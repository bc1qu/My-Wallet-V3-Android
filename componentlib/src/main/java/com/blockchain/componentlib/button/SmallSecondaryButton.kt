package com.blockchain.componentlib.button

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.material.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.Preview
import com.blockchain.componentlib.R
import com.blockchain.componentlib.basic.ImageResource
import com.blockchain.componentlib.theme.AppSurface
import com.blockchain.componentlib.theme.AppTheme
import com.blockchain.componentlib.theme.Dark800
import com.blockchain.componentlib.theme.Grey500
import com.blockchain.componentlib.theme.Grey800
import com.blockchain.componentlib.theme.Grey900

@Composable
fun SmallSecondaryButton(
    text: String,
    onClick: () -> Unit,
    state: ButtonState,
    modifier: Modifier = Modifier,
) {
    val contentPadding = PaddingValues(
        start = if (state == ButtonState.Loading) {
            dimensionResource(R.dimen.medium_margin)
        } else {
            dimensionResource(R.dimen.very_small_margin)
        },
        top = ButtonDefaults.ContentPadding.calculateTopPadding(),
        end = if (state == ButtonState.Loading) dimensionResource(R.dimen.medium_margin) else dimensionResource(
            R.dimen.very_small_margin
        ),
        bottom = ButtonDefaults.ContentPadding.calculateBottomPadding(),
    )

    Button(
        text = text,
        onClick = onClick,
        state = state,
        shape = AppTheme.shapes.extraLarge,
        defaultTextColor = Color.White,
        defaultBackgroundLightColor = Grey800,
        defaultBackgroundDarkColor = Grey800,
        disabledTextLightAlpha = 0.7f,
        disabledTextDarkAlpha = 0.4f,
        disabledBackgroundLightColor = Grey500,
        disabledBackgroundDarkColor = Dark800,
        pressedBackgroundColor = Grey900,
        modifier = modifier.requiredHeightIn(min = dimensionResource(R.dimen.large_margin)),
        contentPadding = contentPadding,
        buttonContent = { state: ButtonState, text: String, textColor: Color, textAlpha: Float, _: ImageResource ->
            ButtonContentSmall(
                state = state,
                text = text,
                textColor = textColor,
                contentAlpha = textAlpha
            )
        },
    )
}

@Preview(name = "Default", group = "Small secondary button")
@Composable
private fun SmallSecondaryButtonPreview() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Enabled
            )
        }
    }
}

@Preview(name = "Disabled", group = "Small secondary button")
@Composable
private fun SmallSecondaryButtonDisabledPreview() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Disabled
            )
        }
    }
}

@Preview(name = "Loading", group = "Small secondary button")
@Composable
private fun SmallSecondaryButtonLoadingPreview() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Loading
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SmallSecondaryButtonPreview_Dark() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Enabled
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrimaryButtonDisabledPreview_Dark() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Disabled
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrimaryButtonLoadingPreview_Dark() {
    AppTheme {
        AppSurface {
            SmallSecondaryButton(
                text = "Click me",
                onClick = { },
                state = ButtonState.Loading
            )
        }
    }
}
