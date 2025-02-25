package com.blockchain.core.chains.dynamicselfcustody.domain

import com.blockchain.api.selfcustody.BuildTxResponse
import com.blockchain.api.selfcustody.PushTxResponse
import com.blockchain.core.chains.dynamicselfcustody.domain.model.NonCustodialAccountBalance
import com.blockchain.core.chains.dynamicselfcustody.domain.model.NonCustodialDerivedAddress
import com.blockchain.core.chains.dynamicselfcustody.domain.model.NonCustodialTxHistoryItem
import com.blockchain.core.chains.dynamicselfcustody.domain.model.TransactionSignature
import com.blockchain.data.FreshnessStrategy
import com.blockchain.outcome.Outcome
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.Currency
import info.blockchain.wallet.dynamicselfcustody.CoinConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface NonCustodialService {

    suspend fun getCoinConfigurationFor(currency: Currency): CoinConfiguration?

    suspend fun authenticate(): Outcome<Exception, Boolean>

    suspend fun subscribe(currency: String, label: String, addresses: List<String>): Outcome<Exception, Boolean>

    suspend fun unsubscribe(currency: String): Outcome<Exception, Boolean>

    fun getSubscriptions(
        refreshStrategy: FreshnessStrategy = FreshnessStrategy.Cached(forceRefresh = true)
    ): Flow<Outcome<Exception, List<String>>>

    suspend fun getBalances(currencies: List<String>): Outcome<Exception, List<NonCustodialAccountBalance>>

    suspend fun getAddresses(currencies: List<String>): Outcome<Exception, List<NonCustodialDerivedAddress>>

    suspend fun getTransactionHistory(
        currency: String,
        contractAddress: String?
    ): Outcome<Exception, List<NonCustodialTxHistoryItem>>

    suspend fun buildTransaction(
        currency: String,
        accountIndex: Int = 0,
        type: String,
        transactionTarget: String,
        amount: String,
        fee: String,
        memo: String = "",
        feeCurrency: String = currency
    ): Outcome<Exception, BuildTxResponse>

    fun getFeeCurrencyFor(asset: AssetInfo): AssetInfo

    suspend fun pushTransaction(
        currency: String,
        rawTx: JsonObject,
        signatures: List<TransactionSignature>
    ): Outcome<Exception, PushTxResponse>
}
