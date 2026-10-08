package dev.danascape.launcher3feed.glance

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The edit mode toolbar, mirroring the hub's own.
 *
 * The hub shows two buttons while editing: the leading one opens the widget picker, or becomes
 * "Remove" once a widget is selected, and the trailing one is always "Done". The leading button is
 * filled and the trailing one outlined, swapping emphasis when a widget is selected, which is what
 * `CommunalHub.Toolbar` and `ToolbarButton(isPrimary = …)` do.
 *
 * Shape and colour follow Material 3 the way the Compose buttons do: a pill, `colors.primary` on
 * `colors.onPrimary` when filled, and a 2dp `colors.primary` border when outlined.
 */
class GlanceEditToolbar(
    context: Context,
    private val onAddWidget: () -> Unit,
    private val onRemoveWidget: () -> Unit,
    private val onDone: () -> Unit,
) : FrameLayout(context) {

    private val theme = HubTheme.from(context)
    private val leadingButton: TextView
    private val doneButton: TextView

    /** True once a widget is selected, which turns the leading button into Remove. */
    var removeEnabled: Boolean = false
        set(value) {
            field = value
            updateButtons()
        }

    init {
        val density = resources.displayMetrics.density
        // The top value is a placeholder: GlanceOverlay overwrites it with the real status bar
        // inset as soon as one is available, in place of the hub's own cutout-derived padding.
        setPadding(
            (TOOLBAR_PADDING_HORIZONTAL_DP * density).toInt(),
            (TOOLBAR_PADDING_TOP_DP * density).toInt(),
            (TOOLBAR_PADDING_HORIZONTAL_DP * density).toInt(),
            0,
        )

        leadingButton = button(density)
        doneButton = button(density).apply { setOnClickListener { onDone() } }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(leadingButton)
                addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
                addView(doneButton)
                layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
        )

        updateButtons()
    }

    private fun button(density: Float): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            minHeight = (HubMetrics.TOOLBAR_HEIGHT_DP * density).toInt()
            val h = (BUTTON_PADDING_HORIZONTAL_DP * density).toInt()
            setPadding(h, 0, h, 0)
        }

    private fun updateButtons() {
        if (removeEnabled) {
            leadingButton.setText(R.string.glance_remove)
            leadingButton.setOnClickListener { onRemoveWidget() }
        } else {
            leadingButton.setText(R.string.glance_add_widget)
            leadingButton.setOnClickListener { onAddWidget() }
        }
        doneButton.setText(R.string.glance_done)

        // Emphasis follows the hub: the actionable button is filled, the other outlined.
        style(leadingButton, filled = true)
        style(doneButton, filled = false)
    }

    private fun style(button: TextView, filled: Boolean) {
        val density = resources.displayMetrics.density
        button.background =
            GradientDrawable().apply {
                // Material 3 buttons are pills, so the radius is half the height.
                cornerRadius = HubMetrics.TOOLBAR_HEIGHT_DP * density / 2f
                if (filled) {
                    setColor(theme.primary)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke((BORDER_WIDTH_DP * density).toInt(), theme.primary)
                }
            }
        button.setTextColor(if (filled) theme.onPrimary else theme.primary)
    }

    companion object {
        /** `Dimensions.ToolbarPaddingTop` with the responsive grid. */
        private const val TOOLBAR_PADDING_TOP_DP = 12f

        /**
         * `Dimensions.toolbarHorizontalPadding` for a phone (the hub's `WindowSizeCategory.MOBILE`
         * case). This is a separate, smaller value from the grid's own `ItemSpacing`-based
         * [HubMetrics] gap — the hub does not use the same inset for both.
         */
        private const val TOOLBAR_PADDING_HORIZONTAL_DP = 9f

        /** `Dimensions.ToolbarButtonPaddingHorizontal`. */
        private const val BUTTON_PADDING_HORIZONTAL_DP = 24f

        /** The hub's outlined button border. */
        private const val BORDER_WIDTH_DP = 2f
    }
}
