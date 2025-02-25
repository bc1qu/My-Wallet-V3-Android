package piuk.blockchain.android.ui.transactionflow.flow.adapter

import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.blockchain.coincore.TxConfirmation
import com.blockchain.coincore.TxConfirmationValue
import com.blockchain.componentlib.viewextensions.visibleIf
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.ItemCheckoutComplexExpandableInfoBinding
import piuk.blockchain.android.ui.adapters.AdapterDelegate
import piuk.blockchain.android.ui.transactionflow.flow.ConfirmationPropertyKey
import piuk.blockchain.android.ui.transactionflow.flow.TxConfirmReadOnlyMapperCheckout
import piuk.blockchain.android.util.getResolvedColor

class ExpandableComplexConfirmationCheckout(private val mapper: TxConfirmReadOnlyMapperCheckout) :
    AdapterDelegate<TxConfirmationValue> {
    override fun isForViewType(items: List<TxConfirmationValue>, position: Int): Boolean {
        return items[position].confirmation == TxConfirmation.EXPANDABLE_COMPLEX_READ_ONLY
    }

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        ExpandableComplexConfirmationCheckoutItemViewHolder(
            ItemCheckoutComplexExpandableInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            mapper
        )

    override fun onBindViewHolder(
        items: List<TxConfirmationValue>,
        position: Int,
        holder: RecyclerView.ViewHolder
    ) = (holder as ExpandableComplexConfirmationCheckoutItemViewHolder).bind(
        items[position]
    )
}

private class ExpandableComplexConfirmationCheckoutItemViewHolder(
    val binding: ItemCheckoutComplexExpandableInfoBinding,
    private val mapper: TxConfirmReadOnlyMapperCheckout
) : RecyclerView.ViewHolder(binding.root) {
    private var isExpanded = false

    init {
        with(binding) {
            expandableComplexItemExpansion.movementMethod = LinkMovementMethod.getInstance()
            expandableComplexItemLabel.setOnClickListener {
                isExpanded = !isExpanded
                expandableComplexItemExpansion.visibleIf { isExpanded }
                updateIcon()
            }
        }
    }

    fun bind(item: TxConfirmationValue) {
        with(binding) {
            mapper.map(item).run {
                expandableComplexItemLabel.text = this[ConfirmationPropertyKey.LABEL] as String
                expandableComplexItemTitle.text = this[ConfirmationPropertyKey.TITLE] as String
                expandableComplexItemSubtitle.text = this[ConfirmationPropertyKey.SUBTITLE] as String
                expandableComplexItemExpansion.setText(
                    this[ConfirmationPropertyKey.LINKED_NOTE] as SpannableStringBuilder, TextView.BufferType.SPANNABLE
                )
            }
        }
        updateIcon()

        if (item is TxConfirmationValue.SwapExchange) {
            updateUiElementsIfNeeded(item)
        }
    }

    private fun updateUiElementsIfNeeded(item: TxConfirmationValue.SwapExchange) {
        with(binding) {
            when {
                item.isNewQuote -> {
                    expandableComplexItemTitle.updateColour(R.color.blue_600)
                    expandableComplexItemSubtitle.updateColour(R.color.blue_600)
                }
                else -> {
                    expandableComplexItemTitle.updateColour(R.color.grey_800)
                    expandableComplexItemSubtitle.updateColour(R.color.grey_600)
                }
            }
        }
    }

    private fun TextView.updateColour(@ColorRes colour: Int) =
        setTextColor(
            ContextCompat.getColor(context, colour)
        )

    private fun updateIcon() {
        with(binding) {
            // unique drawables will share a single Drawable.ConstantState object, so we need to call mutate to get an individual config instance
            expandableComplexItemLabel.compoundDrawables[DRAWABLE_END]?.mutate()

            if (isExpanded) {
                expandableComplexItemLabel.compoundDrawables[DRAWABLE_END]?.setTint(
                    expandableComplexItemLabel.context.getResolvedColor(R.color.blue_600)
                )
            } else {
                expandableComplexItemLabel.compoundDrawables[DRAWABLE_END]?.setTint(
                    expandableComplexItemLabel.context.getResolvedColor(R.color.grey_300)
                )
            }
        }
    }

    companion object {
        private const val DRAWABLE_END = 2
    }
}
