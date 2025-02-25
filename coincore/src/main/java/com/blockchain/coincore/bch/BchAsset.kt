package com.blockchain.coincore.bch

import com.blockchain.coincore.CryptoAccount
import com.blockchain.coincore.CryptoAddress
import com.blockchain.coincore.IdentityAddressResolver
import com.blockchain.coincore.MultipleWalletsAsset
import com.blockchain.coincore.NonCustodialSupport
import com.blockchain.coincore.ReceiveAddress
import com.blockchain.coincore.SingleAccount
import com.blockchain.coincore.SingleAccountList
import com.blockchain.coincore.TxResult
import com.blockchain.coincore.impl.BackendNotificationUpdater
import com.blockchain.coincore.impl.CryptoAssetBase
import com.blockchain.coincore.impl.NotificationAddresses
import com.blockchain.coincore.impl.StandardL1Asset
import com.blockchain.core.chains.bitcoincash.BchBalanceCache
import com.blockchain.core.chains.bitcoincash.BchDataManager
import com.blockchain.preferences.WalletStatusPrefs
import com.blockchain.wallet.DefaultLabels
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.Money
import info.blockchain.wallet.util.FormatsUtil
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import piuk.blockchain.androidcore.data.fees.FeeDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.payments.SendDataManager
import timber.log.Timber

private const val BCH_URL_PREFIX = "bitcoincash:"

internal class BchAsset internal constructor(
    private val payloadManager: PayloadDataManager,
    private val bchDataManager: BchDataManager,
    private val feeDataManager: FeeDataManager,
    private val sendDataManager: SendDataManager,
    private val labels: DefaultLabels,
    private val bchBalanceCache: BchBalanceCache,
    private val walletPreferences: WalletStatusPrefs,
    private val beNotifyUpdate: BackendNotificationUpdater,
    private val addressResolver: IdentityAddressResolver
) : CryptoAssetBase(),
    NonCustodialSupport,
    StandardL1Asset,
    MultipleWalletsAsset {

    override val currency: AssetInfo
        get() = CryptoCurrency.BCH

    override fun initToken(): Completable =
        bchDataManager.initBchWallet(labels.getDefaultNonCustodialWalletLabel())
            .doOnError { Timber.e("Unable to init BCH, because: $it") }
            .onErrorComplete()

    override fun loadNonCustodialAccounts(labels: DefaultLabels): Single<SingleAccountList> =
        Single.fromCallable {
            with(bchDataManager) {
                mutableListOf<CryptoAccount>().apply {
                    getAccountMetadataList().forEachIndexed { i, account ->
                        val bchAccount = BchCryptoWalletAccount.createBchAccount(
                            payloadManager = payloadManager,
                            jsonAccount = account,
                            bchManager = bchDataManager,
                            addressIndex = i,
                            bchBalanceCache = bchBalanceCache,
                            exchangeRates = exchangeRates,
                            feeDataManager = feeDataManager,
                            sendDataManager = sendDataManager,
                            walletPreferences = walletPreferences,
                            custodialWalletManager = custodialManager,
                            refreshTrigger = this@BchAsset,
                            addressResolver = addressResolver
                        )
                        if (bchAccount.isDefault) {
                            updateBackendNotificationAddresses(bchAccount)
                        }
                        add(bchAccount)
                    }
                }
            }
        }

    private fun updateBackendNotificationAddresses(account: BchCryptoWalletAccount) {
        require(account.isDefault)
        require(!account.isArchived)

        val result = mutableListOf<String>()

        for (i in 0 until OFFLINE_CACHE_ITEM_COUNT) {
            account.getReceiveAddressAtPosition(i)?.let {
                result += it
            }
        }

        val notify = NotificationAddresses(
            assetTicker = currency.networkTicker,
            addressList = result
        )
        return beNotifyUpdate.updateNotificationBackend(notify)
    }

    override fun parseAddress(address: String, label: String?, isDomainAddress: Boolean): Maybe<ReceiveAddress> =
        Maybe.fromCallable {
            val normalisedAddress = address.removePrefix(BCH_URL_PREFIX)
            if (isValidAddress(normalisedAddress)) {
                BchAddress(
                    normalisedAddress,
                    label ?: address,
                    isDomainAddress
                )
            } else {
                null
            }
        }

    override fun isValidAddress(address: String): Boolean =
        FormatsUtil.isValidBCHAddress(address)

    companion object {
        private const val OFFLINE_CACHE_ITEM_COUNT = 5
    }

    override fun createWalletFromLabel(label: String, secondPassword: String?): Single<out SingleAccount> =
        throw UnsupportedOperationException("Action not supported")

    override fun createWalletFromAddress(address: String): Completable {
        return bchDataManager.createAccount(address).doOnComplete { forceAccountsRefresh() }
    }

    override fun importWalletFromKey(
        keyData: String,
        keyFormat: String,
        keyPassword: String?,
        walletSecondPassword: String?
    ): Single<out SingleAccount> =
        throw UnsupportedOperationException("Action not supported")
}

internal class BchAddress(
    address_: String,
    override val label: String = address_,
    override val isDomain: Boolean = false,
    override val onTxCompleted: (TxResult) -> Completable = { Completable.complete() }
) : CryptoAddress {
    override val address: String = address_.removeBchUri()
    override val asset: AssetInfo = CryptoCurrency.BCH

    override fun toUrl(amount: Money): String {
        return "$BCH_URL_PREFIX$address"
    }

    private fun String.removeBchUri(): String = this.replace(BCH_URL_PREFIX, "")
}
