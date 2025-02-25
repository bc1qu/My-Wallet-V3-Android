package piuk.blockchain.android.ui.home

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blockchain.analytics.events.LaunchOrigin
import com.blockchain.commonarch.presentation.base.ComposeModalBottomDialog
import com.blockchain.componentlib.sheets.SheetHeader
import com.blockchain.koin.payloadScope
import com.blockchain.walletmode.WalletMode
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.scope.AndroidScopeComponent
import org.koin.core.scope.Scope
import piuk.blockchain.android.R

class ActionsBottomSheet : ComposeModalBottomDialog(), AndroidScopeComponent {
    private val viewModel: ActionsSheetViewModel by inject()
    override val scope: Scope = payloadScope

    override val host: ActionBottomSheetHost by lazy {
        super.host as? ActionBottomSheetHost ?: throw IllegalStateException(
            "Host fragment is not a ActionBottomSheetHost.Host"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val walletMode = arguments?.getSerializable(WALLET_MODE) as? WalletMode ?: throw IllegalStateException(
            "Undefined wallet mode"
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationEventFlow.collect {
                    when (it) {
                        ActionsSheetNavEvent.Buy -> host.launchBuy()
                        ActionsSheetNavEvent.Receive -> host.launchReceive()
                        ActionsSheetNavEvent.Send -> host.launchSend()
                        ActionsSheetNavEvent.TradingBuy -> host.launchBuyForDefi()
                        ActionsSheetNavEvent.Swap -> host.launchSwapScreen()
                        ActionsSheetNavEvent.Rewards -> host.launchInterestDashboard(LaunchOrigin.NAVIGATION)
                        ActionsSheetNavEvent.Sell -> host.launchSell()
                        is ActionsSheetNavEvent.TooMayPendingBuys -> host.launchTooManyPendingBuys(
                            it.maxTransactions
                        )
                    }
                    dismiss()
                }
            }
        }
        viewModel.onIntent(ActionsSheetIntent.LoadActions(walletMode))
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Sheet() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val stateFlowLifecycleAware = remember(viewModel.viewState, lifecycleOwner) {
            viewModel.viewState.flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
        }
        val viewState: ActionsSheetViewState? by stateFlowLifecycleAware.collectAsState(null)

        viewState?.let {
            BottomSheetActionsMenu(
                viewState = it,
            )
        }
    }

    @Composable
    private fun BottomSheetActionsMenu(viewState: ActionsSheetViewState) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SheetHeader(
                title = stringResource(id = R.string.shortcuts),
                onClosePress = { dismiss() },
                shouldShowDivider = true
            )
            ActionRows(
                data = viewState.actions,
                onClick = {
                    viewModel.onIntent(ActionsSheetIntent.ActionClicked(it))
                }
            )
            viewState.bottomItem?.let {
                BottomItem(sheetAction = it, onClick = { action ->
                    viewModel.onIntent(ActionsSheetIntent.ActionClicked(action))
                })
            }
        }
    }

    companion object {
        private const val WALLET_MODE = "WALLET_MODE"
        fun newInstance(walletMode: WalletMode) = ActionsBottomSheet().apply {
            arguments = Bundle().apply {
                putSerializable(WALLET_MODE, walletMode)
            }
        }
    }
}
