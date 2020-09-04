package piuk.blockchain.android.ui.dashboard.assetdetails

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blockchain.koin.scopedInject
import com.blockchain.swap.nabu.models.nabu.KycTierLevel
import com.blockchain.swap.nabu.service.TierService
import info.blockchain.balance.CryptoCurrency
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.dialog_asset_actions_sheet.view.*
import kotlinx.android.synthetic.main.item_asset_action.view.*
import piuk.blockchain.android.R
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.AssetFilter
import piuk.blockchain.android.coincore.BlockchainAccount
import piuk.blockchain.android.coincore.Coincore
import piuk.blockchain.android.ui.base.mvi.MviBottomSheet
import piuk.blockchain.android.util.assetFilter
import piuk.blockchain.android.util.assetTint
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom
import piuk.blockchain.androidcoreui.utils.extensions.inflate
import timber.log.Timber

class AssetActionsSheet : MviBottomSheet<AssetDetailsModel, AssetDetailsIntent, AssetDetailsState>() {
    private val disposables = CompositeDisposable()

    private val coincore: Coincore by scopedInject()

    private val kycTierService: TierService by scopedInject()

    override val model: AssetDetailsModel by scopedInject()

    private val itemAdapter: AssetActionAdapter by lazy {
        AssetActionAdapter()
    }

    override val layoutResource: Int
        get() = R.layout.dialog_asset_actions_sheet

    override fun render(newState: AssetDetailsState) {
        if (this.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {

            require(newState.selectedAccount != null)

            showAssetBalances(newState)

            val actionItems = mapActions(newState.selectedAccount)
            itemAdapter.itemList = actionItems

            if (newState.errorState != AssetDetailsError.NONE) {
                showError(newState.errorState)
            }
        }
    }

    override fun initControls(view: View) {
        view.asset_actions_list.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
            adapter = itemAdapter
        }

        view.asset_actions_back.setOnClickListener {
            model.process(ReturnToPreviousStep)
            dispose()
        }
    }

    override fun dismiss() {
        super.dismiss()
        model.process(ClearSheetDataIntent)
        dispose()
    }

    private fun showError(error: AssetDetailsError) =
        when (error) {
            AssetDetailsError.TX_IN_FLIGHT -> ToastCustom.makeText(requireContext(),
                getString(R.string.dashboard_asset_actions_tx_in_progress), Toast.LENGTH_SHORT,
                ToastCustom.TYPE_ERROR)
            else -> {
                // do nothing
            }
        }

    private fun showAssetBalances(state: AssetDetailsState) {
        dialogView.asset_actions_account_details.updateAccount(
            state.selectedAccount.selectFirstAccount(),
            disposables
        )
    }

    private fun mapActions(
        account: BlockchainAccount
    ): List<AssetActionItem> {
        val firstAccount = account.selectFirstAccount()
        return account.actions.map {
            mapAction(it, firstAccount.asset)
        }
    }

    private fun mapAction(action: AssetAction, asset: CryptoCurrency): AssetActionItem =
        when (action) {
            AssetAction.ViewActivity ->
                AssetActionItem(getString(R.string.activities_title),
                    R.drawable.ic_tx_activity_clock,
                    getString(R.string.fiat_funds_detail_activity_details), asset) {
                    processAction(AssetAction.ViewActivity)
                }
            AssetAction.Send ->
                AssetActionItem(getString(R.string.common_send), R.drawable.ic_tx_sent,
                    getString(R.string.dashboard_asset_actions_send_dsc, asset.displayTicker), asset) {
                    processAction(AssetAction.Send)
                    dismiss()
                }
            AssetAction.NewSend ->
                AssetActionItem(getString(R.string.common_send), R.drawable.ic_tx_sent,
                    getString(R.string.dashboard_asset_actions_send_dsc,
                        asset.displayTicker), asset) {
                    processAction(AssetAction.NewSend)
                }
            AssetAction.Receive ->
                AssetActionItem(getString(R.string.common_receive), R.drawable.ic_tx_receive,
                    getString(R.string.dashboard_asset_actions_receive_dsc,
                        asset.displayTicker), asset) {
                    processAction(AssetAction.Receive)
                    dismiss()
                }
            AssetAction.Swap -> AssetActionItem(getString(R.string.common_swap),
                R.drawable.ic_tx_swap,
                getString(R.string.dashboard_asset_actions_swap_dsc, asset.displayTicker),
                asset) {
                processAction(AssetAction.Swap)
            }
            AssetAction.Summary -> AssetActionItem(
                getString(R.string.dashboard_asset_actions_summary_title),
                R.drawable.ic_tx_interest,
                getString(R.string.dashboard_asset_actions_summary_dsc, asset.displayTicker),
                asset) {
                goToSummary()
            }
            AssetAction.Deposit -> AssetActionItem(getString(R.string.common_transfer),
                R.drawable.ic_tx_deposit_arrow,
                getString(R.string.dashboard_asset_actions_deposit_dsc, asset.displayTicker),
                asset) {
                checkForKycStatus {
                    goToDeposit(asset)
                }
            }
            AssetAction.Sell -> AssetActionItem(getString(R.string.common_sell),
                R.drawable.ic_tx_sell,
                getString(R.string.convert_your_crypto_to_cash),
                asset) {
                processAction(AssetAction.Sell)
            }
        }

    private fun checkForKycStatus(action: () -> Unit) {
        disposables += kycTierService.tiers().subscribeBy(
            onSuccess = { tiers ->
                if (tiers.isApprovedFor(KycTierLevel.GOLD)) {
                    action()
                } else {
                    model.process(ShowInterestDashboard)
                    dismiss()
                }
            },
            onError = {
                Timber.e("Error getting tiers in asset actions sheet $it")
            }
        )
    }

    private fun goToDeposit(asset: CryptoCurrency) {
        checkForKycStatus {
            disposables += coincore[asset].accountGroup(AssetFilter.NonCustodial)
                .subscribeBy {
                    when {
                        it.accounts.size > 1 -> {
                            model.process(SelectSendingAccount)
                        }
                        it.accounts.size == 1 -> {
                            model.process(HandleActionIntent(AssetAction.Deposit))
                        }
                        else -> {
                            throw IllegalStateException(
                                "No accounts available to deposit into interest"
                            )
                        }
                    }
                }
        }
    }

    private fun goToSummary() {
        checkForKycStatus {
            processAction(AssetAction.Summary)
        }
    }

    private fun processAction(action: AssetAction) {
        model.process(HandleActionIntent(action))
        dispose()
    }

    companion object {
        fun newInstance(): AssetActionsSheet = AssetActionsSheet()
    }
}

private class AssetActionAdapter : RecyclerView.Adapter<AssetActionAdapter.ActionItemViewHolder>() {
    var itemList: List<AssetActionItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActionItemViewHolder =
        ActionItemViewHolder(parent.inflate(R.layout.item_asset_action))

    override fun getItemCount(): Int = itemList.size

    override fun onBindViewHolder(holder: ActionItemViewHolder, position: Int) =
        holder.bind(itemList[position])

    private class ActionItemViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: AssetActionItem) {
            view.item_action_holder.setOnClickListener {
                item.actionCta()
            }

            view.item_action_icon.setImageResource(item.icon)
            view.item_action_icon.setAssetIconColours(item.asset, view.context)
            view.item_action_title.text = item.title
            view.item_action_label.text = item.description
        }

        private fun ImageView.setAssetIconColours(
            cryptoCurrency: CryptoCurrency,
            context: Context
        ) {
            setBackgroundResource(R.drawable.bkgd_tx_circle)
            background.setTint(ContextCompat.getColor(context, cryptoCurrency.assetTint()))
            setColorFilter(ContextCompat.getColor(context, cryptoCurrency.assetFilter()))
        }
    }
}

private data class AssetActionItem(
    val title: String,
    val icon: Int,
    val description: String,
    val asset: CryptoCurrency,
    val actionCta: () -> Unit
)