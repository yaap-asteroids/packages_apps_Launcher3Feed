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
 * [ResizeHandle], and dragging changes how many spans the widget takes. This is the same shape:
 * `ResizeableItemFrame` draws `ResizeHandle.TOP` and `ResizeHandle.BOTTOM` around the content.
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
        // The hub's handle is a 32dp pill inside a 48dp touch target, rounded on its outer edge
        // only: `resizeButtonShape` rounds the top for the top handle, the bottom for the bottom.
        view.layoutParams =
            LayoutParams(
                (HANDLE_CONTENT_SIZE_DP * density).toInt(),
                (HANDLE_TOUCH_SIZE_DP * density).toInt(),
                gravity or Gravity.CENTER_HORIZONTAL,
            )
        val r = HANDLE_TOUCH_SIZE_DP * density / 2f
        view.background =
            GradientDrawable().apply {
                cornerRadii =
                    if (isTop) {
                        floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    } else {
                        floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                    }
                setColor(theme.tertiaryContainer)
            }
        view.visibility = View.GONE
        attachDragBehaviour(view, isTop = gravity == Gravity.TOP)
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
        /** `communal_enforced_rounded_corner_max_radius`, the radius the hub enforces on cards. */
        private const val CORNER_RADIUS_DP = 28f

        /** `ResizeFrameDimensions.BorderWidth`. */
        private const val BORDER_WIDTH_DP = 2f

        /** `ResizeFrameDimensions.TouchTargetSize`. */
        private const val HANDLE_TOUCH_SIZE_DP = 48f

        /** `ResizeFrameDimensions.ResizeButtonContentSize`. */
        private const val HANDLE_CONTENT_SIZE_DP = 32f
    }
}
