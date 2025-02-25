package com.blockchain.componentlib.control

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import com.blockchain.componentlib.R
import com.blockchain.componentlib.theme.AppSurface
import com.blockchain.componentlib.theme.AppTheme
import com.blockchain.componentlib.utils.BaseAbstractComposeView

class SuccessSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseAbstractComposeView(context, attrs, defStyleAttr) {

    var isChecked by mutableStateOf(false)
    var onCheckChanged by mutableStateOf({ _: Boolean -> })

    @Composable
    override fun Content() {
        AppTheme {
            AppSurface {
                SuccessSwitch(
                    modifier = Modifier
                        .padding(dimensionResource(R.dimen.very_small_margin)),
                    isChecked = isChecked,
                    onCheckChanged = {
                        isChecked = it
                        onCheckChanged(it)
                    }
                )
            }
        }
    }

    fun clearState() {
        isChecked = false
        onCheckChanged = { _: Boolean -> }
    }
}
