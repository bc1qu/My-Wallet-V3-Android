package piuk.blockchain.android.simplebuy

import com.blockchain.api.NabuApiException
import com.blockchain.api.NabuApiExceptionFactory
import com.blockchain.api.NabuErrorCodes
import com.blockchain.api.NabuErrorCodes.MaxPaymentBankAccountLinkAttempts
import com.blockchain.api.NabuErrorCodes.MaxPaymentBankAccounts
import com.blockchain.api.isInternetConnectionError
import com.blockchain.api.paymentmethods.models.PaymentContact
import com.blockchain.api.paymentmethods.models.SimpleBuyConfirmationAttributes
import com.blockchain.banking.BankPartnerCallbackProvider
import com.blockchain.banking.BankTransferAction
import com.blockchain.coincore.fiat.isOpenBankingCurrency
import com.blockchain.commonarch.presentation.base.ActivityIndicator
import com.blockchain.commonarch.presentation.base.trackProgress
import com.blockchain.commonarch.presentation.mvi.MviModel
import com.blockchain.core.buy.BuyOrdersCache
import com.blockchain.core.kyc.domain.model.KycTier
import com.blockchain.core.limits.TxLimits
import com.blockchain.domain.fiatcurrencies.FiatCurrenciesService
import com.blockchain.domain.paymentmethods.model.BankPartner
import com.blockchain.domain.paymentmethods.model.BankState
import com.blockchain.domain.paymentmethods.model.CardStatus
import com.blockchain.domain.paymentmethods.model.GooglePayAddress
import com.blockchain.domain.paymentmethods.model.LinkedPaymentMethod
import com.blockchain.domain.paymentmethods.model.PaymentMethod
import com.blockchain.domain.paymentmethods.model.PaymentMethodType
import com.blockchain.domain.paymentmethods.model.UndefinedPaymentMethod
import com.blockchain.enviroment.EnvironmentConfig
import com.blockchain.extensions.exhaustive
import com.blockchain.featureflag.FeatureFlag
import com.blockchain.logging.RemoteLogger
import com.blockchain.nabu.Feature
import com.blockchain.nabu.FeatureAccess
import com.blockchain.nabu.UserIdentity
import com.blockchain.nabu.datamanagers.ApprovalErrorStatus
import com.blockchain.nabu.datamanagers.BuySellOrder
import com.blockchain.nabu.datamanagers.CardAttributes
import com.blockchain.nabu.datamanagers.CardPaymentState
import com.blockchain.nabu.datamanagers.CurrencyPair
import com.blockchain.nabu.datamanagers.OrderState
import com.blockchain.nabu.datamanagers.RecurringBuyOrder
import com.blockchain.nabu.models.data.RecurringBuyFrequency
import com.blockchain.nabu.models.data.RecurringBuyState
import com.blockchain.network.PollResult
import com.blockchain.outcome.getOrThrow
import com.blockchain.payments.core.CardAcquirer
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.FiatCurrency
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.Singles
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import kotlinx.coroutines.rx3.rxSingle
import piuk.blockchain.android.cards.CardAcquirerCredentials
import piuk.blockchain.android.cards.partners.CardActivator
import piuk.blockchain.android.domain.usecases.GetEligibilityAndNextPaymentDateUseCase
import piuk.blockchain.android.domain.usecases.LinkAccess
import piuk.blockchain.android.rating.domain.service.AppRatingService
import piuk.blockchain.android.ui.linkbank.domain.openbanking.usecase.GetSafeConnectTosLinkUseCase
import piuk.blockchain.android.ui.transactionflow.engine.TransactionErrorState
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import retrofit2.HttpException
import timber.log.Timber

class SimpleBuyModel(
    fiatCurrenciesService: FiatCurrenciesService,
    private val buyOrdersCache: BuyOrdersCache,
    initialState: SimpleBuyState,
    uiScheduler: Scheduler,
    private val serializer: SimpleBuyPrefsSerializer,
    private val cardActivator: CardActivator,
    private val interactor: SimpleBuyInteractor,
    private val _activityIndicator: Lazy<ActivityIndicator?>,
    private val getEligibilityAndNextPaymentDateUseCase: GetEligibilityAndNextPaymentDateUseCase,
    environmentConfig: EnvironmentConfig,
    remoteLogger: RemoteLogger,
    private val bankPartnerCallbackProvider: BankPartnerCallbackProvider,
    private val createBuyOrderUseCase: CreateBuyOrderUseCase,
    private val userIdentity: UserIdentity,
    private val getSafeConnectTosLinkUseCase: GetSafeConnectTosLinkUseCase,
    private val appRatingService: AppRatingService,
    private val cardPaymentAsyncFF: FeatureFlag,
) : MviModel<SimpleBuyState, SimpleBuyIntent>(
    initialState = serializer.fetch() ?: initialState.withSelectedFiatCurrency(fiatCurrenciesService),
    uiScheduler = uiScheduler,
    environmentConfig = environmentConfig,
    remoteLogger = remoteLogger
) {

    private val activityIndicator: ActivityIndicator? by unsafeLazy {
        _activityIndicator.value
    }

    override fun performAction(previousState: SimpleBuyState, intent: SimpleBuyIntent): Disposable? =
        when (intent) {
            is SimpleBuyIntent.UpdateExchangeRate -> interactor.updateExchangeRate(intent.fiatCurrency, intent.asset)
                .subscribeBy(
                    onSuccess = { process(SimpleBuyIntent.ExchangeRateUpdated(it)) },
                    onError = { processOrderErrors(it) }
                )
            is SimpleBuyIntent.CancelOrder,
            is SimpleBuyIntent.CancelOrderAndResetAuthorisation,
            -> (
                previousState.id?.let {
                    interactor.cancelOrder(it)
                } ?: Completable.complete()
                ).trackProgress(activityIndicator)
                .subscribeBy(
                    onComplete = { process(SimpleBuyIntent.OrderCanceled) },
                    onError = { processOrderErrors(it) }
                )

            is SimpleBuyIntent.ListenToOrderCreation -> createBuyOrderUseCase.buyOrderAndQuote.firstOrError()
                .trackProgress(activityIndicator)
                .map { it.getOrThrow() }
                .subscribeBy(
                    onSuccess = {
                        process(SimpleBuyIntent.OrderCreated(buyOrder = it.buyOrder, quote = it.quote))
                    },
                    onError = { processOrderErrors(it) }
                )

            is SimpleBuyIntent.ListenToQuotesUpdate ->
                createBuyOrderUseCase.buyOrderAndQuote
                    .map { it.getOrThrow() }
                    .subscribeBy(
                        onNext = {
                            process(SimpleBuyIntent.OrderCreated(buyOrder = it.buyOrder, quote = it.quote))
                        },
                        onError = { processOrderErrors(it) }
                    )

            is SimpleBuyIntent.CancelOrderIfAnyAndCreatePendingOne -> {
                createBuyOrderUseCase.createOrderAndStartsQuotesFetching(
                    previousState.id,
                    previousState.selectedCryptoAsset,
                    previousState.selectedPaymentMethod,
                    previousState.order,
                    previousState.recurringBuyFrequency
                )
                process(SimpleBuyIntent.ListenToOrderCreation)
                null
            }

            is SimpleBuyIntent.StopQuotesUpdate -> {
                createBuyOrderUseCase.stopQuoteFetching(intent.shouldResetOrder)
                null
            }
            is SimpleBuyIntent.FetchKycState -> interactor.pollForKycState()
                .subscribeBy(
                    onSuccess =
                    { process(it) },
                    onError =
                    { /*never fails. will return SimpleBuyIntent.KycStateUpdated(KycState.FAILED)*/ }
                )

            is SimpleBuyIntent.LinkBankTransferRequested -> interactor.linkNewBank(previousState.fiatCurrency)
                .trackProgress(activityIndicator)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = {
                        when ((it as? NabuApiException)?.getErrorCode()) {
                            MaxPaymentBankAccounts ->
                                process(SimpleBuyIntent.ErrorIntent(ErrorState.BankLinkMaxAccountsReached(it)))
                            MaxPaymentBankAccountLinkAttempts ->
                                process(SimpleBuyIntent.ErrorIntent(ErrorState.BankLinkMaxAttemptsReached(it)))
                            else ->
                                process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported))
                        }
                    }
                )
            is SimpleBuyIntent.TryToLinkABankTransfer -> {
                interactor.eligiblePaymentMethodsTypes(previousState.fiatCurrency).map {
                    it.any { paymentMethod -> paymentMethod.type == PaymentMethodType.BANK_TRANSFER }
                }.subscribeBy(
                    onSuccess = { isEligibleToLinkABank ->
                        if (isEligibleToLinkABank) {
                            process(SimpleBuyIntent.LinkBankTransferRequested)
                        } else {
                            process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported))
                        }
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported))
                    }
                )
            }

            is SimpleBuyIntent.FetchWithdrawLockTime -> {
                require(previousState.selectedPaymentMethod != null)
                interactor.fetchWithdrawLockTime(
                    previousState.selectedPaymentMethod.paymentMethodType,
                    previousState.fiatCurrency
                ).subscribeBy(
                    onSuccess = { process(it) },
                    onError = { processOrderErrors(it) }
                )
            }
            is SimpleBuyIntent.BuyButtonClicked -> {
                val selectedPaymentMethod = previousState.selectedPaymentMethodDetails
                if (selectedPaymentMethod is PaymentMethod.UndefinedBankAccount) {
                    process(SimpleBuyIntent.AddNewPaymentMethodRequested(selectedPaymentMethod))
                    null
                } else {
                    process(SimpleBuyIntent.CancelOrderIfAnyAndCreatePendingOne)
                    interactor.checkTierLevel()
                        .subscribeBy(
                            onSuccess =
                            { process(it) },
                            onError =
                            { processOrderErrors(it) }
                        )
                }
            }

            is SimpleBuyIntent.FetchPaymentDetails ->
                processGetPaymentMethod(
                    fiatCurrency = intent.fiatCurrency,
                    preselectedId = intent.selectedPaymentMethodId,
                    previousSelectedId = previousState.selectedPaymentMethod?.id,
                    usePrefilledAmount = false
                )

            is SimpleBuyIntent.FetchSuggestedPaymentMethod -> {
                val lastPaymentMethodId = interactor.getLastPaymentMethodId()

                processGetPaymentMethod(
                    fiatCurrency = intent.fiatCurrency,
                    preselectedId = intent.selectedPaymentMethodId ?: lastPaymentMethodId,
                    previousSelectedId = previousState.selectedPaymentMethod?.id,
                    usePrefilledAmount = intent.usePrefilledAmount
                )
            }

            is SimpleBuyIntent.SelectedPaymentChangedLimits -> {
                require(previousState.selectedCryptoAsset != null)

                process(
                    SimpleBuyIntent.GetPrefillAndQuickFillAmounts(
                        limits = intent.limits,
                        assetCode = previousState.selectedCryptoAsset.networkTicker,
                        fiatCurrency = previousState.fiatCurrency,
                        usePrefilledAmount = false
                    )
                )
                null
            }

            is SimpleBuyIntent.PaymentMethodChangeRequested -> {
                validateAmountAndUpdatePaymentMethod(
                    paymentMethod = intent.paymentMethod,
                    state = previousState
                )
            }
            is SimpleBuyIntent.PaymentMethodsUpdated -> {
                check(previousState.selectedCryptoAsset != null)
                intent.selectedPaymentMethod?.let { selectedPaymentMethod ->
                    onPaymentMethodsUpdated(
                        previousState.selectedCryptoAsset,
                        previousState.fiatCurrency,
                        selectedPaymentMethod,
                        intent.paymentOptions.availablePaymentMethods,
                        intent.usePrefilledAmount
                    )
                } ?: run {
                    process(SimpleBuyIntent.ErrorIntent(ErrorState.BuyPaymentMethodsUnavailable))
                    null
                }
            }
            is SimpleBuyIntent.MakePayment ->
                interactor.fetchOrder(intent.orderId)
                    .subscribeBy(
                        onError =
                        {
                            processOrderErrors(it)
                        },
                        onSuccess =
                        {
                            if (it.attributes != null) {
                                handleOrderAttrs(it)
                            } else {
                                pollForOrderStatus()
                            }
                        }
                    )
            is SimpleBuyIntent.GetAuthorisationUrl ->
                interactor.pollForAuthorisationUrl(intent.orderId)
                    .subscribeBy(
                        onSuccess =
                        {
                            when (it) {
                                is PollResult.FinalResult -> {
                                    it.value.attributes?.authorisationUrl?.let { url ->
                                        handleBankAuthorisationPayment(it.value.paymentMethodId, url)
                                    }
                                }
                                is PollResult.TimeOut -> process(
                                    SimpleBuyIntent.ErrorIntent(
                                        ErrorState.BankLinkingTimeout
                                    )
                                )
                                is PollResult.Cancel -> {
                                }
                            }
                        },
                        onError =
                        {
                            processOrderErrors(it)
                        }
                    )
            is SimpleBuyIntent.UpdatePaymentMethodsAndAddTheFirstEligible -> fetchEligiblePaymentMethods(
                intent.fiatCurrency
            ).trackProgress(activityIndicator).subscribeBy(
                onSuccess =
                {
                    process(
                        updateSelectedAndAvailablePaymentMethodMethodsIntent(
                            previousSelectedId = previousState.selectedPaymentMethod?.id,
                            availablePaymentMethods = it,
                            preselectedId = null,
                            usePrefilledAmount = true
                        )
                    )

                    it.firstOrNull { paymentMethod -> paymentMethod.isEligible }
                        ?.let { paymentMethod ->
                            process(SimpleBuyIntent.AddNewPaymentMethodRequested(paymentMethod))
                        }
                },
                onError =
                {
                    processOrderErrors(it)
                }
            )
            is SimpleBuyIntent.ConfirmOrder -> {
                require(previousState.selectedCryptoAsset != null) { "ConfirmOrder Missing assetInfo" }
                processConfirmOrder(
                    id = previousState.id,
                    selectedPaymentMethod = previousState.selectedPaymentMethod,
                    amount = previousState.amount,
                    pair = CurrencyPair(previousState.selectedCryptoAsset, previousState.fiatCurrency).rawValue,
                )
            }
            is SimpleBuyIntent.ConfirmGooglePayOrder -> {
                require(previousState.selectedCryptoAsset != null) { "ConfirmGooglePayOrder Missing assetInfo" }
                processConfirmOrder(
                    id = previousState.id,
                    googlePayPayload = intent.googlePayPayload,
                    googlePayBeneficiaryId = previousState.googlePayDetails?.beneficiaryId,
                    googlePayAddress = intent.googlePayAddress,
                    amount = previousState.amount,
                    pair = CurrencyPair(previousState.selectedCryptoAsset, previousState.fiatCurrency).rawValue,
                    selectedPaymentMethod = previousState.selectedPaymentMethod,
                )
            }
            is SimpleBuyIntent.RecurringBuySuggestionAccepted -> {
                require(previousState.id != null) { "RecurringBuySuggestionAccepted Missing id" }
                processConfirmOrderAndCreateRecurringBuy(
                    orderId = previousState.id,
                    googlePayPayload = intent.googlePayPayload,
                    googlePayBeneficiaryId = previousState.googlePayDetails?.beneficiaryId,
                    googlePayAddress = intent.googlePayAddress,
                    asset = previousState.selectedCryptoAsset,
                    order = previousState.order,
                    selectedPaymentMethod = previousState.selectedPaymentMethod,
                    recurringBuyFrequency = intent.recurringBuyFrequency
                )
            }
            is SimpleBuyIntent.FinishedFirstBuy -> null
            is SimpleBuyIntent.CheckOrderStatus -> interactor.pollForOrderStatus(
                previousState.id ?: throw IllegalStateException("Order Id not available")
            ).subscribeBy(
                onSuccess =
                { buySellOrder ->
                    processOrderStatus(buySellOrder.value)
                },
                onError =
                {
                    processOrderErrors(it)
                }
            )
            is SimpleBuyIntent.PaymentSucceeded -> {
                interactor.checkTierLevel().map { it.kycState != KycState.VERIFIED_AND_ELIGIBLE }.subscribeBy(
                    onSuccess = {
                        if (it) process(SimpleBuyIntent.UnlockHigherLimits)
                    },
                    onError = {
                        processOrderErrors(it)
                    }
                )
            }

            is SimpleBuyIntent.CreateRecurringBuy ->
                createRecurringBuy(
                    previousState.selectedCryptoAsset,
                    previousState.order,
                    previousState.selectedPaymentMethod,
                    intent.recurringBuyFrequency,
                ).trackProgress(activityIndicator)
                    .subscribeBy(
                        onSuccess = {
                            process(
                                SimpleBuyIntent.RecurringBuyCreated(
                                    recurringBuyId = it.id.orEmpty(),
                                    recurringBuyFrequency = intent.recurringBuyFrequency
                                )
                            )
                        },
                        onError = { processOrderErrors(it) }
                    )
            is SimpleBuyIntent.AmountUpdated -> validateAmount(
                balance = previousState.availableBalance,
                amount = intent.amount,
                buyLimits = previousState.buyOrderLimits,
                paymentMethodLimits = previousState.selectedPaymentMethodLimits
            ).subscribeBy(
                onSuccess =
                {
                    process(SimpleBuyIntent.UpdateErrorState(it))
                },
                onError =
                {
                    processOrderErrors(it)
                }
            )
            is SimpleBuyIntent.GetPrefillAndQuickFillAmounts ->
                interactor.getPrefillAndQuickFillAmounts(
                    limits = intent.limits,
                    assetCode = intent.assetCode,
                    fiatCurrency = intent.fiatCurrency,
                ).subscribeBy(
                    onSuccess = { (amountToPrepopulate, quickFillData) ->
                        quickFillData?.let {
                            process(SimpleBuyIntent.PopulateQuickFillButtons(it))
                        }
                        if (intent.usePrefilledAmount) {
                            process(SimpleBuyIntent.PrefillEnterAmount(amountToPrepopulate as FiatValue))
                        }
                    },
                    onError = {
                        // do nothing - fail silently
                        Timber.e("Simplebuy: Getting prefill and quickfill data failed - ${it.message}")
                    }
                )
            is SimpleBuyIntent.GooglePayInfoRequested -> requestGooglePayInfo(
                previousState.fiatCurrency
            ).subscribeBy(
                onSuccess =
                {
                    process(it)
                },
                onError =
                {
                    processOrderErrors(it)
                }
            )
            is SimpleBuyIntent.FetchEligibility -> userIdentity.userAccessForFeature(Feature.Buy)
                .subscribeBy(
                    onSuccess =
                    { buyEligibility ->
                        if (buyEligibility is FeatureAccess.Granted) {
                            process(
                                SimpleBuyIntent.UpgradeEligibilityTransactionsLimit(buyEligibility.transactionsLimit)
                            )
                        }
                    },
                    onError =
                    {
                        processOrderErrors(it)
                    }
                )

            SimpleBuyIntent.GetSafeConnectTermsOfServiceLink -> {
                rxSingle { getSafeConnectTosLinkUseCase() }
                    .subscribe { termsOfServiceLink ->
                        process(SimpleBuyIntent.UpdateSafeConnectTermsOfServiceLink(termsOfServiceLink))
                    }
            }

            SimpleBuyIntent.CheckForOrderCompletedSideEvents -> {
                if (interactor.shouldShowAppRating()) {
                    rxSingle { appRatingService.shouldShowRating() }.subscribe { showRating ->
                        if (showRating) process(SimpleBuyIntent.ShowAppRating)
                    }
                } else {
                    null
                }
            }
            else -> null
        }

    private fun processOrderStatus(buySellOrder: BuySellOrder) {
        when {
            buySellOrder.state == OrderState.FINISHED -> {
                updatePersistingCountersForCompletedOrders(
                    pair = buySellOrder.pair,
                    amount = buySellOrder.source.toStringWithoutSymbol(),
                    paymentMethodId = buySellOrder.paymentMethodId
                )
                process(SimpleBuyIntent.PaymentSucceeded)
            }
            buySellOrder.state.isPending() -> {
                process(SimpleBuyIntent.PaymentPending)
            }
            buySellOrder.state.hasFailed() -> {
                interactor.saveOrderAmountAndPaymentMethodId(
                    pair = buySellOrder.pair,
                    amount = buySellOrder.source.toStringWithoutSymbol(),
                    paymentId = buySellOrder.paymentMethodId
                )
                handleErrorState(buySellOrder.paymentError)
            }
            else -> {
                interactor.saveOrderAmountAndPaymentMethodId(
                    pair = buySellOrder.pair,
                    amount = buySellOrder.source.toStringWithoutSymbol(),
                    paymentId = buySellOrder.paymentMethodId
                )
                when (val cardAttributes = buySellOrder.attributes?.cardAttributes ?: CardAttributes.Empty) {
                    is CardAttributes.EveryPay -> {
                        handleCardPaymentState(cardAttributes.paymentState)
                    }
                    is CardAttributes.Provider -> {
                        handleCardPaymentState(cardAttributes.paymentState)
                    }
                    is CardAttributes.Empty -> {
                        // Usual path for any non-card payment
                        handleErrorState(buySellOrder.approvalErrorStatus)
                    }
                }
            }
        }
    }

    private fun onPaymentMethodsUpdated(
        asset: AssetInfo,
        fiatCurrency: FiatCurrency,
        selectedPaymentMethod: SelectedPaymentMethod,
        availablePaymentMethods: List<PaymentMethod>,
        usePrefilledAmount: Boolean
    ): Disposable {
        return interactor.fetchBuyLimits(
            fiat = fiatCurrency,
            asset = asset,
            paymentMethodType = selectedPaymentMethod.paymentMethodType
        )
            .trackProgress(activityIndicator)
            .subscribeBy(
                onError = {
                    processOrderErrors(it)
                },
                onSuccess = { limits ->
                    val paymentMethodsWithNotEnoughBalance = availablePaymentMethods.filter { paymentMethod ->
                        paymentMethod.availableBalance?.let { balance ->
                            balance < limits.minAmount
                        } ?: false
                    }

                    val paymentMethodsWithEnoughBalance = availablePaymentMethods.filter { paymentMethod ->
                        paymentMethod.availableBalance?.let { balance ->
                            balance >= limits.minAmount
                        } ?: true
                    }

                    // We are allowed to refresh the payment methods only when
                    // 1. there is at least one with enough funds so making sure that in the end
                    // something will be selected
                    // 2. when at least one with not enough funds has been detected.

                    val shouldRefreshPaymentMethods =
                        paymentMethodsWithEnoughBalance.isNotEmpty() && paymentMethodsWithNotEnoughBalance.isNotEmpty()

                    var paymentOptions = PaymentOptions(
                        paymentMethodsWithEnoughBalance
                    )

                    if (shouldRefreshPaymentMethods) {
                        val newSelectedPaymentMethodId = selectedMethodId(
                            preselectedId = null,
                            previousSelectedId = null,
                            availablePaymentMethods = paymentMethodsWithEnoughBalance
                        )

                        val updateSelectedPaymentMethod = paymentMethodsWithEnoughBalance.firstOrNull { paymentMethod ->
                            newSelectedPaymentMethodId == paymentMethod.id
                        }?.let { sPaymentMethod ->
                            SelectedPaymentMethod(
                                sPaymentMethod.id,
                                (sPaymentMethod as? PaymentMethod.Card)?.partner,
                                sPaymentMethod.detailedLabel(),
                                sPaymentMethod.type,
                                sPaymentMethod.isEligible
                            )
                        }

                        process(
                            SimpleBuyIntent.UpdatedBuyLimitsAndPaymentMethods(
                                limits = limits,
                                paymentOptions = paymentOptions,
                                selectedPaymentMethod = updateSelectedPaymentMethod
                            )
                        )
                    } else {
                        paymentOptions = PaymentOptions(availablePaymentMethods)
                        process(
                            SimpleBuyIntent.UpdatedBuyLimitsAndPaymentMethods(
                                limits = limits,
                                paymentOptions = paymentOptions,
                                selectedPaymentMethod = selectedPaymentMethod
                            )
                        )
                    }

                    val selectedPaymentMethodDetails = selectedPaymentMethod.id.let { id ->
                        paymentOptions.availablePaymentMethods.firstOrNull { it.id == id }
                    }

                    val selectedPaymentMethodLimits = selectedPaymentMethodDetails?.let {
                        TxLimits.fromAmounts(min = it.limits.min, max = it.limits.max)
                    } ?: TxLimits.withMinAndUnlimitedMax(FiatValue.zero(fiatCurrency))

                    val limitsPrefill = limits.combineWith(selectedPaymentMethodLimits)

                    process(
                        SimpleBuyIntent.GetPrefillAndQuickFillAmounts(
                            limits = limitsPrefill,
                            assetCode = asset.networkTicker,
                            fiatCurrency = fiatCurrency,
                            usePrefilledAmount = usePrefilledAmount
                        )
                    )
                }

            )
    }

    private fun validateAmountAndUpdatePaymentMethod(
        paymentMethod: PaymentMethod,
        state: SimpleBuyState,
    ): Disposable {
        val balance = paymentMethod.availableBalance
        require(state.selectedCryptoAsset != null)
        return interactor.fetchBuyLimits(
            fiat = state.fiatCurrency,
            asset = state.selectedCryptoAsset,
            paymentMethodType = paymentMethod.type
        ).trackProgress(activityIndicator).flatMap { limits ->
            validateAmount(
                amount = state.amount,
                balance = balance,
                buyLimits = limits,
                paymentMethodLimits = TxLimits.fromAmounts(paymentMethod.limits.min, paymentMethod.limits.max)
            ).map { errorState ->
                errorState to limits
            }
        }.subscribeBy(
            onSuccess = { (errorState, limits) ->
                process(SimpleBuyIntent.TxLimitsUpdated(limits))

                if (paymentMethod.isEligible && paymentMethod is UndefinedPaymentMethod) {
                    process(SimpleBuyIntent.AddNewPaymentMethodRequested(paymentMethod))
                } else {
                    process(SimpleBuyIntent.SelectedPaymentMethodUpdate(paymentMethod))
                    process(SimpleBuyIntent.UpdateErrorState(errorState))
                }
            },
            onError = {
                processOrderErrors(it)
            }
        )
    }

    private fun validateAmount(
        amount: Money,
        balance: Money?,
        buyLimits: TxLimits,
        paymentMethodLimits: TxLimits,
    ): Single<TransactionErrorState> =
        Single.defer {
            when {
                balance != null && balance < amount -> Single.just(TransactionErrorState.INSUFFICIENT_FUNDS)
                buyLimits.isMinViolatedByAmount(amount) -> Single.just(TransactionErrorState.BELOW_MIN_LIMIT)
                buyLimits.isMaxViolatedByAmount(amount) -> {
                    userIdentity.isVerifiedFor(Feature.TierLevel(KycTier.GOLD))
                        .onErrorReturnItem(false)
                        .map { gold ->
                            if (gold)
                                TransactionErrorState.OVER_GOLD_TIER_LIMIT
                            else
                                TransactionErrorState.OVER_SILVER_TIER_LIMIT
                        }
                }
                paymentMethodLimits.isMaxViolatedByAmount(amount) -> Single.just(
                    TransactionErrorState.ABOVE_MAX_PAYMENT_METHOD_LIMIT
                )

                paymentMethodLimits.isMinViolatedByAmount(amount) -> Single.just(
                    TransactionErrorState.BELOW_MIN_PAYMENT_METHOD_LIMIT
                )
                else -> Single.just(TransactionErrorState.NONE)
            }
        }

    private fun handleErrorState(approvalErrorStatus: ApprovalErrorStatus) {
        when (approvalErrorStatus) {
            ApprovalErrorStatus.Invalid -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApproveBankInvalid)
            )
            ApprovalErrorStatus.Failed -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankFailed)
            )
            ApprovalErrorStatus.Declined -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankDeclined)
            )
            ApprovalErrorStatus.Rejected -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankRejected)
            )
            ApprovalErrorStatus.Expired -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankExpired)
            )
            ApprovalErrorStatus.LimitedExceeded -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankLimitedExceed)
            )
            ApprovalErrorStatus.AccountInvalid -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankAccountInvalid)
            )
            ApprovalErrorStatus.FailedInternal -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankFailedInternal)
            )
            ApprovalErrorStatus.InsufficientFunds -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankInsufficientFunds)
            )
            ApprovalErrorStatus.CardAcquirerDecline -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardAcquirerDeclined)
            )
            ApprovalErrorStatus.CardBlockchainDecline -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardBlockchainDeclined)
            )
            ApprovalErrorStatus.CardCreateAbandoned -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateAbandoned)
            )
            ApprovalErrorStatus.CardCreateBankDeclined -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateBankDeclined)
            )
            ApprovalErrorStatus.CardCreateDebitOnly -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateDebitOnly)
            )
            ApprovalErrorStatus.CardCreateExpired -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateExpired)
            )
            ApprovalErrorStatus.CardCreateFailed -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateFailed)
            )
            ApprovalErrorStatus.CardCreateNoToken -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardNoToken)
            )
            ApprovalErrorStatus.CardDuplicate -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardDuplicated)
            )
            ApprovalErrorStatus.CardPaymentDebitOnly -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentDebitOnly)
            )
            ApprovalErrorStatus.CardPaymentFailed -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentFailed)
            )
            ApprovalErrorStatus.CardPaymentNotSupported -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentNotSupported)
            )
            ApprovalErrorStatus.InsufficientCardFunds -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.InsufficientCardFunds)
            )
            is ApprovalErrorStatus.Undefined -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankUndefinedError(approvalErrorStatus.error))
            )
            ApprovalErrorStatus.None -> throw IllegalStateException("Cannot handle error for order")
        }.exhaustive
    }

    private fun handleCardPaymentState(paymentState: CardPaymentState) {
        when (paymentState) {
            CardPaymentState.WAITING_FOR_3DS -> {
                // This is handled in handleCardPayment for now
            }
            CardPaymentState.INITIAL,
            CardPaymentState.CONFIRMED_3DS,
            CardPaymentState.SETTLED,
            CardPaymentState.VOIDED,
            CardPaymentState.ABANDONED,
            CardPaymentState.FAILED,
            -> {
                // Continue polling
            }
        }
    }

    private fun processGetPaymentMethod(
        fiatCurrency: FiatCurrency,
        preselectedId: String?,
        previousSelectedId: String?,
        usePrefilledAmount: Boolean
    ) =
        fetchEligiblePaymentMethods(fiatCurrency)
            .flatMap { paymentMethods ->
                getEligibilityAndNextPaymentDateUseCase(Unit)
                    .map { paymentMethods to it }
                    .onErrorReturn { paymentMethods to emptyList() }
            }.trackProgress(activityIndicator)
            .subscribeBy(
                onSuccess = { (availablePaymentMethods, eligibilityNextPaymentList) ->
                    process(SimpleBuyIntent.RecurringBuyEligibilityUpdated(eligibilityNextPaymentList))
                    process(
                        updateSelectedAndAvailablePaymentMethodMethodsIntent(
                            preselectedId = preselectedId,
                            previousSelectedId = previousSelectedId,
                            availablePaymentMethods = availablePaymentMethods,
                            usePrefilledAmount = usePrefilledAmount
                        )
                    )
                },
                onError = {
                    processOrderErrors(it)
                }
            )

    private fun fetchEligiblePaymentMethods(fiatCurrency: FiatCurrency) = interactor.paymentMethods(
        fiatCurrency
    ).map {
        it.toPaymentMethods(fiatCurrency)
    }

    private fun updateSelectedAndAvailablePaymentMethodMethodsIntent(
        preselectedId: String?,
        previousSelectedId: String?,
        availablePaymentMethods: List<PaymentMethod>,
        usePrefilledAmount: Boolean
    ): SimpleBuyIntent.PaymentMethodsUpdated {

        val paymentOptions = PaymentOptions(
            availablePaymentMethods = availablePaymentMethods
        )

        val selectedPaymentMethodId = selectedMethodId(
            preselectedId = preselectedId,
            previousSelectedId = previousSelectedId,
            availablePaymentMethods = availablePaymentMethods
        )
        val selectedPaymentMethod = availablePaymentMethods.firstOrNull {
            it.id == selectedPaymentMethodId
        }

        return SimpleBuyIntent.PaymentMethodsUpdated(
            usePrefilledAmount = usePrefilledAmount,
            paymentOptions = paymentOptions,
            selectedPaymentMethod = selectedPaymentMethod?.let {
                SelectedPaymentMethod(
                    selectedPaymentMethod.id,
                    (selectedPaymentMethod as? PaymentMethod.Card)?.partner,
                    selectedPaymentMethod.detailedLabel(),
                    selectedPaymentMethod.type,
                    selectedPaymentMethod.isEligible
                )
            }
        )
    }

    // If no preselected Id and no payment method already set, we want the first eligible,
    // if none present, check if available is only 1 and preselect it. Otherwise, don't preselect anything
    private fun selectedMethodId(
        preselectedId: String?,
        previousSelectedId: String?,
        availablePaymentMethods: List<PaymentMethod>,
    ): String? =
        preselectedId?.let { availablePaymentMethods.firstOrNull { it.id == preselectedId }?.id }
            ?: interactor.getLastPaymentMethodId()
                ?.let { lastPaymentMethod -> availablePaymentMethods.firstOrNull { it.id == lastPaymentMethod }?.id }
            ?: previousSelectedId?.let { availablePaymentMethods.firstOrNull { it.id == previousSelectedId }?.id }
            ?: let {
                val paymentMethodsThatCanBePreselected =
                    if (availablePaymentMethods.size == 1) availablePaymentMethods
                    else availablePaymentMethods.filter { it !is PaymentMethod.UndefinedBankAccount }

                paymentMethodsThatCanBePreselected.firstOrNull { it.isEligible && it.canBeUsedForPaying() }?.id
                    ?: paymentMethodsThatCanBePreselected.firstOrNull { it.isEligible }?.id
                    ?: paymentMethodsThatCanBePreselected.firstOrNull()?.id
            }

    private fun handleOrderAttrs(order: BuySellOrder) {
        when {
            order.attributes?.isCardPayment == true -> handleCardPayment(order)
            !order.source.currency.isOpenBankingCurrency() -> process(SimpleBuyIntent.CheckOrderStatus)
            order.attributes?.authorisationUrl != null -> handleBankAuthorisationPayment(
                paymentMethodId = order.paymentMethodId,
                authorisationUrl = order.attributes?.authorisationUrl!!
            )
            else -> process(SimpleBuyIntent.GetAuthorisationUrl(order.id))
        }
    }

    private fun processConfirmOrderAndCreateRecurringBuy(
        orderId: String,
        googlePayPayload: String?,
        googlePayBeneficiaryId: String?,
        googlePayAddress: GooglePayAddress?,
        asset: AssetInfo?,
        order: SimpleBuyOrder,
        selectedPaymentMethod: SelectedPaymentMethod?,
        recurringBuyFrequency: RecurringBuyFrequency
    ): Disposable {
        return Singles.zip(
            confirmOrder(
                id = orderId,
                selectedPaymentMethod = selectedPaymentMethod,
                googlePayPayload = googlePayPayload,
                googlePayBeneficiaryId = googlePayBeneficiaryId,
                googlePayAddress = googlePayAddress,
            ),
            createRecurringBuy(
                selectedCryptoAsset = asset,
                order = order,
                selectedPaymentMethod = selectedPaymentMethod,
                recurringBuyFrequency = recurringBuyFrequency
            ).trackProgress(activityIndicator)
                .doOnTerminate { buyOrdersCache.invalidate() }
        ).subscribeBy(
            onSuccess =
            {
                process(
                    SimpleBuyIntent.RecurringBuyCreated(
                        recurringBuyId = it.second.id.orEmpty(),
                        recurringBuyFrequency = recurringBuyFrequency
                    )
                )
                triggerIntentsAfterOrderConfirmed(it.first, true)
            },
            onError =
            {
                processOrderErrors(it)
            }
        )
    }

    private fun createRecurringBuy(
        selectedCryptoAsset: AssetInfo?,
        order: SimpleBuyOrder,
        selectedPaymentMethod: SelectedPaymentMethod?,
        recurringBuyFrequency: RecurringBuyFrequency,
    ): Single<RecurringBuyOrder> =
        interactor.createRecurringBuyOrder(
            selectedCryptoAsset,
            order,
            selectedPaymentMethod,
            recurringBuyFrequency
        )
            .onErrorReturn {
                RecurringBuyOrder(RecurringBuyState.INACTIVE)
            }

    private fun confirmOrder(
        id: String?,
        selectedPaymentMethod: SelectedPaymentMethod?,
        googlePayPayload: String?,
        googlePayBeneficiaryId: String?,
        googlePayAddress: GooglePayAddress?,
    ): Single<BuySellOrder> {
        require(id != null) { "Order Id not available" }
        require(selectedPaymentMethod != null) { "selectedPaymentMethod missing" }
        return cardPaymentAsyncFF.enabled.flatMap { cardPaymentAsync ->
            interactor.confirmOrder(
                orderId = id,
                paymentMethodId = googlePayBeneficiaryId ?: selectedPaymentMethod.takeIf { it.isBank() }?.concreteId(),
                attributes = if (selectedPaymentMethod.isBank()) {
                    // redirectURL is for card providers only.
                    SimpleBuyConfirmationAttributes(
                        callback = bankPartnerCallbackProvider.callback(BankPartner.YAPILY, BankTransferAction.PAY),
                        redirectURL = null
                    )
                } else {
                    googlePayPayload?.let { payload ->
                        cardActivator.paymentAttributes().copy(
                            disable3DS = false,
                            isMitPayment = false,
                            isAsync = cardPaymentAsync,
                            googlePayPayload = payload,
                            paymentContact = googlePayAddress?.let {
                                PaymentContact(
                                    line1 = it.address1,
                                    line2 = it.address2,
                                    city = it.locality,
                                    state = it.administrativeArea,
                                    country = it.countryCode,
                                    postCode = it.postalCode,
                                    firstname = it.name,
                                    lastname = it.name
                                )
                            }
                        )
                    } ?: run {
                        cardActivator.paymentAttributes().copy(isAsync = cardPaymentAsync)
                    }
                },
                isBankPartner = selectedPaymentMethod.isBank()
            )
        }
    }

    private fun processConfirmOrder(
        id: String?,
        selectedPaymentMethod: SelectedPaymentMethod?,
        googlePayPayload: String? = null,
        googlePayBeneficiaryId: String? = null,
        googlePayAddress: GooglePayAddress? = null,
        amount: FiatValue,
        pair: String
    ): Disposable {

        return confirmOrder(id, selectedPaymentMethod, googlePayPayload, googlePayBeneficiaryId, googlePayAddress)
            .map { it }
            .trackProgress(activityIndicator)
            .doOnTerminate { buyOrdersCache.invalidate() }
            .subscribeBy(
                onSuccess = { buySellOrder ->
                    triggerIntentsAfterOrderConfirmed(buySellOrder)
                },
                onError = {
                    interactor.saveOrderAmountAndPaymentMethodId(
                        pair = pair,
                        amount = amount.toStringWithoutSymbol(),
                        paymentId = selectedPaymentMethod?.id ?: googlePayBeneficiaryId!!
                    )
                    processOrderErrors(it)
                }
            )
    }

    private fun triggerIntentsAfterOrderConfirmed(buySellOrder: BuySellOrder, isRbEnabled: Boolean = false) {
        val orderCreatedSuccessfully = buySellOrder.state == OrderState.FINISHED

        if (orderCreatedSuccessfully) {
            updatePersistingCountersForCompletedOrders(
                pair = buySellOrder.pair,
                amount = buySellOrder.source.toStringWithoutSymbol(),
                paymentMethodId = buySellOrder.paymentMethodId
            )
        }
        process(SimpleBuyIntent.StopQuotesUpdate(true))

        process(SimpleBuyIntent.OrderConfirmed(buyOrder = buySellOrder, isRbActive = isRbEnabled))
    }

    private fun processOrderErrors(it: Throwable) {
        if (it is NabuApiException) {
            it.getServerSideErrorInfo()?.let { serverError ->
                process(SimpleBuyIntent.ErrorIntent(ErrorState.ServerSideUxError(serverError)))
            } ?: run {
                when (it.getErrorCode()) {
                    NabuErrorCodes.DailyLimitExceeded -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.DailyLimitExceeded)
                    )
                    NabuErrorCodes.WeeklyLimitExceeded -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.WeeklyLimitExceeded)
                    )
                    NabuErrorCodes.AnnualLimitExceeded -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.YearlyLimitExceeded)
                    )
                    NabuErrorCodes.PendingOrdersLimitReached -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.ExistingPendingOrder)
                    )
                    NabuErrorCodes.InsufficientCardFunds -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.InsufficientCardFunds)
                    )
                    NabuErrorCodes.CardBankDeclined -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardBankDeclined)
                    )
                    NabuErrorCodes.CardDuplicate -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardDuplicated)
                    )
                    NabuErrorCodes.CardBlockchainDecline -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardBlockchainDeclined)
                    )
                    NabuErrorCodes.CardAcquirerDecline -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardAcquirerDeclined)
                    )
                    NabuErrorCodes.CardPaymentNotSupported -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentNotSupported)
                    )
                    NabuErrorCodes.CardCreateFailed -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateFailed)
                    )
                    NabuErrorCodes.CardPaymentFailed -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentFailed)
                    )
                    NabuErrorCodes.CardCreateAbandoned -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateAbandoned)
                    )
                    NabuErrorCodes.CardCreateExpired -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateExpired)
                    )
                    NabuErrorCodes.CardCreateBankDeclined -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateBankDeclined)
                    )
                    NabuErrorCodes.CardCreateDebitOnly -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardCreateDebitOnly)
                    )
                    NabuErrorCodes.CardPaymentDebitOnly -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardPaymentDebitOnly)
                    )
                    NabuErrorCodes.CardCreateNoToken -> process(
                        SimpleBuyIntent.ErrorIntent(ErrorState.CardNoToken)
                    )
                    else -> process(SimpleBuyIntent.ErrorIntent(ErrorState.UnhandledHttpError(it)))
                }
            }
        } else {
            val error = when {
                it is HttpException -> {
                    val error = NabuApiExceptionFactory.fromResponseBody(it)
                    error.getServerSideErrorInfo()?.let { serverError ->
                        ErrorState.ServerSideUxError(serverError)
                    } ?: ErrorState.UnhandledHttpError(error)
                }
                it.isInternetConnectionError() -> ErrorState.InternetConnectionError
                else -> throw it
            }
            process(SimpleBuyIntent.ErrorIntent(error))
        }
    }

    private fun updatePersistingCountersForCompletedOrders(pair: String, amount: String, paymentMethodId: String) {
        interactor.updateCountersForCompletedOrders()
        interactor.saveOrderAmountAndPaymentMethodId(pair, amount, paymentMethodId)
    }

    private fun pollForOrderStatus() {
        process(SimpleBuyIntent.CheckOrderStatus)
    }

    private fun handleCardPayment(order: BuySellOrder) {
        order.attributes?.cardAttributes?.let { paymentAttributes ->
            val intent = when (paymentAttributes) {
                is CardAttributes.Provider -> {
                    createIntentForCardProvider(paymentAttributes, order.state)
                }
                is CardAttributes.EveryPay -> {
                    if (paymentAttributes.paymentState == CardPaymentState.WAITING_FOR_3DS &&
                        order.state == OrderState.AWAITING_FUNDS
                    ) {
                        SimpleBuyIntent.Open3dsAuth(
                            CardAcquirerCredentials.Everypay(
                                paymentAttributes.paymentLink,
                                cardActivator.redirectUrl
                            )
                        )
                    } else {
                        SimpleBuyIntent.CheckOrderStatus
                    }
                }
                is CardAttributes.Empty -> SimpleBuyIntent.ErrorIntent(
                    ErrorState.ProviderIsNotSupported
                )
            }
            process(intent)
        }
    }

    private fun createIntentForCardProvider(
        paymentAttributes: CardAttributes.Provider,
        orderState: OrderState,
    ): SimpleBuyIntent {
        return if (orderState == OrderState.AWAITING_FUNDS) {
            when (CardAcquirer.fromString(paymentAttributes.cardAcquirerName)) {
                CardAcquirer.CHECKOUTDOTCOM -> SimpleBuyIntent.Open3dsAuth(
                    CardAcquirerCredentials.Checkout(
                        apiKey = paymentAttributes.publishableApiKey,
                        paymentLink = paymentAttributes.paymentLink,
                        exitLink = cardActivator.redirectUrl
                    )
                )
                CardAcquirer.EVERYPAY -> {
                    if (paymentAttributes.paymentState == CardPaymentState.WAITING_FOR_3DS) {
                        SimpleBuyIntent.Open3dsAuth(
                            CardAcquirerCredentials.Everypay(
                                paymentLink = paymentAttributes.paymentLink,
                                exitLink = cardActivator.redirectUrl
                            )
                        )
                    } else {
                        SimpleBuyIntent.CheckOrderStatus
                    }
                }
                CardAcquirer.STRIPE -> SimpleBuyIntent.Open3dsAuth(
                    CardAcquirerCredentials.Stripe(
                        apiKey = paymentAttributes.publishableApiKey,
                        clientSecret = paymentAttributes.clientSecret
                    )
                )
                CardAcquirer.UNKNOWN -> SimpleBuyIntent.ErrorIntent(ErrorState.UnknownCardProvider)
            }
        } else {
            SimpleBuyIntent.CheckOrderStatus
        }
    }

    private fun handleBankAuthorisationPayment(
        paymentMethodId: String,
        authorisationUrl: String,
    ) {
        disposables += interactor.getLinkedBankInfo(paymentMethodId).subscribeBy(
            onSuccess = { linkedBank ->
                process(SimpleBuyIntent.AuthorisePaymentExternalUrl(authorisationUrl, linkedBank))
            },
            onError = {
                processOrderErrors(it)
            }
        )
    }

    private fun requestGooglePayInfo(
        currency: FiatCurrency?,
    ): Single<SimpleBuyIntent.GooglePayInfoReceived> {
        require(currency != null) { "Missing Currency" }
        return interactor.getGooglePayInfo(currency)
    }

    override fun onStateUpdate(s: SimpleBuyState) {
        serializer.update(s)
    }

    private fun SimpleBuyInteractor.PaymentMethods.toPaymentMethods(
        fiatCurrency: FiatCurrency,
    ): List<PaymentMethod> {
        val availableMap = available.associateBy { it.type }
        val limitsMap = available.associate { it.type to it.limits }

        val availableForBuyLinkedMethods = linked
            .filter { availableMap[it.type]?.canBeUsedForPayment == true }
            .filter {
                when (it) {
                    is LinkedPaymentMethod.Card -> it.status == CardStatus.ACTIVE
                    is LinkedPaymentMethod.Funds -> it.currency == fiatCurrency
                    is LinkedPaymentMethod.Bank -> it.isBankTransferAccount && it.state == BankState.ACTIVE
                }
            }
            .filterNot { it.type == PaymentMethodType.FUNDS && it.currency != fiatCurrency }
            .mapNotNull {
                val limits = limitsMap[it.type] ?: return@mapNotNull null

                when (it) {
                    is LinkedPaymentMethod.Card -> PaymentMethod.Card(
                        cardId = it.cardId,
                        limits = limits,
                        label = it.label,
                        endDigits = it.endDigits,
                        partner = it.partner,
                        expireDate = it.expireDate,
                        cardType = it.cardType,
                        status = it.status,
                        isEligible = true,
                        cardRejectionState = it.cardRejectionState
                    )
                    is LinkedPaymentMethod.Funds -> it.balance.takeIf { balance ->
                        balance > limits.min
                    }?.let { balance ->
                        val fundsLimits = limits.copy(max = Money.min(limits.max, balance))
                        PaymentMethod.Funds(
                            balance,
                            fiatCurrency,
                            fundsLimits,
                            true
                        )
                    }
                    is LinkedPaymentMethod.Bank -> PaymentMethod.Bank(
                        bankId = it.id,
                        limits = limits,
                        bankName = it.name,
                        accountEnding = it.accountEnding,
                        accountType = it.accountType,
                        iconUrl = it.iconUrl,
                        isEligible = true
                    )
                }
            }

        val canBeLinkedMethods = available
            .filter { it.linkAccess != LinkAccess.BLOCKED }
            .mapNotNull { method ->
                when (method.type) {
                    PaymentMethodType.PAYMENT_CARD ->
                        PaymentMethod.UndefinedCard(
                            method.limits,
                            method.canBeUsedForPayment,
                            PaymentMethod.UndefinedCard.mapCardFundSources(
                                availableMap[PaymentMethodType.PAYMENT_CARD]?.cardFundSources
                            )
                        )
                    PaymentMethodType.GOOGLE_PAY ->
                        PaymentMethod.GooglePay(method.limits, method.canBeUsedForPayment)
                    PaymentMethodType.BANK_TRANSFER ->
                        PaymentMethod.UndefinedBankTransfer(method.limits, method.canBeUsedForPayment)
                    PaymentMethodType.BANK_ACCOUNT ->
                        if (method.canBeUsedForPayment && method.currency == fiatCurrency) {
                            PaymentMethod.UndefinedBankAccount(
                                method.currency,
                                method.limits,
                                method.canBeUsedForPayment
                            )
                        } else null
                    PaymentMethodType.FUNDS,
                    PaymentMethodType.UNKNOWN,
                    -> null
                }
            }

        val availablePaymentMethods = (availableForBuyLinkedMethods + canBeLinkedMethods)

        return availablePaymentMethods.sortedBy { paymentMethod -> paymentMethod.order }.toList()
    }
}

private fun SimpleBuyState.withSelectedFiatCurrency(
    fiatCurrenciesService: FiatCurrenciesService
): SimpleBuyState = try {
    // Try catch is used for testing, because fiatCurrenciesService.selectedTradingCurrency would throw
    // Uninitialized on KoinGraph test
    val selectedFiatCurrency = fiatCurrenciesService.selectedTradingCurrency
    (selectedFiatCurrency as? FiatCurrency)?.let {
        this.copy(
            fiatCurrency = selectedFiatCurrency,
            amount = Money.zero(selectedFiatCurrency) as FiatValue
        )
    } ?: this
} catch (ex: Exception) {
    this
}
