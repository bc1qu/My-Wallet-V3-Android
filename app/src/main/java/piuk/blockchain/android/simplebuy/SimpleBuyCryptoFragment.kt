package piuk.blockchain.android.simplebuy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import com.blockchain.analytics.events.LaunchOrigin
import com.blockchain.coincore.AssetAction
import com.blockchain.commonarch.presentation.mvi.MviFragment
import com.blockchain.componentlib.basic.ImageResource
import com.blockchain.componentlib.button.ButtonState
import com.blockchain.componentlib.button.SmallMinimalButton
import com.blockchain.componentlib.switcher.SwitcherState
import com.blockchain.componentlib.tag.TagType
import com.blockchain.componentlib.tag.TagViewState
import com.blockchain.componentlib.theme.AppTheme
import com.blockchain.componentlib.viewextensions.gone
import com.blockchain.componentlib.viewextensions.visible
import com.blockchain.core.limits.TxLimit
import com.blockchain.core.payments.toCardType
import com.blockchain.deeplinking.processor.DeeplinkProcessorV2.Companion.DIFFERENT_PAYMENT_URL
import com.blockchain.domain.common.model.ServerErrorAction
import com.blockchain.domain.common.model.ServerSideUxErrorInfo
import com.blockchain.domain.eligibility.model.TransactionsLimit
import com.blockchain.domain.fiatcurrencies.FiatCurrenciesService
import com.blockchain.domain.paymentmethods.model.CardRejectionState
import com.blockchain.domain.paymentmethods.model.PaymentMethod
import com.blockchain.domain.paymentmethods.model.PaymentMethod.UndefinedCard.CardFundSource
import com.blockchain.domain.paymentmethods.model.PaymentMethodType
import com.blockchain.domain.paymentmethods.model.UndefinedPaymentMethod
import com.blockchain.extensions.exhaustive
import com.blockchain.featureflag.FeatureFlag
import com.blockchain.koin.cardRejectionCheckFeatureFlag
import com.blockchain.koin.scopedInject
import com.blockchain.nabu.datamanagers.OrderState
import com.blockchain.nabu.models.data.RecurringBuyFrequency
import com.blockchain.utils.capitalizeFirstChar
import com.blockchain.utils.isLastDayOfTheMonth
import com.blockchain.utils.to12HourFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import info.blockchain.balance.AssetCatalogue
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.FiatCurrency
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.math.BigDecimal
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import org.koin.android.ext.android.inject
import piuk.blockchain.android.R
import piuk.blockchain.android.campaign.CampaignType
import piuk.blockchain.android.cards.CardDetailsActivity
import piuk.blockchain.android.cards.CardDetailsActivity.Companion.ADD_CARD_REQUEST_CODE
import piuk.blockchain.android.cards.icon
import piuk.blockchain.android.databinding.FragmentSimpleBuyBuyCryptoBinding
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.ACTION_BUY
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.INSUFFICIENT_FUNDS
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.INTERNET_CONNECTION_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.NABU_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.OVER_MAXIMUM_SOURCE_LIMIT
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.PENDING_ORDERS_LIMIT_REACHED
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_GENERIC_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_INSUFFICIENT_BALANCE
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_STALE_BALANCE
import piuk.blockchain.android.simplebuy.paymentmethods.PaymentMethodChooserBottomSheet
import piuk.blockchain.android.ui.customviews.inputview.FiatCryptoViewConfiguration
import piuk.blockchain.android.ui.customviews.inputview.PrefixedOrSuffixedEditText
import piuk.blockchain.android.ui.dashboard.asDeltaPercent
import piuk.blockchain.android.ui.dashboard.sheets.KycUpgradeNowSheet
import piuk.blockchain.android.ui.dashboard.sheets.WireTransferAccountDetailsBottomSheet
import piuk.blockchain.android.ui.kyc.navhost.KycNavHostActivity
import piuk.blockchain.android.ui.linkbank.BankAuthActivity
import piuk.blockchain.android.ui.linkbank.BankAuthSource
import piuk.blockchain.android.ui.recurringbuy.RecurringBuyAnalytics
import piuk.blockchain.android.ui.recurringbuy.RecurringBuyAnalytics.Companion.PAYMENT_METHOD_UNAVAILABLE
import piuk.blockchain.android.ui.recurringbuy.RecurringBuyAnalytics.Companion.SELECT_PAYMENT
import piuk.blockchain.android.ui.recurringbuy.RecurringBuySelectionBottomSheet
import piuk.blockchain.android.ui.resources.AssetResources
import piuk.blockchain.android.ui.settings.SettingsAnalytics
import piuk.blockchain.android.ui.transactionflow.analytics.InfoBottomSheetDismissed
import piuk.blockchain.android.ui.transactionflow.analytics.InfoBottomSheetKycUpsellActionClicked
import piuk.blockchain.android.ui.transactionflow.engine.TransactionErrorState
import piuk.blockchain.android.ui.transactionflow.flow.TransactionFlowInfoBottomSheet
import piuk.blockchain.android.ui.transactionflow.flow.TransactionFlowInfoHost
import piuk.blockchain.android.ui.transactionflow.flow.customisations.InfoActionType
import piuk.blockchain.android.ui.transactionflow.flow.customisations.InfoBottomSheetType
import piuk.blockchain.android.ui.transactionflow.flow.customisations.TransactionFlowBottomSheetInfo
import piuk.blockchain.android.ui.transactionflow.flow.customisations.TransactionFlowInfoBottomSheetCustomiser
import piuk.blockchain.android.util.StringLocalizationUtil
import piuk.blockchain.android.util.setAssetIconColoursWithTint

class SimpleBuyCryptoFragment :
    MviFragment<SimpleBuyModel, SimpleBuyIntent, SimpleBuyState, FragmentSimpleBuyBuyCryptoBinding>(),
    RecurringBuySelectionBottomSheet.Host,
    SimpleBuyScreen,
    TransactionFlowInfoHost,
    PaymentMethodChooserBottomSheet.Host,
    KycUpgradeNowSheet.Host {

    override val model: SimpleBuyModel by scopedInject()
    private val assetResources: AssetResources by inject()
    private val assetCatalogue: AssetCatalogue by inject()
    private val fiatCurrenciesService: FiatCurrenciesService by scopedInject()
    private val cardRejectionFF: FeatureFlag by inject(cardRejectionCheckFeatureFlag)
    private val bottomSheetInfoCustomiser: TransactionFlowInfoBottomSheetCustomiser by inject()
    private var infoActionCallback: () -> Unit = {}

    private val fiatCurrency: FiatCurrency
        get() = fiatCurrenciesService.selectedTradingCurrency

    private var lastState: SimpleBuyState? = null
    private val compositeDisposable = CompositeDisposable()
    private var shouldShowPaymentMethodSheet = false

    private val asset: AssetInfo
        get() = arguments?.getString(ARG_CRYPTO_ASSET)?.let {
            assetCatalogue.assetInfoFromNetworkTicker(it)
        } ?: throw IllegalArgumentException("No cryptoCurrency specified")

    private val preselectedMethodId: String?
        get() = arguments?.getString(ARG_PAYMENT_METHOD_ID)

    private val preselectedAmount: FiatValue?
        get() = arguments?.getString(ARG_AMOUNT)?.let { amount ->
            FiatValue.fromMajor(fiatCurrency, BigDecimal(amount))
        }

    private val launchLinkCard: Boolean by lazy {
        arguments?.getBoolean(ARG_LINK_NEW_CARD, false) ?: false
    }

    private val launchSelectPaymentMethod: Boolean by lazy {
        arguments?.getBoolean(ARG_LAUNCH_PAYMENT_METHOD_SELECTION, false) ?: false
    }

    override fun navigator(): SimpleBuyNavigator =
        (activity as? SimpleBuyNavigator)
            ?: throw IllegalStateException("Parent must implement SimpleBuyNavigator")

    override fun initBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSimpleBuyBuyCryptoBinding =
        FragmentSimpleBuyBuyCryptoBinding.inflate(inflater, container, false)

    override fun onResume() {
        super.onResume()
        model.process(SimpleBuyIntent.UpdateExchangeRate(fiatCurrency, asset))
        model.process(SimpleBuyIntent.FlowCurrentScreen(FlowScreen.ENTER_AMOUNT))
        model.process(SimpleBuyIntent.StopQuotesUpdate(false))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        activity.updateToolbar(
            toolbarTitle = getString(R.string.tx_title_buy, asset.displayTicker),
            backAction = { activity.onBackPressedDispatcher.onBackPressed() }
        )
        model.process(SimpleBuyIntent.InitialiseSelectedCryptoAndFiat(asset, fiatCurrency))
        model.process(
            SimpleBuyIntent.FetchSuggestedPaymentMethod(
                fiatCurrency,
                preselectedMethodId
            )
        )
        model.process(SimpleBuyIntent.FetchEligibility)
        analytics.logEvent(SimpleBuyAnalytics.BUY_FORM_SHOWN)

        compositeDisposable += binding.inputAmount.amount
            .doOnSubscribe {
                preselectedAmount?.let { amount ->
                    model.process(SimpleBuyIntent.AmountUpdated(amount))
                }
            }
            .subscribe {
                when (it) {
                    is FiatValue -> model.process(SimpleBuyIntent.AmountUpdated(it))
                    else -> throw IllegalStateException("CryptoValue is not supported as input yet")
                }
            }

        binding.btnContinue.setOnClickListener { startBuy() }

        compositeDisposable += binding.inputAmount.onImeAction.subscribe {
            if (it == PrefixedOrSuffixedEditText.ImeOptions.NEXT)
                startBuy()
        }

        if (launchLinkCard) {
            addPaymentMethod(PaymentMethodType.PAYMENT_CARD, fiatCurrency)
        }

        shouldShowPaymentMethodSheet = launchSelectPaymentMethod
    }

    override fun showAvailableToAddPaymentMethods() =
        showPaymentMethodsBottomSheet(
            paymentOptions = lastState?.paymentOptions ?: PaymentOptions(),
            state = PaymentMethodsChooserState.AVAILABLE_TO_ADD
        )

    override fun onCardTagClicked(cardInfo: CardRejectionState) {
        when (cardInfo) {
            is CardRejectionState.AlwaysRejected ->
                showCardRejectionInfo(
                    title = cardInfo.title.orEmpty(),
                    description = cardInfo.description.orEmpty(),
                    errorId = cardInfo.errorId,
                    iconUrl = cardInfo.iconUrl.orEmpty(),
                    statusIconUrl = cardInfo.statusIconUrl.orEmpty(),
                    actions = cardInfo.actions,
                    analyticsCategories = cardInfo.analyticsCategories
                )
            is CardRejectionState.MaybeRejected -> showCardRejectionInfo(
                title = cardInfo.title.orEmpty(),
                description = cardInfo.description.orEmpty(),
                errorId = cardInfo.errorId,
                iconUrl = cardInfo.iconUrl.orEmpty(),
                statusIconUrl = cardInfo.statusIconUrl.orEmpty(),
                actions = cardInfo.actions,
                analyticsCategories = cardInfo.analyticsCategories
            )
            CardRejectionState.NotRejected -> {
                // do nothing
            }
        }
    }

    private fun showCardRejectionInfo(
        title: String?,
        description: String?,
        errorId: String?,
        iconUrl: String?,
        statusIconUrl: String?,
        actions: List<ServerErrorAction>,
        analyticsCategories: List<String>
    ) {
        navigator().showErrorInBottomSheet(
            title = title.orEmpty(),
            description = description.orEmpty(),
            error = CardRejectionState.toString(),
            serverSideUxErrorInfo = ServerSideUxErrorInfo(
                id = errorId,
                title = title.orEmpty(),
                description = description.orEmpty(),
                iconUrl = iconUrl.orEmpty(),
                statusUrl = statusIconUrl.orEmpty(),
                actions = actions,
                categories = analyticsCategories
            )
        )
    }

    private fun showPaymentMethodsBottomSheet(paymentOptions: PaymentOptions, state: PaymentMethodsChooserState) {
        showBottomSheet(
            when (state) {
                PaymentMethodsChooserState.AVAILABLE_TO_PAY ->
                    PaymentMethodChooserBottomSheet.newInstance(
                        paymentMethods = paymentOptions.availablePaymentMethods
                            .filter { method ->
                                method.canBeUsedForPaying() ||
                                    method is PaymentMethod.UndefinedBankAccount
                            },
                        mode = PaymentMethodChooserBottomSheet.DisplayMode.PAYMENT_METHODS,
                        canAddNewPayment = paymentOptions.availablePaymentMethods.any { method -> method.canBeAdded() }
                    )
                PaymentMethodsChooserState.AVAILABLE_TO_ADD ->
                    PaymentMethodChooserBottomSheet.newInstance(
                        paymentMethods = paymentOptions.availablePaymentMethods
                            .filter { method ->
                                method.canBeAdded()
                            },
                        mode = PaymentMethodChooserBottomSheet.DisplayMode.PAYMENT_METHOD_TYPES,
                        canAddNewPayment = true,
                        canUseCreditCards = canUseCreditCards()
                    )
            }
        )
    }

    private fun startBuy() {
        lastState?.takeIf { canContinue(it) }?.let { state ->
            binding.inputAmount.canEdit(false)
            model.process(SimpleBuyIntent.BuyButtonClicked)
            analytics.logEvent(
                buyConfirmClicked(
                    state.amount.toBigInteger().toString(),
                    state.fiatCurrency.networkTicker,
                    state.selectedPaymentMethod?.paymentMethodType?.toAnalyticsString().orEmpty()
                )
            )

            val paymentMethodDetails = state.selectedPaymentMethodDetails
            check(paymentMethodDetails != null)

            analytics.logEvent(
                BuyAmountEntered(
                    frequency = state.recurringBuyFrequency.name,
                    inputAmount = state.amount,
                    maxCardLimit = if (paymentMethodDetails is PaymentMethod.Card) {
                        paymentMethodDetails.limits.max
                        state.amount
                    } else null,
                    outputCurrency = state.selectedCryptoAsset?.networkTicker ?: return,
                    paymentMethod = state.selectedPaymentMethod?.paymentMethodType
                        ?: return
                )
            )
        }
    }

    private fun showDialogRecurringBuyUnavailable(paymentMethodDefined: Boolean) {
        showAlert(
            AlertDialog.Builder(requireContext(), R.style.AlertDialogStyle)
                .setTitle(R.string.recurring_buy_unavailable_title)
                .setMessage(R.string.recurring_buy_unavailable_message)
                .setCancelable(false)
                .setPositiveButton(R.string.recurring_buy_cta_alert) { dialog, _ ->
                    val interval = RecurringBuyFrequency.ONE_TIME
                    model.process(SimpleBuyIntent.RecurringBuyIntervalUpdated(interval))
                    binding.recurringBuyCta.apply {
                        text = interval.toHumanReadableRecurringBuy(requireContext())
                    }
                    dialog.dismiss()
                }
                .create()
        )

        if (paymentMethodDefined) {
            analytics.logEvent(RecurringBuyAnalytics.RecurringBuyUnavailableShown(SELECT_PAYMENT))
        } else {
            analytics.logEvent(
                RecurringBuyAnalytics.RecurringBuyUnavailableShown(PAYMENT_METHOD_UNAVAILABLE)
            )
        }
    }

    private fun sendAnalyticsQuickFillButtonTapped(buttonTapped: Money, position: Int) {
        analytics.logEvent(
            QuickFillButtonTapped(
                amount = buttonTapped.toBigDecimal().toString(),
                amountType = AmountType.values()[position],
                currency = buttonTapped.currencyCode
            )
        )
    }

    private fun loadQuickFillButtons(
        quickFillButtonData: QuickFillButtonData,
    ) {
        with(binding.quickFillButtons) {
            visible()
            setContent {
                AppTheme {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LazyRow(modifier = Modifier.weight(1f)) {
                            items(
                                items = quickFillButtonData.quickFillButtons,
                                itemContent = { item ->
                                    SmallMinimalButton(
                                        text = item.toStringWithSymbol(includeDecimalsWhenWhole = false),
                                        onClick = {
                                            model.process(SimpleBuyIntent.PrefillEnterAmount(item))
                                            sendAnalyticsQuickFillButtonTapped(
                                                item,
                                                quickFillButtonData.quickFillButtons.indexOf(item)
                                            )
                                        },
                                        modifier = Modifier.padding(end = dimensionResource(R.dimen.smallest_margin))
                                    )
                                }
                            )
                        }
                        if (quickFillButtonData.buyMaxAmount.isPositive) {
                            SmallMinimalButton(
                                text = getString(R.string.buy_max),
                                onClick = {
                                    model.process(
                                        SimpleBuyIntent.PrefillEnterAmount(
                                            quickFillButtonData.buyMaxAmount as FiatValue
                                        )
                                    )
                                },
                                state = ButtonState.Enabled,
                                modifier = Modifier
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun render(newState: SimpleBuyState) {
        lastState = newState

        model.process(
            SimpleBuyIntent.SelectedPaymentChangedLimits(
                selectedPaymentMethod = newState.selectedPaymentMethod,
                limits = newState.limits
            )
        )

        newState.quickFillButtonData?.let { data ->
            loadQuickFillButtons(data)
        }

        if (newState.buyErrorState != null) {
            showErrorState(newState.buyErrorState)
            model.process(SimpleBuyIntent.ClearError)
            binding.inputAmount.canEdit(true)
            return
        }

        binding.recurringBuyCta.apply {
            text = newState.recurringBuyFrequency.toHumanReadableRecurringBuy(requireContext())
        }

        newState.selectedCryptoAsset?.let {
            binding.inputAmount.configuration = FiatCryptoViewConfiguration(
                inputCurrency = newState.fiatCurrency,
                outputCurrency = newState.fiatCurrency,
                exchangeCurrency = it,
                canSwap = false,
                predefinedAmount = newState.order.amount ?: FiatValue.zero(newState.fiatCurrency),
                showExchangeRate = false
            )
            binding.buyIcon.setAssetIconColoursWithTint(it)
        }
        newState.selectedCryptoAsset?.let {
            assetResources.loadAssetIcon(binding.cryptoIcon, it)
            binding.cryptoText.text = it.name
        }

        newState.exchangePriceWithDelta?.let {
            binding.cryptoExchangeRate.text = it.price.toStringWithSymbol()
            binding.priceDelta.asDeltaPercent(it.delta)
        }

        (newState.limits.max as? TxLimit.Limited)?.amount?.takeIf {
            it.currency == fiatCurrency
        }?.let {
            binding.inputAmount.maxLimit = it
        }

        val transactionsLimit = newState.transactionsLimit
        if (transactionsLimit is TransactionsLimit.Limited) {
            binding.inputAmount.showInfo(
                getString(R.string.tx_enter_amount_orders_limit_info, transactionsLimit.maxTransactionsLeft)
            ) {
                val info = bottomSheetInfoCustomiser.info(
                    InfoBottomSheetType.TRANSACTIONS_LIMIT, newState,
                    newState.fiatCurrency.type
                )
                if (info != null) {
                    showBottomSheet(TransactionFlowInfoBottomSheet.newInstance(info))
                    infoActionCallback = handlePossibleInfoAction(info, newState.transactionsLimit)
                }
            }
        } else {
            binding.inputAmount.hideInfo()
        }

        binding.btnContinue.isEnabled = canContinue(newState)
        updateInputStateUI(newState)

        if (newState.paymentOptions.availablePaymentMethods.isEmpty()) {
            showLoading(true)
            disableRecurringBuyCta(false)
        } else {
            showLoading(false)
            newState.selectedPaymentMethodDetails?.let { paymentMethod ->
                renderDefinedPaymentMethod(newState, paymentMethod)
            }
            if (shouldShowPaymentMethodSheet) {
                showPaymentMethodsBottomSheet(
                    paymentOptions = newState.paymentOptions,
                    state = PaymentMethodsChooserState.AVAILABLE_TO_PAY
                )
                shouldShowPaymentMethodSheet = false
            }
        }

        showCtaOrError(newState)

        if (newState.confirmationActionRequested &&
            newState.kycVerificationState != null &&
            newState.orderState == OrderState.PENDING_CONFIRMATION
        ) {
            handlePostOrderCreationAction(newState)
        }

        newState.newPaymentMethodToBeAdded?.let {
            handleNewPaymentMethodAdding(newState)
        }

        newState.linkBankTransfer?.let {
            model.process(SimpleBuyIntent.ResetLinkBankTransfer)
            startActivityForResult(
                BankAuthActivity.newInstance(
                    it, BankAuthSource.SIMPLE_BUY, requireContext()
                ),
                BankAuthActivity.LINK_BANK_REQUEST_CODE
            )
        }
    }

    private fun showCtaOrError(newState: SimpleBuyState) {
        when {
            cardRejectionFF.isEnabled && newState.selectedPaymentMethodDetails?.isCardAndAlwaysRejected() == true -> {
                (
                    (newState.selectedPaymentMethodDetails as PaymentMethod.Card).cardRejectionState
                        as CardRejectionState.AlwaysRejected
                    ).renderAlwaysRejectedCardError()
            }
            newState.errorStateShouldBeIndicated() -> showError(newState)
            else -> showCta()
        }
    }

    private fun showError(state: SimpleBuyState) {
        with(binding) {
            btnContinue.gone()
            with(btnError) {
                text = state.errorState.message(state)
                visible()
            }
        }

        val infoType = when (state.errorState) {
            TransactionErrorState.INSUFFICIENT_FUNDS -> InfoBottomSheetType.INSUFFICIENT_FUNDS
            TransactionErrorState.BELOW_MIN_PAYMENT_METHOD_LIMIT,
            TransactionErrorState.BELOW_MIN_LIMIT -> InfoBottomSheetType.BELOW_MIN_LIMIT
            // we need to keep those for working with the feature flag off, otherwise we would be based only on the
            // suggested upgrade
            TransactionErrorState.OVER_GOLD_TIER_LIMIT,
            TransactionErrorState.OVER_SILVER_TIER_LIMIT -> InfoBottomSheetType.OVER_MAX_LIMIT
            TransactionErrorState.ABOVE_MAX_PAYMENT_METHOD_LIMIT -> InfoBottomSheetType.ABOVE_MAX_PAYMENT_METHOD_LIMIT
            else -> null
        }

        val bottomSheetInfo = infoType?.let { type ->
            bottomSheetInfoCustomiser.info(type, state, state.fiatCurrency.type)
        }
        bottomSheetInfo?.let { info ->
            binding.btnError.onClick = {
                showBottomSheet(TransactionFlowInfoBottomSheet.newInstance(info))
                infoActionCallback =
                    handlePossibleInfoAction(info, state.transactionsLimit ?: TransactionsLimit.Unlimited)
            }
        } ?: run { binding.btnError.onClick = {} }

        model.process(SimpleBuyIntent.ClearError)
        binding.inputAmount.canEdit(true)
    }

    private fun handlePossibleInfoAction(
        info: TransactionFlowBottomSheetInfo,
        transactionsLimit: TransactionsLimit,
    ): () -> Unit {
        val type = info.action?.actionType ?: return {}
        return when (type) {
            InfoActionType.KYC_UPGRADE -> return {
                analytics.logEvent(InfoBottomSheetKycUpsellActionClicked(AssetAction.Buy))
                showBottomSheet(
                    KycUpgradeNowSheet.newInstance(transactionsLimit)
                )
            }
            InfoActionType.BUY -> {
                {}
            }
        }
    }

    private fun showCta() {
        with(binding) {
            btnContinue.visible()
            btnError.gone()
        }
    }

    private fun handleNewPaymentMethodAdding(state: SimpleBuyState) {
        require(state.newPaymentMethodToBeAdded is UndefinedPaymentMethod)
        addPaymentMethod(state.newPaymentMethodToBeAdded.type, state.fiatCurrency)
        model.process(SimpleBuyIntent.AddNewPaymentMethodHandled)
        model.process(SimpleBuyIntent.SelectedPaymentMethodUpdate(state.newPaymentMethodToBeAdded))
    }

    private fun updateInputStateUI(newState: SimpleBuyState) {
        binding.inputAmount.onAmountValidationUpdated(
            isValid = !newState.errorStateShouldBeIndicated()
        )
    }

    private fun handlePostOrderCreationAction(newState: SimpleBuyState) {
        when {
            newState.selectedPaymentMethod?.isActive() == true -> {
                navigator().goToCheckOutScreen()
            }
            newState.selectedPaymentMethod?.paymentMethodType == PaymentMethodType.GOOGLE_PAY &&
                newState.kycVerificationState == KycState.VERIFIED_AND_ELIGIBLE -> {
                // We need to ensure that only verified and eligible users can use Google Pay
                navigator().goToCheckOutScreen()
            }
            newState.selectedPaymentMethod?.isEligible == true -> {
                addPaymentMethod(newState.selectedPaymentMethod.paymentMethodType, newState.fiatCurrency)
            }
            else -> {
                require(newState.kycVerificationState != null)
                require(newState.kycVerificationState != KycState.VERIFIED_AND_ELIGIBLE)
                when (newState.kycVerificationState) {
                    // Kyc state unknown because error, or gold docs unsubmitted
                    KycState.PENDING -> {
                        startKyc()
                    }
                    // Awaiting results state
                    KycState.IN_REVIEW,
                    KycState.UNDECIDED,
                    -> {
                        navigator().goToKycVerificationScreen()
                    }
                    // Got results, kyc verification screen will show error
                    KycState.VERIFIED_BUT_NOT_ELIGIBLE,
                    KycState.FAILED,
                    -> {
                        navigator().goToKycVerificationScreen()
                    }
                    KycState.VERIFIED_AND_ELIGIBLE -> throw IllegalStateException(
                        "Payment method should be active or eligible"
                    )
                }.exhaustive
            }
        }
    }

    override fun startKycClicked() {
        startKyc()
    }

    /**
     * Once User selects the option to Link a bank then his/her Kyc status is checked.
     * If is VERIFIED_AND_ELIGIBLE then we try to link a bank and if the fetched partner is supported
     * then the LinkBankActivity is launched.
     * In case that user is not VERIFIED_AND_ELIGIBLE then we just select the payment method and when
     * user presses Continue the KYC flow is launched
     */

    private fun startKyc() {
        model.process(SimpleBuyIntent.NavigationHandled)
        model.process(SimpleBuyIntent.KycStarted)
        analytics.logEvent(SimpleBuyAnalytics.START_GOLD_FLOW)
        KycNavHostActivity.startForResult(this, CampaignType.SimpleBuy, SimpleBuyActivity.KYC_STARTED)
    }

    private fun canContinue(state: SimpleBuyState): Boolean =
        if (state.amount.isZero) false else {
            state.errorState == TransactionErrorState.NONE &&
                state.selectedPaymentMethod != null &&
                !state.isLoading
        }

    private fun renderDefinedPaymentMethod(
        state: SimpleBuyState,
        selectedPaymentMethod: PaymentMethod
    ) {
        renderRecurringBuy(state)

        with(binding) {
            paymentMethod.visible()
            paymentMethodSeparator.visible()
            paymentMethodDetailsRoot.apply {
                visible()
                onClick = {
                    showPaymentMethodsBottomSheet(
                        state = state.paymentOptions.availablePaymentMethods.toPaymentMethodChooserState(),
                        paymentOptions = state.paymentOptions
                    )
                }
            }
        }

        when (selectedPaymentMethod) {
            is PaymentMethod.Card -> renderCardPayment(selectedPaymentMethod)
            is PaymentMethod.Funds -> renderFundsPayment(selectedPaymentMethod)
            is PaymentMethod.Bank -> renderBankPayment(selectedPaymentMethod)
            is PaymentMethod.UndefinedCard -> renderUndefinedCardPayment(selectedPaymentMethod)
            is PaymentMethod.UndefinedBankTransfer -> renderUndefinedBankTransfer(selectedPaymentMethod)
            is PaymentMethod.UndefinedBankAccount -> renderUndefinedBankAccount(selectedPaymentMethod)
            is PaymentMethod.GooglePay -> renderGooglePayPayment(selectedPaymentMethod)
            else -> {
                // Nothing to do here.
            }
        }

        if (cardRejectionFF.isEnabled && selectedPaymentMethod !is PaymentMethod.Card) {
            with(binding) {
                btnError.gone()
                btnContinue.visible()
                paymentMethodDetailsRoot.tags = emptyList()
            }
        }
    }

    private fun List<PaymentMethod>.toPaymentMethodChooserState(): PaymentMethodsChooserState {
        with(filter { it.canBeUsedForPaying() }) {
            return if (
                this.isEmpty() ||
                // Show AVAILABLE_TO_ADD if GooglePay is the only method that can be used for paying
                (this.size == 1 && this.singleOrNull { it is PaymentMethod.GooglePay } != null)
            ) {
                PaymentMethodsChooserState.AVAILABLE_TO_ADD
            } else {
                PaymentMethodsChooserState.AVAILABLE_TO_PAY
            }
        }
    }

    private fun renderRecurringBuy(state: SimpleBuyState) {
        val paymentMethodIsEligibleForSelectedFreq =
            state.isSelectedPaymentMethodEligibleForSelectedFrequency() ||
                state.recurringBuyFrequency == RecurringBuyFrequency.ONE_TIME

        if (!paymentMethodIsEligibleForSelectedFreq) {
            model.process(SimpleBuyIntent.RecurringBuyIntervalUpdated(RecurringBuyFrequency.ONE_TIME))
        }

        if (state.isSelectedPaymentMethodRecurringBuyEligible()) {
            enableRecurringBuyCta()
        } else {
            disableRecurringBuyCta(state.selectedPaymentMethodDetails?.canBeUsedForPaying() ?: false)
        }
    }

    private fun enableRecurringBuyCta() {
        binding.recurringBuyCta.apply {
            switcherState = SwitcherState.Enabled
            onClick = {
                showBottomSheet(RecurringBuySelectionBottomSheet.newInstance())
            }
        }
    }

    private fun disableRecurringBuyCta(paymentMethodDefined: Boolean) {
        binding.recurringBuyCta.apply {
            switcherState = SwitcherState.Disabled
            onClick = {
                showDialogRecurringBuyUnavailable(paymentMethodDefined)
            }
        }
    }

    private fun renderFundsPayment(paymentMethod: PaymentMethod.Funds) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = paymentMethod.fiatCurrency.name
            secondaryText = paymentMethod.limits.max.toStringWithSymbol()
            startImageResource = if (paymentMethod.fiatCurrency.logo.isNotEmpty()) {
                ImageResource.Remote(paymentMethod.fiatCurrency.logo)
            } else {
                ImageResource.Local(R.drawable.ic_default_asset_logo)
            }
        }
    }

    private fun renderBankPayment(paymentMethod: PaymentMethod.Bank) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = paymentMethod.bankName
            secondaryText = getString(R.string.payment_method_limit, paymentMethod.limits.max.toStringWithSymbol())
            startImageResource = if (paymentMethod.iconUrl.isNotEmpty()) {
                ImageResource.Remote(paymentMethod.iconUrl)
            } else {
                ImageResource.Local((R.drawable.ic_bank_icon))
            }
        }
    }

    private fun renderUndefinedCardPayment(selectedPaymentMethod: PaymentMethod.UndefinedCard) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = if (canUseCreditCards()) {
                getString(R.string.credit_or_debit_card)
            } else {
                getString(R.string.add_debit_card)
            }
            secondaryText =
                getString(R.string.payment_method_limit, selectedPaymentMethod.limits.max.toStringWithSymbol())
            startImageResource =
                ImageResource.Local(contentDescription = "UndefinedCard", id = R.drawable.ic_payment_card)
        }
    }

    private fun renderUndefinedBankTransfer(selectedPaymentMethod: PaymentMethod.UndefinedBankTransfer) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = getString(R.string.easy_bank_transfer)
            secondaryText =
                getString(R.string.payment_method_limit, selectedPaymentMethod.limits.max.toStringWithSymbol())
            startImageResource = ImageResource.Local(
                contentDescription = "UnderfinedBankTransfer",
                id = R.drawable.ic_bank_icon
            )
        }
    }

    private fun renderUndefinedBankAccount(selectedPaymentMethod: PaymentMethod.UndefinedBankAccount) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = getString(
                StringLocalizationUtil.getBankDepositTitle(selectedPaymentMethod.fiatCurrency.networkTicker)
            )
            secondaryText =
                getString(R.string.payment_method_limit, selectedPaymentMethod.limits.max.toStringWithSymbol())
            startImageResource = ImageResource.Local(
                contentDescription = "UnderfinedBankTransfer",
                id = R.drawable.ic_bank_icon
            )
        }
    }

    private fun renderCardPayment(selectedPaymentMethod: PaymentMethod.Card) {
        with(binding) {
            paymentMethodDetailsRoot.apply {
                primaryText = selectedPaymentMethod.dottedEndDigits()
                secondaryText =
                    getString(R.string.payment_method_limit, selectedPaymentMethod.limits.max.toStringWithSymbol())
                startImageResource = ImageResource.Local(
                    selectedPaymentMethod.cardType.toCardType().icon()
                )
                if (cardRejectionFF.isEnabled) {
                    when (val cardState = selectedPaymentMethod.cardRejectionState) {
                        is CardRejectionState.AlwaysRejected -> {
                            cardState.renderAlwaysRejectedCardError()
                            btnContinue.gone()
                            tags = emptyList()
                        }
                        is CardRejectionState.MaybeRejected -> {
                            btnError.gone()
                            btnContinue.visible()
                            tags = listOf(
                                TagViewState(
                                    cardState.title ?: getString(R.string.card_issuer_sometimes_rejects_title),
                                    TagType.Warning()
                                )
                            )
                        }
                        else -> {
                            tags = emptyList()
                            btnError.gone()
                            btnContinue.visible()
                        }
                    }
                }
            }
        }
    }

    private fun renderGooglePayPayment(selectedPaymentMethod: PaymentMethod.GooglePay) {
        binding.paymentMethodDetailsRoot.apply {
            primaryText = selectedPaymentMethod.detailedLabel()
            startImageResource = ImageResource.Local(
                contentDescription = "googlePayIcon",
                id = R.drawable.google_pay_mark
            )
        }
        disableRecurringBuyCta(false)
    }

    private fun PaymentMethod?.isCardAndAlwaysRejected(): Boolean =
        (this as? PaymentMethod.Card)?.cardRejectionState is CardRejectionState.AlwaysRejected

    private fun CardRejectionState.AlwaysRejected.renderAlwaysRejectedCardError() =
        binding.btnError.apply {
            visible()
            text = title ?: getString(R.string.card_issuer_always_rejects_title)
            onClick = {
                val tryAnotherCardActionTitle = if (actions.isNotEmpty() &&
                    actions[0].deeplinkPath.isNotEmpty()
                ) {
                    actions[0].title
                } else {
                    getString(R.string.common_ok)
                }
                val learnMoreActionTitle = if (actions.isNotEmpty() &&
                    actions.size == 2 &&
                    actions[1].deeplinkPath.isNotEmpty()
                ) {
                    actions[1].title
                } else {
                    getString(R.string.common_ok)
                }

                val sheetTitle = title ?: getString(R.string.card_issuer_always_rejects_title)
                val sheetSubtitle = description ?: getString(
                    R.string.card_issuer_always_rejects_desc
                )

                navigator().showErrorInBottomSheet(
                    title = sheetTitle,
                    description = sheetSubtitle,
                    error = errorId.orEmpty(),
                    serverSideUxErrorInfo = ServerSideUxErrorInfo(
                        id = errorId,
                        title = sheetTitle,
                        description = sheetSubtitle,
                        iconUrl = iconUrl.orEmpty(),
                        statusUrl = statusIconUrl.orEmpty(),
                        actions = listOf(
                            ServerErrorAction(
                                title = tryAnotherCardActionTitle,
                                deeplinkPath = actions[0].deeplinkPath
                            ),
                            ServerErrorAction(
                                title = learnMoreActionTitle,
                                deeplinkPath = actions[1].deeplinkPath
                            )
                        ),
                        categories = analyticsCategories
                    )
                )
            }
        }

    private fun showLoading(isVisible: Boolean) {
        with(binding) {
            if (isVisible) {
                paymentMethodDetailsRoot.gone()
                shimmer.visible()
            } else {
                shimmer.gone()
                paymentMethodDetailsRoot.visible()
            }
        }
    }

    private fun showErrorState(errorState: ErrorState) {
        when (errorState) {
            ErrorState.DailyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_daily_limit_title),
                    description = getString(R.string.sb_checkout_daily_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.WeeklyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_weekly_limit_title),
                    description = getString(R.string.sb_checkout_weekly_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.YearlyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_yearly_limit_title),
                    description = getString(R.string.sb_checkout_yearly_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.ExistingPendingOrder ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_pending_order_title),
                    description = getString(R.string.sb_checkout_pending_order_blurb),
                    error = PENDING_ORDERS_LIMIT_REACHED
                )
            ErrorState.InsufficientCardFunds ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardInsufficientFunds),
                    description = getString(R.string.msg_cardInsufficientFunds),
                    error = INSUFFICIENT_FUNDS
                )
            ErrorState.CardBankDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardBankDecline),
                    description = getString(R.string.msg_cardBankDecline),
                    error = errorState.toString()
                )
            ErrorState.CardDuplicated ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardDuplicate),
                    description = getString(R.string.msg_cardDuplicate),
                    error = errorState.toString()
                )
            ErrorState.CardBlockchainDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardBlockchainDecline),
                    description = getString(R.string.msg_cardBlockchainDecline),
                    error = errorState.toString()
                )
            ErrorState.CardAcquirerDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardAcquirerDecline),
                    description = getString(R.string.msg_cardAcquirerDecline),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentNotSupported ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentNotSupported),
                    description = getString(R.string.msg_cardPaymentNotSupported),
                    error = errorState.toString()
                )
            ErrorState.CardCreateFailed ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateFailed),
                    description = getString(R.string.msg_cardCreateFailed),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentFailed ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentFailed),
                    description = getString(R.string.msg_cardPaymentFailed),
                    error = errorState.toString()
                )
            ErrorState.CardCreateAbandoned ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateAbandoned),
                    description = getString(R.string.msg_cardCreateAbandoned),
                    error = errorState.toString()
                )
            ErrorState.CardCreateExpired ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateExpired),
                    description = getString(R.string.msg_cardCreateExpired),
                    error = errorState.toString()
                )
            ErrorState.CardCreateBankDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateBankDeclined),
                    description = getString(R.string.msg_cardCreateBankDeclined),
                    error = errorState.toString()
                )
            ErrorState.CardCreateDebitOnly ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateDebitOnly),
                    description = getString(R.string.msg_cardCreateDebitOnly),
                    serverSideUxErrorInfo = ServerSideUxErrorInfo(
                        id = null,
                        title = getString(R.string.title_cardCreateDebitOnly),
                        description = getString(R.string.msg_cardCreateDebitOnly),
                        iconUrl = getString(R.string.empty),
                        statusUrl = getString(R.string.empty),
                        actions = listOf(
                            ServerErrorAction(
                                getString(R.string.sb_checkout_card_debit_only_cta), DIFFERENT_PAYMENT_URL
                            )
                        ),
                        categories = emptyList()
                    ),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentDebitOnly ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentDebitOnly),
                    description = getString(R.string.msg_cardPaymentDebitOnly),
                    error = errorState.toString()
                )
            ErrorState.CardNoToken ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateNoToken),
                    description = getString(R.string.msg_cardCreateNoToken),
                    error = errorState.toString()
                )
            is ErrorState.BankLinkMaxAccountsReached ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.bank_linking_max_accounts_title),
                    description = getString(R.string.bank_linking_max_accounts_subtitle),
                    error = errorState.toString(),
                    nabuApiException = errorState.error
                )
            is ErrorState.BankLinkMaxAttemptsReached ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.bank_linking_max_attempts_title),
                    description = getString(R.string.bank_linking_max_attempts_subtitle),
                    error = errorState.toString(),
                    nabuApiException = errorState.error
                )
            is ErrorState.UnhandledHttpError ->
                navigator().showErrorInBottomSheet(
                    title = getString(
                        R.string.common_http_error_with_message,
                        errorState.nabuApiException.getErrorDescription()
                    ),
                    description = errorState.nabuApiException.getErrorDescription(),
                    error = NABU_ERROR,
                    nabuApiException = errorState.nabuApiException
                )
            ErrorState.InternetConnectionError ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.executing_connection_error),
                    description = getString(R.string.something_went_wrong_try_again),
                    error = INTERNET_CONNECTION_ERROR,
                )
            is ErrorState.ServerSideUxError ->
                navigator().showErrorInBottomSheet(
                    title = errorState.serverSideUxErrorInfo.title,
                    description = errorState.serverSideUxErrorInfo.description,
                    error = ClientErrorAnalytics.SERVER_SIDE_HANDLED_ERROR,
                    serverSideUxErrorInfo = errorState.serverSideUxErrorInfo
                )
            ErrorState.SettlementInsufficientBalance ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardInsufficientFunds),
                    description = getString(R.string.trading_deposit_description_insufficient),
                    error = SETTLEMENT_INSUFFICIENT_BALANCE
                )
            ErrorState.SettlementStaleBalance ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.trading_deposit_title_stale_balance),
                    description = getString(R.string.trading_deposit_description_stale),
                    error = SETTLEMENT_STALE_BALANCE
                )
            ErrorState.SettlementGenericError ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.common_oops_bank),
                    description = getString(R.string.trading_deposit_description_generic),
                    error = SETTLEMENT_GENERIC_ERROR
                )
            is ErrorState.SettlementRefreshRequired ->
                navigator().showBankRefreshError(errorState.accountId)
            ErrorState.ApproveBankInvalid,
            ErrorState.ApprovedBankAccountInvalid,
            ErrorState.ApprovedBankDeclined,
            ErrorState.ApprovedBankExpired,
            ErrorState.ApprovedBankFailed,
            ErrorState.ApprovedBankFailedInternal,
            ErrorState.ApprovedBankInsufficientFunds,
            ErrorState.ApprovedBankLimitedExceed,
            ErrorState.ApprovedBankRejected,
            is ErrorState.PaymentFailedError,
            ErrorState.ProviderIsNotSupported,
            is ErrorState.ApprovedBankUndefinedError,
            ErrorState.BankLinkingTimeout,
            ErrorState.Card3DsFailed,
            ErrorState.UnknownCardProvider,
            ErrorState.LinkedBankNotSupported -> {
                analytics.logEvent(
                    ClientErrorAnalytics.ClientLogError(
                        nabuApiException = null,
                        errorDescription = "IllegalStateException",
                        error = errorState.toString(),
                        source = ClientErrorAnalytics.Companion.Source.CLIENT,
                        title = "IllegalStateException",
                        action = ACTION_BUY,
                        categories = emptyList()
                    )
                )
                throw IllegalStateException(
                    "Error $errorState should not presented here"
                )
            }
            ErrorState.BuyPaymentMethodsUnavailable -> {
                navigator().goToBlockedBuyScreen()
            }
        }.exhaustive
    }

    override fun onPause() {
        super.onPause()
        model.process(SimpleBuyIntent.NavigationHandled)
    }

    override fun onIntervalSelected(interval: RecurringBuyFrequency) {
        model.process(SimpleBuyIntent.RecurringBuyIntervalUpdated(interval))
        binding.recurringBuyCta.apply {
            text = interval.toHumanReadableRecurringBuy(requireContext())
        }
    }

    override fun onActionInfoTriggered() {
        infoActionCallback()
    }

    override fun onSheetClosed() {
        model.process(SimpleBuyIntent.ClearError)
    }

    override fun onSheetClosed(sheet: BottomSheetDialogFragment) {
        super<TransactionFlowInfoHost>.onSheetClosed(sheet)
        if (sheet is TransactionFlowInfoBottomSheet) {
            analytics.logEvent(InfoBottomSheetDismissed(AssetAction.Buy))
        }
    }

    override fun onPaymentMethodChanged(paymentMethod: PaymentMethod) {
        model.process(SimpleBuyIntent.PaymentMethodChangeRequested(paymentMethod))

        if (paymentMethod.canBeUsedForPaying()) {
            analytics.logEvent(
                BuyPaymentMethodSelected(
                    paymentMethod.toNabuAnalyticsString()
                )
            )
        }
        when (paymentMethod) {
            is PaymentMethod.UndefinedCard -> {
                analytics.logEvent(SettingsAnalytics.LinkCardClicked(LaunchOrigin.BUY))
            }

            is PaymentMethod.UndefinedBankAccount -> {
                analytics.logEvent(BankTransferClicked(fiatCurrency = fiatCurrency))
            }

            else -> {
            }
        }
    }

    private fun addPaymentMethod(type: PaymentMethodType, fiatCurrency: FiatCurrency) {
        when (type) {
            PaymentMethodType.PAYMENT_CARD -> {
                val intent = Intent(activity, CardDetailsActivity::class.java)
                startActivityForResult(intent, ADD_CARD_REQUEST_CODE)
            }
            PaymentMethodType.FUNDS -> {
                showBottomSheet(
                    WireTransferAccountDetailsBottomSheet.newInstance(
                        fiatCurrency
                    )
                )
            }
            PaymentMethodType.BANK_TRANSFER -> {
                model.process(SimpleBuyIntent.LinkBankTransferRequested)
            }
            else -> {
            }
        }
        analytics.logEvent(PaymentMethodSelected(type.toAnalyticsString()))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_CARD_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val preselectedId =
                (data?.extras?.getSerializable(CardDetailsActivity.CARD_KEY) as? PaymentMethod.Card)?.id
            updatePaymentMethods(preselectedId)
        }
        if (requestCode == BankAuthActivity.LINK_BANK_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val preselectedId = data?.extras?.getString(BankAuthActivity.LINKED_BANK_ID_KEY)
            updatePaymentMethods(preselectedId)
        }
        if (requestCode == SimpleBuyActivity.KYC_STARTED) {
            if (resultCode == SimpleBuyActivity.RESULT_KYC_SIMPLE_BUY_COMPLETE) {
                model.process(SimpleBuyIntent.KycCompleted)
                navigator().goToKycVerificationScreen()
            } else if (resultCode == KycNavHostActivity.RESULT_KYC_FOR_SDD_COMPLETE) {
                model.process(
                    SimpleBuyIntent.UpdatePaymentMethodsAndAddTheFirstEligible(
                        fiatCurrency
                    )
                )
            }
        }
    }

    private fun updatePaymentMethods(preselectedId: String?) {
        model.process(
            SimpleBuyIntent.FetchSuggestedPaymentMethod(
                fiatCurrency,
                preselectedId,
                usePrefilledAmount = false,
                reloadQuickFillButtons = true
            )
        )
    }

    private fun canUseCreditCards(): Boolean {
        val paymentMethods = lastState?.paymentOptions?.availablePaymentMethods

        paymentMethods?.filterIsInstance<PaymentMethod.UndefinedCard>()?.forEach { card ->
            card.cardFundSources?.let { it ->
                val sources = it.toHashSet()
                // Credit cards are supported by default, unless they are explicitly missing from CardFundSources
                return sources.isEmpty() ||
                    (sources.size == 1 && sources.contains(CardFundSource.UNKNOWN)) ||
                    sources.contains(CardFundSource.CREDIT)
            }
        }
        return true
    }

    companion object {
        private const val ARG_CRYPTO_ASSET = "CRYPTO"
        private const val ARG_PAYMENT_METHOD_ID = "PAYMENT_METHOD_ID"
        private const val ARG_AMOUNT = "AMOUNT"
        private const val ARG_LINK_NEW_CARD = "LINK_NEW_CARD"
        private const val ARG_LAUNCH_PAYMENT_METHOD_SELECTION = "LAUNCH_PAYMENT_METHOD_SELECTION"

        fun newInstance(
            asset: AssetInfo,
            preselectedMethodId: String? = null,
            preselectedAmount: String? = null,
            launchLinkCard: Boolean = false,
            launchPaymentMethodSelection: Boolean = false,
        ): SimpleBuyCryptoFragment {
            return SimpleBuyCryptoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CRYPTO_ASSET, asset.networkTicker)
                    preselectedMethodId?.let { putString(ARG_PAYMENT_METHOD_ID, preselectedMethodId) }
                    preselectedAmount?.let { putString(ARG_AMOUNT, preselectedAmount) }
                    putBoolean(ARG_LINK_NEW_CARD, launchLinkCard)
                    putBoolean(ARG_LAUNCH_PAYMENT_METHOD_SELECTION, launchPaymentMethodSelection)
                }
            }
        }
    }

    private enum class PaymentMethodsChooserState {
        AVAILABLE_TO_PAY, AVAILABLE_TO_ADD
    }

    private fun TransactionErrorState.message(state: SimpleBuyState): String =
        when (this) {
            TransactionErrorState.BELOW_MIN_LIMIT ->
                resources.getString(R.string.minimum_buy, state.limits.minAmount.toStringWithSymbol())
            TransactionErrorState.INSUFFICIENT_FUNDS -> resources.getString(
                R.string.not_enough_funds, state.fiatCurrency.displayTicker
            )
            TransactionErrorState.OVER_SILVER_TIER_LIMIT,
            TransactionErrorState.OVER_GOLD_TIER_LIMIT,
            -> resources.getString(
                R.string.over_your_limit
            )
            TransactionErrorState.BELOW_MIN_PAYMENT_METHOD_LIMIT -> resources.getString(
                R.string.minimum_buy, state.limits.minAmount.toStringWithSymbol()
            )
            TransactionErrorState.ABOVE_MAX_PAYMENT_METHOD_LIMIT -> resources.getString(
                R.string.maximum_with_value, state.limits.maxAmount.toStringWithSymbol()
            )
            else -> resources.getString(R.string.empty)
        }

    private fun SimpleBuyState.errorStateShouldBeIndicated() =
        errorState != TransactionErrorState.NONE && amount.isPositive
}

fun RecurringBuyFrequency.toRecurringBuySuggestionTitle(context: Context): String {
    return when (this) {
        RecurringBuyFrequency.WEEKLY -> context.getString(R.string.checkout_rb_weekly_title)
        RecurringBuyFrequency.BI_WEEKLY -> context.getString(R.string.checkout_rb_biweekly_title)
        RecurringBuyFrequency.MONTHLY -> context.getString(R.string.checkout_rb_monthly_title)
        else -> context.getString(R.string.checkout_rb_weekly_title)
    }
}

fun RecurringBuyFrequency.toHumanReadableRecurringBuy(context: Context): String {
    return when (this) {
        RecurringBuyFrequency.ONE_TIME -> context.getString(R.string.recurring_buy_one_time_selector)
        RecurringBuyFrequency.DAILY -> context.getString(R.string.recurring_buy_daily_1)
        RecurringBuyFrequency.WEEKLY -> context.getString(R.string.recurring_buy_weekly_1)
        RecurringBuyFrequency.BI_WEEKLY -> context.getString(R.string.recurring_buy_bi_weekly_1)
        RecurringBuyFrequency.MONTHLY -> context.getString(R.string.recurring_buy_monthly_1)
        else -> context.getString(R.string.common_unknown)
    }
}

@StringRes fun RecurringBuyFrequency.toHumanReadableRecurringBuy(): Int {
    return when (this) {
        RecurringBuyFrequency.ONE_TIME -> R.string.recurring_buy_one_time_selector
        RecurringBuyFrequency.DAILY -> R.string.recurring_buy_daily_1
        RecurringBuyFrequency.WEEKLY -> R.string.recurring_buy_weekly_1
        RecurringBuyFrequency.BI_WEEKLY -> R.string.recurring_buy_bi_weekly_1
        RecurringBuyFrequency.MONTHLY -> R.string.recurring_buy_monthly_1
        else -> R.string.common_unknown
    }
}

fun RecurringBuyFrequency.toHumanReadableRecurringDate(context: Context, dateTime: ZonedDateTime): String {
    return when (this) {
        RecurringBuyFrequency.DAILY -> {
            context.getString(
                R.string.recurring_buy_frequency_subtitle_each_day,
                dateTime.to12HourFormat()
            )
        }
        RecurringBuyFrequency.BI_WEEKLY, RecurringBuyFrequency.WEEKLY -> {
            context.getString(
                R.string.recurring_buy_frequency_subtitle,
                dateTime.dayOfWeek
                    .getDisplayName(TextStyle.FULL, Locale.getDefault())
                    .toString().capitalizeFirstChar()
            )
        }
        RecurringBuyFrequency.MONTHLY -> {
            if (dateTime.isLastDayOfTheMonth()) {
                context.getString(R.string.recurring_buy_frequency_subtitle_monthly_last_day)
            } else {
                context.getString(
                    R.string.recurring_buy_frequency_subtitle_monthly,
                    dateTime.dayOfMonth.toString()
                )
            }
        }
        RecurringBuyFrequency.ONE_TIME,
        RecurringBuyFrequency.UNKNOWN,
        -> ""
    }
}
