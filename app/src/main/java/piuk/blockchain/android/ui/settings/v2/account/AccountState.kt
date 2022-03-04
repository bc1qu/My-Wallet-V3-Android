package piuk.blockchain.android.ui.settings.v2.account

import com.blockchain.commonarch.presentation.mvi.MviState
import info.blockchain.balance.FiatCurrency

data class AccountState(
    val viewToLaunch: ViewToLaunch = ViewToLaunch.None,
    val accountInformation: AccountInformation? = null,
    val errorState: AccountError = AccountError.NONE,
    val exchangeLinkingState: ExchangeLinkingState = ExchangeLinkingState.UNKNOWN,
    val bcCardOrderState: DebitCardOrderState = DebitCardOrderState.UNKNOWN
) : MviState

sealed class ViewToLaunch {
    object None : ViewToLaunch()
    class CurrencySelection(val selectedCurrency: FiatCurrency, val currencyList: List<FiatCurrency>) : ViewToLaunch()
    class ExchangeLink(val exchangeLinkingState: ExchangeLinkingState) : ViewToLaunch()
    class BcDebitCardState(val bcDebitCardOrderState: DebitCardOrderState) : ViewToLaunch()
}

enum class ExchangeLinkingState {
    UNKNOWN,
    NOT_LINKED,
    LINKED
}

enum class DebitCardOrderState {
    NOT_ELIGIBLE,
    ELIGIBLE,
    UNKNOWN
}

data class AccountInformation(
    val walletId: String,
    val userCurrency: FiatCurrency,
)

enum class AccountError {
    NONE,
    ACCOUNT_INFO_FAIL,
    FIAT_LIST_FAIL,
    ACCOUNT_FIAT_UPDATE_FAIL,
    EXCHANGE_INFO_FAIL,
    EXCHANGE_LOAD_FAIL,
    BLOCKCHAIN_CARD_LOAD_FAIL
}
