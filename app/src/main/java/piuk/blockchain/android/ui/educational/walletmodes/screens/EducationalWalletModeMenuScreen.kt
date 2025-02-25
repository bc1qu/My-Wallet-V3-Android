package piuk.blockchain.android.ui.educational.walletmodes.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.blockchain.componentlib.basic.Image
import com.blockchain.componentlib.basic.ImageResource
import com.blockchain.componentlib.theme.AppTheme
import piuk.blockchain.android.R

@Composable
fun EducationalWalletModeMenuScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.dimensions.paddingLarge)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1F),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                imageResource = ImageResource.Local(R.drawable.ic_educational_wallet_menu)
            )

            Spacer(modifier = Modifier.size(AppTheme.dimensions.paddingLarge))

            Text(
                text = stringResource(R.string.educational_wallet_mode_menu_title),
                style = AppTheme.typography.title3,
                color = AppTheme.colors.title,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.size(AppTheme.dimensions.paddingSmall))

            Text(
                text = stringResource(R.string.educational_wallet_mode_menu_description),
                style = AppTheme.typography.paragraph1,
                color = AppTheme.colors.title,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6F)
        ) {
        }
    }
}

// ///////////////
// PREVIEWS
// ///////////////

@Preview(showBackground = true)
@Composable
fun PreviewEducationalWalletModeMenuScreen() {
    EducationalWalletModeMenuScreen()
}
