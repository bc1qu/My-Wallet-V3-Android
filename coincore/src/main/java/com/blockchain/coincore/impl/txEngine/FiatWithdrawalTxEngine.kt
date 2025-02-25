package com.blockchain.coincore.impl.txEngine

import androidx.annotation.VisibleForTesting
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.FeeLevel
import com.blockchain.coincore.FeeSelection
import com.blockchain.coincore.FiatAccount
import com.blockchain.coincore.PendingTx
import com.blockchain.coincore.TxConfirmationValue
import com.blockchain.coincore.TxEngine
import com.blockchain.coincore.TxResult
import com.blockchain.coincore.TxValidationFailure
import com.blockchain.coincore.ValidationState
import com.blockchain.coincore.fiat.LinkedBankAccount
import com.blockchain.coincore.updateTxValidity
import com.blockchain.core.kyc.domain.model.KycTier
import com.blockchain.core.limits.LimitsDataManager
import com.blockchain.domain.paymentmethods.model.LegacyLimits
import com.blockchain.nabu.Feature
import com.blockchain.nabu.UserIdentity
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.storedatasource.FlushableDataSource
import info.blockchain.balance.AssetCategory
import info.blockchain.balance.Money
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single

class FiatWithdrawalTxEngine(
    @get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val walletManager: CustodialWalletManager,
    private val limitsDataManager: LimitsDataManager,
    private val userIdentity: UserIdentity
) : TxEngine() {

    override val flushableDataSources: List<FlushableDataSource>
        get() = listOf()

    private val userIsGoldVerified: Single<Boolean>
        get() = userIdentity.isVerifiedFor(Feature.TierLevel(KycTier.GOLD))

    override fun assertInputsValid() {
        check(sourceAccount is FiatAccount)
        check(txTarget is LinkedBankAccount)
    }

    override val canTransactFiat: Boolean
        get() = true

    override fun doInitialiseTx(): Single<PendingTx> {
        check(txTarget is LinkedBankAccount)
        check(sourceAccount is FiatAccount)
        val withdrawFeeAndMinLimit = (txTarget as LinkedBankAccount).getWithdrawalFeeAndMinLimit().cache()
        val zeroFiat = Money.zero((sourceAccount as FiatAccount).currency)
        return Single.zip(
            sourceAccount.balance.firstOrError(),
            withdrawFeeAndMinLimit,
            limitsDataManager.getLimits(
                outputCurrency = zeroFiat.currency,
                sourceCurrency = zeroFiat.currency,
                targetCurrency = (txTarget as LinkedBankAccount).currency,
                sourceAccountType = AssetCategory.CUSTODIAL,
                targetAccountType = AssetCategory.NON_CUSTODIAL,
                legacyLimits = withdrawFeeAndMinLimit.map { it as LegacyLimits }
            )
        ) { balance, withdrawalFee, limits ->
            PendingTx(
                amount = zeroFiat,
                limits = limits,
                availableBalance = Money.max(balance.withdrawable - withdrawalFee.fee, zeroFiat),
                feeForFullAvailable = zeroFiat,
                totalBalance = balance.total,
                feeAmount = withdrawalFee.fee,
                selectedFiat = userFiat,
                feeSelection = FeeSelection()
            )
        }
    }

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Single<TxResult> =
        (txTarget as LinkedBankAccount).receiveAddress.flatMapCompletable {
            walletManager.createWithdrawOrder(
                amount = pendingTx.amount,
                bankId = it.address
            )
        }
            .toSingle { TxResult.UnHashedTxResult(amount = pendingTx.amount) }

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> {
        return Single.just(
            pendingTx.copy(
                confirmations = listOfNotNull(
                    TxConfirmationValue.From(sourceAccount),
                    TxConfirmationValue.PaymentMethod(
                        txTarget.label,
                        (txTarget as LinkedBankAccount).accountNumber,
                        (txTarget as LinkedBankAccount).accountType,
                        AssetAction.FiatWithdraw
                    ),
                    TxConfirmationValue.EstimatedCompletion,
                    TxConfirmationValue.Amount(pendingTx.amount, false),
                    if (pendingTx.feeAmount.isPositive) {
                        TxConfirmationValue.TransactionFee(pendingTx.feeAmount)
                    } else null,
                    TxConfirmationValue.Amount(pendingTx.amount.plus(pendingTx.feeAmount), true)
                )
            )
        )
    }

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> =
        Single.just(
            pendingTx.copy(
                amount = amount
            )
        )

    override fun doUpdateFeeLevel(pendingTx: PendingTx, level: FeeLevel, customFeeAmount: Long): Single<PendingTx> {
        require(pendingTx.feeSelection.availableLevels.contains(level))
        return Single.just(pendingTx)
    }

    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        doValidateAmount(pendingTx).updateTxValidity(pendingTx)

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> {
        return if (pendingTx.validationState == ValidationState.UNINITIALISED && pendingTx.amount.isZero) {
            Single.just(pendingTx)
        } else {
            validateAmount(pendingTx).updateTxValidity(pendingTx)
        }
    }

    private fun validateAmount(pendingTx: PendingTx): Completable =
        Completable.defer {
            if (pendingTx.limits != null) {
                when {
                    pendingTx.isMinLimitViolated() -> Completable.error(
                        TxValidationFailure(
                            ValidationState.UNDER_MIN_LIMIT
                        )
                    )
                    pendingTx.isMaxLimitViolated() -> userIsGoldVerified.flatMapCompletable {
                        if (it) {
                            Completable.error(TxValidationFailure(ValidationState.OVER_GOLD_TIER_LIMIT))
                        } else {
                            Completable.error(TxValidationFailure(ValidationState.OVER_SILVER_TIER_LIMIT))
                        }
                    }
                    pendingTx.availableBalance < pendingTx.amount -> Completable.error(
                        TxValidationFailure(
                            ValidationState.INSUFFICIENT_FUNDS
                        )
                    )
                    else -> Completable.complete()
                }
            } else {
                Completable.error(
                    MissingLimitsException(
                        action = AssetAction.FiatWithdraw,
                        source = sourceAccount,
                        target = txTarget
                    )
                )
            }
        }
}
