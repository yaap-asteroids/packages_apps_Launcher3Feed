package dev.danascape.launcher3feed.glance

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.FrameLayout

/**
 * Wraps a widget so it can be selected and resized in edit mode.
 *
 * The hub outlines the selected widget and puts a drag handle above and below it, one per
 * [ResizeHandle], and dragging changes how many spans the widget takes:
 * `ResizableItemFrame` draws `ResizeHandle.TOP` and `ResizeHandle.BOTTOM` around the content, each
 * a `DragHandle` — a small filled circle ringed with a border, centered on the content's own top
 * or bottom edge. (A second, pill-shaped handle exists in the same file, but only for the
 * accessibility resize controls a long-press-to-toggle reveals in place of the plain drag handle;
 * the always-visible one a finger drags is the circle.) Our handles sit fully inside the frame
 * at that edge rather than straddling it half in, half out as the hub's do — doing that here
 * would mean turning off child clipping on this frame, the column holding it and the scroll view
 * around that, which isn't worth the risk of a visual regression we can't see without a build.
 *
 * While edit mode is off the frame is inert and the widget receives touches as usual.
 */
@SuppressLint("ClickableViewAccessibility")
class GlanceWidgetFrame(
    context: Context,
    val appWidgetId: Int,
    private val onSelected: () -> Unit,
    private val onResize: (spanDelta: Int) -> Unit,
    private val onLongPress: () -> Unit,
) : FrameLayout(context) {

    private val theme = HubTheme.from(context)
    private val density = resources.displayMetrics.density
    private val topHandle = handle(Gravity.TOP)
    private val bottomHandle = handle(Gravity.BOTTOM)

    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) editSelected = false
            updateChrome()
        }

    /**
     * Whether this widget is the one selected in edit mode.
     *
     * Deliberately not `View.isSelected`: that shares a JVM signature with the property, and
     * `setSelected` propagates down to children, which would reach the widget's own RemoteViews.
     */
    var editSelected: Boolean = false
        set(value) {
            field = value
            updateChrome()
        }

    /**
     * Pixel pitch of one hub row, set by the overlay from [HubMetrics.rowPitchPx]. The resize
     * handles commit one span per pitch of drag, so this stays the step unit regardless of how
     * many spans the widget itself currently occupies.
     */
    var rowPitchPx: Float = 1f

    init {
        addView(topHandle)
        addView(bottomHandle)
        updateChrome()
    }

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        onLongPress()
    }

    /**
     * Watches for a long press without stealing ordinary taps from the widget.
     *
     * A widget's own RemoteViews consume touches, so a long click listener on this frame would
     * never fire. Launcher3 solves the same problem with `CheckLongPressHelper`; this is that in
     * miniature: arm a timer on the way down, cancel it on movement or release, and only start
     * intercepting once it has fired. In edit mode everything is intercepted, because taps select.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressFired = false
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE ->
                if (
                    kotlin.math.abs(ev.x - downX) > touchSlop ||
                        kotlin.math.abs(ev.y - downY) > touchSlop
                ) {
                    cancelLongPress()
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
        return longPressFired
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onSelected()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private fun updateChrome() {
        background =
            if (editSelected) {
                GradientDrawable().apply {
                    cornerRadius = CORNER_RADIUS_DP * density
                    setColor(Color.TRANSPARENT)
                    setStroke((BORDER_WIDTH_DP * density).toInt(), theme.primary)
                }
            } else {
                null
            }

        val handleVisibility = if (editSelected) View.VISIBLE else View.GONE
        topHandle.visibility = handleVisibility
        bottomHandle.visibility = handleVisibility
    }

    private fun handle(gravity: Int): View {
        val view = View(context)
        val isTop = gravity == Gravity.TOP
        // `DragHandle`'s own call site in CommunalHub.kt never overrides its 8dp `dragHandleRadius`
        // default, so the real handle is a 16dp circle: a filled disc behind a ring the same
        // 2dp [BORDER_WIDTH_DP] as the selected-widget outline.
        val size = (HANDLE_DIAMETER_DP * density).toInt()
        view.layoutParams = LayoutParams(size, size, gravity or Gravity.CENTER_HORIZONTAL)
        view.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.tertiaryContainer)
                setStroke((BORDER_WIDTH_DP * density).toInt(), theme.onTertiaryContainer)
            }
        view.visibility = View.GONE
        attachDragBehaviour(view, isTop = isTop)
        return view
    }

    /**
     * Dragging a handle resizes by whole spans. A span is only committed once the drag passes half
     * a span, so the widget does not flicker between sizes mid-gesture.
     */
    private fun attachDragBehaviour(handle: View, isTop: Boolean) {
        var downY = 0f
        var committed = 0

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    committed = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Dragging the top handle up, or the bottom handle down, grows the widget.
                    val travel = if (isTop) downY - event.rawY else event.rawY - downY
                    val steps = (travel / rowPitchPx.coerceAtLeast(1f)).toInt()
                    if (steps != committed) {
                        onResize(steps - committed)
                        committed = steps
                    }
                    true
                }
                else -> true
            }
        }
    }

    companion object {
        /**
         * `android.R.dimen.system_app_widget_background_radius`, which `ResizableItemFrame`'s own
         * call site leaves at its default. Its un-flagged value is 28dp; a
         * `use_smaller_app_widget_system_radius` flag can drop it to 24dp, but that can't be read
         * from here, so this takes the baseline.
         */
        private const val CORNER_RADIUS_DP = 28f

        /** `ResizeFrameDimensions.BorderWidth`, also the selected-widget outline's own width. */
        private const val BORDER_WIDTH_DP = 2f

        /** Twice `DragHandle`'s own `dragHandleRadius` default (8dp), its call site's own value. */
        private const val HANDLE_DIAMETER_DP = 16f
    }
}
