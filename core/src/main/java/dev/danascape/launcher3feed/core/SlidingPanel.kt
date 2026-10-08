/*
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.danascape.launcher3feed.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The draggable -1 panel.
 *
 * [content] is laid out one screen width off the leading edge and slid in by translating it, so
 * an offset of 0 is closed and an offset of [getWidth] is fully open. The panel is dragged either
 * by real touches once it is open, or by the launcher's workspace scroll, which [FeedOverlay]
 * replays here as synthetic touches so both paths share the same fling and settle physics.
 */
@SuppressLint("ViewConstructor")
internal class SlidingPanel(context: Context, private val listener: Listener) :
    FrameLayout(context) {

    interface Listener {
        /** A drag started, by a finger or by the replayed launcher scroll. */
        fun onPanelDragStarted()

        /** The panel started animating, or jumped, open. */
        fun onPanelOpening()

        /** The panel started animating closed. */
        fun onPanelClosing()

        fun onPanelOpened()

        fun onPanelClosed()

        /** [progress] runs from 0 closed to 1 fully open. */
        fun onPanelProgress(progress: Float)
    }

    private val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val touchSlop: Int
    private val maxFlingVelocity: Int
    private val flingThresholdVelocity: Int
    private val minSnapVelocity: Int

    private var content: View? = null

    /** How far the panel is open, kept across re-measures so a resize keeps the position. */
    private var progress = 0f
    private var offsetPx = 0

    private var animator: ValueAnimator? = null
    private var animatorTarget = 0
    private var settling = false

    /** True while a finger (or the replayed launcher scroll) is dragging the panel. */
    private var dragging = false

    /** Makes the next ACTION_DOWN start a drag without waiting for touch slop. */
    var forceDrag = false

    /** True once the panel has settled fully open. */
    var isPanelOpen = false
        private set

    /**
     * True from the start of a drag or settle until the panel rests. While moving, the content is
     * drawn into a hardware layer, so each frame only moves a texture instead of redrawing every
     * widget.
     */
    private var isMoving = false
        set(value) {
            if (field == value) return
            field = value
            content?.setLayerType(if (value) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)
        }

    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = INVALID_POINTER
    private var downX = 0f
    private var downY = 0f
    private var lastMotionX = 0f
    private var totalMotionX = 0f
    private var dragStartX = 0f
    private var dragStartOffset = 0

    init {
        val config = ViewConfiguration.get(context)
        val density = resources.displayMetrics.density
        touchSlop = config.scaledPagingTouchSlop
        maxFlingVelocity = config.scaledMaximumFlingVelocity
        flingThresholdVelocity = (FLING_THRESHOLD_VELOCITY_DP * density).toInt()
        minSnapVelocity = (MIN_SNAP_VELOCITY_DP * density).toInt()
    }

    /** Current offset in pixels, 0 when closed. */
    val offset: Int
        get() = offsetPx

    fun setContent(view: View) {
        content?.let { removeView(it) }
        content = view
        addView(view)
    }

    /**
     * Animates the panel fully open. A [durationMs] of 0 jumps there.
     */
    fun open(durationMs: Int) {
        isMoving = true
        listener.onPanelOpening()
        settleTo(width, durationMs)
    }

    /**
     * Animates the panel closed, in at most [MAX_CLOSE_DURATION_MS]. A [durationMs] of 0 jumps
     * there.
     */
    fun close(durationMs: Int) {
        isMoving = true
        listener.onPanelClosing()
        settleTo(0, min(durationMs, MAX_CLOSE_DURATION_MS))
    }

    /** Puts the panel fully open without animating, as when restoring after a re-attach. */
    fun restoreOpen() {
        progress = 1f
        isMoving = true
        listener.onPanelOpening()
        setOffset(width)
        onFullyOpened()
    }

    private fun settleTo(target: Int, durationMs: Int) {
        cancelAnimation()
        settling = true
        animatorTarget = target
        if (durationMs <= 0) {
            onSettled()
            return
        }
        animator = ValueAnimator.ofInt(offsetPx, target).apply {
            duration = durationMs.toLong()
            interpolator = EASE_OUT_QUINT
            addUpdateListener { setOffset(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    onSettled()
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        animator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
    }

    private fun onSettled() {
        setOffset(animatorTarget)
        if (!settling) return
        settling = false
        when (offsetPx) {
            0 -> {
                isPanelOpen = false
                isMoving = false
                listener.onPanelClosed()
            }
            width -> onFullyOpened()
        }
    }

    private fun onFullyOpened() {
        isPanelOpen = true
        isMoving = false
        listener.onPanelOpened()
    }

    fun setOffset(px: Int) {
        val width = width
        if (width <= 0) return
        val clamped = if (px <= 1) 0 else min(px, width)
        if (clamped == offsetPx && progress == clamped.toFloat() / width) return
        offsetPx = clamped
        progress = offsetPx.toFloat() / width
        content?.translationX = (if (isRtl) -offsetPx else offsetPx).toFloat()
        listener.onPanelProgress(progress)
    }

    // ---- touch -------------------------------------------------------------

    private fun beginDrag() {
        dragging = true
        settling = false
        cancelAnimation()
        isMoving = true
        listener.onPanelDragStarted()
    }

    private fun endTouch() {
        velocityTracker?.recycle()
        velocityTracker = null
        forceDrag = false
        dragging = false
        activePointerId = INVALID_POINTER
    }

    private fun onDown(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        lastMotionX = downX
        totalMotionX = 0f
        dragStartOffset = offsetPx
        activePointerId = ev.getPointerId(0)
        // Catch a panel that is still settling, unless it has all but arrived.
        val nearlySettled =
            animator == null || abs(animatorTarget - offsetPx) < touchSlop / 3
        if (!nearlySettled || forceDrag) {
            forceDrag = false
            beginDrag()
            dragStartX = downX
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (content == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(ev)
            MotionEvent.ACTION_MOVE -> if (!dragging) maybeStartDrag(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTouch()
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (content == null) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!dragging) onDown(ev)
            MotionEvent.ACTION_MOVE ->
                if (dragging) {
                    val index = ev.findPointerIndex(activePointerId)
                    if (index != -1) {
                        val x = ev.getX(index)
                        totalMotionX += abs(x - lastMotionX)
                        lastMotionX = x
                        val delta = x - dragStartX
                        setOffset(dragStartOffset + (if (isRtl) -delta else delta).toInt())
                    }
                } else {
                    maybeStartDrag(ev)
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settleAfterDrag()
                endTouch()
            }
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
        }
        return true
    }

    /**
     * Starts a drag once a horizontal swipe passes touch slop. Swipes further into an open panel
     * are left to its content, and steep swipes need more travel, so vertical scrolling in the
     * content keeps working.
     */
    private fun maybeStartDrag(ev: MotionEvent) {
        val index = ev.findPointerIndex(activePointerId)
        if (index == -1) return
        val x = ev.getX(index)
        val dx = x - downX
        val absX = abs(dx)
        if (absX == 0f) return

        val towardsOpen = if (isRtl) dx < 0f else dx > 0f
        if (isPanelOpen && !isMoving && towardsOpen) return

        val angle = atan(abs(ev.getY(index) - downY) / absX)
        if (angle > MAX_DRAG_ANGLE) return
        val slopScale =
            if (angle > SLOP_SCALE_START_ANGLE) {
                sqrt((angle - SLOP_SCALE_START_ANGLE) / SLOP_SCALE_START_ANGLE) * 4f + 1f
            } else {
                1f
            }

        if (absX > (touchSlop * slopScale).roundToInt()) {
            totalMotionX += abs(lastMotionX - x)
            lastMotionX = x
            dragStartX = x
            dragStartOffset = offsetPx
            beginDrag()
        }
    }

    private fun settleAfterDrag() {
        val width = width
        val tracker = velocityTracker
        var velocity = 0
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
            velocity = tracker.getXVelocity(activePointerId).toInt()
        }
        if (isRtl) velocity = -velocity

        if (totalMotionX > MIN_FLING_DISTANCE_PX && abs(velocity) > flingThresholdVelocity) {
            // Same snap timing as the launcher workspace: the duration follows the fling speed
            // over the remaining distance, eased so short flings do not look abrupt.
            val remaining = if (velocity < 0) offsetPx else width - offsetPx
            val fraction = min(1f, remaining.toFloat() / width)
            val half = width / 2f
            val distance = half + sin((fraction - 0.5f) * SNAP_DISTANCE_INFLUENCE) * half
            val duration =
                (abs(distance / max(minSnapVelocity, abs(velocity))) * 1000f).roundToInt() * 4
            if (velocity > 0) open(duration) else close(duration)
        } else if (offsetPx >= width / 2) {
            open(SNAP_DURATION_MS)
        } else {
            close(SNAP_DURATION_MS)
        }
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val index = ev.actionIndex
        if (ev.getPointerId(index) != activePointerId) return
        val newIndex = if (index == 0) 1 else 0
        val x = ev.getX(newIndex)
        dragStartX += x - lastMotionX
        downX = x
        lastMotionX = x
        activePointerId = ev.getPointerId(newIndex)
        velocityTracker?.clear()
    }

    // ---- layout ------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        content?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setOffset((w * progress).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val view = content ?: return
        val width = view.measuredWidth
        val left = if (isRtl) width else -width
        view.layout(left, 0, left + width, view.measuredHeight)
    }

    private companion object {
        const val INVALID_POINTER = -1

        const val FLING_THRESHOLD_VELOCITY_DP = 300
        const val MIN_SNAP_VELOCITY_DP = 1000
        const val MIN_FLING_DISTANCE_PX = 25
        const val SNAP_DURATION_MS = 400
        const val MAX_CLOSE_DURATION_MS = 300

        /** Launcher3's distanceInfluenceForSnapDuration factor, 0.3 * PI / 2. */
        const val SNAP_DISTANCE_INFLUENCE = 0.47123889f

        /** Swipes steeper than this never drag the panel. */
        val MAX_DRAG_ANGLE = Math.toRadians(60.0).toFloat()

        /** Above this angle the slop grows, up to 5x at [MAX_DRAG_ANGLE]. */
        val SLOP_SCALE_START_ANGLE = Math.toRadians(30.0).toFloat()

        val EASE_OUT_QUINT = Interpolator { input ->
            val t = input - 1f
            t * t * t * t * t + 1f
        }
    }
}
