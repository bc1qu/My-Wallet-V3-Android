package piuk.blockchain.android.coincore.impl

import com.blockchain.swap.nabu.datamanagers.BuySellOrder
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.OrderState
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.Single
import io.reactivex.rxkotlin.Singles
import piuk.blockchain.android.coincore.ActivitySummaryItem
import piuk.blockchain.android.coincore.ActivitySummaryList
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.AvailableActions
import piuk.blockchain.android.coincore.CryptoAccount
import piuk.blockchain.android.coincore.CryptoAddress
import piuk.blockchain.android.coincore.CustodialTradingActivitySummaryItem
import piuk.blockchain.android.coincore.FiatAccount
import piuk.blockchain.android.coincore.ReceiveAddress
import piuk.blockchain.android.coincore.SendState
import piuk.blockchain.android.coincore.SendTarget
import piuk.blockchain.android.coincore.TradingAccount
import piuk.blockchain.android.coincore.TransactionProcessor
import piuk.blockchain.android.coincore.TransferError
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.utils.extensions.mapList
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

open class CustodialTradingAccount(
    override val asset: CryptoCurrency,
    override val label: String,
    override val exchangeRates: ExchangeRateDataManager,
    val custodialWalletManager: CustodialWalletManager,
    private val isNoteSupported: Boolean = false
) : CryptoAccountBase(), TradingAccount {

    private val nabuAccountExists = AtomicBoolean(false)
    private val hasFunds = AtomicBoolean(false)

    override val receiveAddress: Single<ReceiveAddress>
        get() = Single.error(NotImplementedError("Custodial accounts don't support receive"))

    override fun requireSecondPassword(): Single<Boolean> =
        Single.just(false)

    override val accountBalance: Single<Money>
        get() = custodialWalletManager.getTotalBalanceForAsset(asset)
            .doOnComplete { nabuAccountExists.set(false) }
            .doOnSuccess { nabuAccountExists.set(true) }
            .toSingle(CryptoValue.zero(asset))
            .onErrorReturn {
                Timber.d("Unable to get custodial trading total balance: $it")
                CryptoValue.zero(asset)
            }
            .doOnSuccess { hasFunds.set(it.isPositive) }
            .map { it as Money }

    override val actionableBalance: Single<Money>
        get() = custodialWalletManager.getActionableBalanceForAsset(asset)
            .doOnComplete { nabuAccountExists.set(false) }
            .doOnSuccess { nabuAccountExists.set(true) }
            .toSingle(CryptoValue.zero(asset))
            .onErrorReturn {
                Timber.d("Unable to get custodial trading actionable balance: $it")
                CryptoValue.zero(asset)
            }
            .doOnSuccess { hasFunds.set(it.isPositive) }
            .map { it as Money }

    override val activity: Single<ActivitySummaryList>
        get() = custodialWalletManager.getAllBuyOrdersFor(asset)
            .mapList { buyOrderToSummary(it) }
            .filterActivityStates()
            .doOnSuccess { setHasTransactions(it.isNotEmpty()) }
            .onErrorReturn { emptyList() }

    val isConfigured: Boolean
        get() = nabuAccountExists.get()

    override val isFunded: Boolean
        get() = hasFunds.get()

    override val isDefault: Boolean =
        false // Default is, presently, only ever a non-custodial account.

    override fun createSendProcessor(sendTo: SendTarget): Single<TransactionProcessor> =
        when (sendTo) {
            is CryptoAddress -> Single.just(
                CustodialTransferProcessor(
                    sendingAccount = this,
                    sendTarget = sendTo,
                    exchangeRates = exchangeRates,
                    walletManager = custodialWalletManager,
                    isNoteSupported = isNoteSupported
                )
            )
            is CryptoAccount -> sendTo.receiveAddress.map {
                CustodialTransferProcessor(
                    sendingAccount = this,
                    sendTarget = it as CryptoAddress,
                    exchangeRates = exchangeRates,
                    walletManager = custodialWalletManager,
                    isNoteSupported = isNoteSupported
                )
            }
            is FiatAccount -> Single.just(
                CustodialSellProcessor(
                    sendingAccount = this,
                    sendTarget = sendTo,
                    walletManager = custodialWalletManager,
                    exchangeRates = exchangeRates
                )
            )
            else -> Single.error(TransferError("Cannot send custodial crypto to a non-crypto target"))
        }

    override val sendState: Single<SendState>
        get() = Singles.zip(
            accountBalance,
            actionableBalance
        ) { total, available ->
            when {
                total <= CryptoValue.zero(asset) -> SendState.NO_FUNDS
                total - available <= CryptoValue.zero(asset) -> SendState.FUNDS_LOCKED
                else -> SendState.CAN_SEND
            }
        }

    override val actions: AvailableActions
        get() =
            mutableSetOf(
                AssetAction.ViewActivity
            ).apply {
                if (isFunded) {
                    add(AssetAction.Sell)
                    add(AssetAction.NewSend)
                }
            }

    private fun buyOrderToSummary(buyOrder: BuySellOrder): ActivitySummaryItem =
        CustodialTradingActivitySummaryItem(
            exchangeRates = exchangeRates,
            cryptoCurrency = buyOrder.crypto.currency,
            value = buyOrder.crypto,
            fundedFiat = buyOrder.fiat,
            txId = buyOrder.id,
            timeStampMs = buyOrder.created.time,
            status = buyOrder.state,
            fee = buyOrder.fee ?: FiatValue.zero(buyOrder.fiat.currencyCode),
            account = this,
            paymentMethodId = buyOrder.paymentMethodId
        )

    // Stop gap filter, until we finalise which item we wish to display to the user.
    // TODO: This can be done via the API when it's settled
    private fun Single<ActivitySummaryList>.filterActivityStates(): Single<ActivitySummaryList> {
        return flattenAsObservable { list ->
            list.filter {
                it is CustodialTradingActivitySummaryItem && displayedStates.contains(it.status)
            }
        }.toList()
    }

    companion object {
        private val displayedStates = setOf(
            OrderState.FINISHED,
            OrderState.AWAITING_FUNDS,
            OrderState.PENDING_EXECUTION
        )
    }
}
