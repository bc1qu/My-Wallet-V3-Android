package piuk.blockchain.android.util

import android.content.res.Resources
import com.blockchain.wallet.DefaultLabels
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.Currency
import info.blockchain.balance.FiatCurrency
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.resources.AssetResources

internal class ResourceDefaultLabels(
    private val resources: Resources,
    private val assetResources: AssetResources
) : DefaultLabels {

    override fun getDefaultNonCustodialWalletLabel(): String =
        resources.getString(
            R.string.default_crypto_non_custodial_wallet_label
        )

    override fun getOldDefaultNonCustodialWalletLabel(asset: AssetInfo): String =
        resources.getString(
            R.string.old_default_non_custodial_wallet_label,
            asset.name
        )

    override fun getDefaultCustodialWalletLabel(): String {
        return resources.getString(R.string.custodial_wallet_default_label_1)
    }

    override fun getDefaultFiatWalletLabel(): String =
        "Fiat Accounts"

    override fun getAssetMasterWalletLabel(asset: Currency): String =
        asset.name

    override fun getAllWalletLabel(): String =
        resources.getString(R.string.default_label_all_wallets)

    override fun getAllCustodialWalletsLabel(): String =
        resources.getString(R.string.default_label_all_custodial_wallets)

    override fun getAllNonCustodialWalletsLabel(): String =
        resources.getString(R.string.default_label_all_non_custodial_wallets)

    override fun getDefaultInterestWalletLabel(): String =
        resources.getString(R.string.default_label_rewards_wallet)

    override fun getDefaultExchangeWalletLabel(): String =
        resources.getString(R.string.exchange_default_account_label_1)

    override fun getDefaultCustodialFiatWalletLabel(fiatCurrency: FiatCurrency): String =
        fiatCurrency.name
}
