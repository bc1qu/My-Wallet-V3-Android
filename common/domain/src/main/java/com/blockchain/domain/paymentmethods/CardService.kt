package com.blockchain.domain.paymentmethods

import com.blockchain.data.DataResource
import com.blockchain.data.FreshnessStrategy
import com.blockchain.domain.paymentmethods.model.BillingAddress
import com.blockchain.domain.paymentmethods.model.CardRejectionCheckError
import com.blockchain.domain.paymentmethods.model.CardRejectionState
import com.blockchain.domain.paymentmethods.model.CardStatus
import com.blockchain.domain.paymentmethods.model.CardToBeActivated
import com.blockchain.domain.paymentmethods.model.GooglePayInfo
import com.blockchain.domain.paymentmethods.model.LinkedPaymentMethod
import com.blockchain.domain.paymentmethods.model.PartnerCredentials
import com.blockchain.domain.paymentmethods.model.PaymentMethod
import com.blockchain.outcome.Outcome
import info.blockchain.balance.FiatCurrency
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.flow.Flow

interface CardService {

    fun getLinkedCards(
        request: FreshnessStrategy,
        vararg states: CardStatus
    ): Flow<DataResource<List<LinkedPaymentMethod.Card>>>

    fun getLinkedCards(vararg states: CardStatus): Single<List<LinkedPaymentMethod.Card>>

    fun addNewCard(
        fiatCurrency: FiatCurrency,
        billingAddress: BillingAddress,
        paymentMethodTokens: Map<String, String>? = null
    ): Single<CardToBeActivated>

    fun deleteCard(cardId: String): Completable

    fun activateCard(cardId: String, redirectUrl: String, cvv: String): Single<PartnerCredentials>

    fun getCardDetails(cardId: String): Single<PaymentMethod.Card>

    fun getGooglePayTokenizationParameters(currency: String): Single<GooglePayInfo>

    suspend fun checkNewCardRejectionState(binNumber: String): Outcome<CardRejectionCheckError, CardRejectionState>
}
