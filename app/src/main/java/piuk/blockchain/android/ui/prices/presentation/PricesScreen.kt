package piuk.blockchain.android.ui.prices.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.blockchain.componentlib.control.Search
import com.blockchain.componentlib.theme.AppTheme
import info.blockchain.balance.AssetInfo
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.prices.presentation.composables.PriceListItem
import piuk.blockchain.android.ui.prices.presentation.composables.PriceScreenLoading
import piuk.blockchain.android.ui.prices.presentation.composables.PricesScreenError

@Composable
fun PricesScreen(
    viewState: PricesViewState,
    retryAction: () -> Unit,
    pricesItemClicked: (AssetInfo) -> Unit,
    filterData: (String) -> Unit,
) {
    with(viewState) {
        when {
            isLoading -> PriceScreenLoading()
            isError -> PricesScreenError(retryAction)
            else -> {
                Column {
                    Box(
                        modifier = Modifier.padding(
                            start = AppTheme.dimensions.paddingLarge,
                            top = AppTheme.dimensions.paddingSmall,
                            end = AppTheme.dimensions.paddingLarge,
                            bottom = AppTheme.dimensions.paddingMedium
                        )
                    ) {
                        Search(
                            label = stringResource(R.string.search_coins_hint),
                            onValueChange = filterData
                        )
                    }

                    LazyColumn {
                        items(
                            items = data,
                        ) {
                            PriceListItem(
                                priceItem = it,
                                onClick = { pricesItemClicked(it.assetInfo) }
                            )
                        }

                        item {
                            Spacer(Modifier.size(dimensionResource(R.dimen.standard_margin)))
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
fun PreviewInterestDashboardScreenLoading() {
    PricesScreen(
        PricesViewState(
            isLoading = true,
            isError = false,
            data = listOf()
        ),
        {}, {}, {},
    )
}

@Preview(name = "Error", showBackground = true)
@Composable
fun PreviewInterestDashboardScreenError() {
    PricesScreen(
        PricesViewState(
            isLoading = false,
            isError = true,
            data = listOf()
        ),
        {}, {}, {},
    )
}

@Preview(name = "Data", showBackground = true)
@Composable
fun PreviewInterestDashboardScreenDataNoKyc() {
    PricesScreen(
        PricesViewState(
            isLoading = false,
            isError = false,
            data = listOf()
        ),
        {}, {}, {},
    )
}
