/*
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.danascape.launcher3feed.core

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import java.io.PrintWriter

/**
 * Base for a launcher -1 screen. Subclasses supply their content through
 * [onCreateContentView] and may follow the launcher's lifecycle through [onStart], [onResume],
 * [onPause] and [onStop].
 *
 * Each instance owns one window, stacked over the launcher's own window with the launcher's
 * token. That window stays attached while the launcher is, but is untouchable and fully
 * transparent unless the panel is at least partly open.
 */
abstract class FeedOverlay @JvmOverloads constructor(
    context: Context,
    theme: Int = R.style.Theme_Launcher3Feed_Overlay,
    windowTheme: Int = R.style.Theme_Launcher3Feed_OverlayWindow,
) : ContextThemeWrapper(context, theme), Window.Callback {

    /**
     * The overlay window. A [Dialog] is only used to get a PhoneWindow; it is never shown, the
     * window's decor is added to the launcher's window token directly.
     */
    val window: Window =
        checkNotNull(Dialog(context, windowTheme).window).also {
            it.callback = this
            it.addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }

    /** Parent of the content view, the part of the panel that slides. */
    lateinit var container: FrameLayout
        private set

    private lateinit var panel: SlidingPanel
    private lateinit var windowManager: WindowManager
    private var decorView: View? = null
    private var callback: ILauncherOverlayCallback? = null

    private var panelState = PanelState.CLOSED
    private var activityState = 0

    private var replayingLauncherScroll = false
    private var launcherScrollDownTime = 0L
    private var launcherScrollX = 0

    private val backCallback by lazy { OnBackInvokedCallback { onBackPressed() } }
    private var backCallbackRegistered = false

    /** Whether the panel has settled fully open. */
    val isOpen: Boolean
        get() = panelState == PanelState.OPEN

    // ---- subclass hooks ----------------------------------------------------

    /** View shown in the panel. It is added to [container] by [onCreate]. */
    protected abstract fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup): View

    /** Called once the window exists. Subclasses that override it must call through first. */
    open fun onCreate(savedInstanceState: Bundle?) {
        container.addView(onCreateContentView(LayoutInflater.from(this), container))
    }

    open fun onStart() {}

    open fun onResume() {}

    open fun onPause() {}

    open fun onStop() {}

    open fun onDestroy() {}

    /** Back while the panel is showing. Closes it unless overridden. */
    open fun onBackPressed() {
        closePanel(CLOSE_DURATION_MS)
    }

    /** The panel moved; [progress] runs from 0 closed to 1 fully open. */
    open fun onScroll(progress: Float) {}

    open fun dump(prefix: String, writer: PrintWriter) {
        writer.println("${prefix}panelState=$panelState activityState=$activityState")
        if (::panel.isInitialized) {
            writer.println("${prefix}offset=${panel.offset} isPanelOpen=${panel.isPanelOpen}")
        }
    }

    // ---- driven by OverlaySession ------------------------------------------

    /**
     * Creates the window on top of the launcher window described by [params], whose token it
     * borrows.
     */
    internal fun attach(params: LayoutParams, callback: ILauncherOverlayCallback?, options: Bundle) {
        this.callback = callback
        window.setWindowManager(null, params.token, javaClass.name, true)
        windowManager = window.windowManager

        container = FrameLayout(this)
        panel = SlidingPanel(this, panelListener).apply { setContent(container) }

        params.apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
            type = LayoutParams.TYPE_DRAWN_APPLICATION
            gravity = Gravity.LEFT
            dimAmount = 0f
            // Hidden until the panel moves.
            alpha = 0f
            flags = (flags or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                LayoutParams.FLAG_SPLIT_TOUCH or UNTOUCHABLE_FLAGS) and
                LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            softInputMode = LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        window.attributes = params

        onCreate(options)
        window.setContentView(panel)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            panel.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        val decor = window.decorView
        windowManager.addView(decor, window.attributes)
        decorView = decor
    }

    internal fun destroy() {
        setActivityState(0)
        setBackCallbackRegistered(false)
        decorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: RuntimeException) {
                // The launcher window, and with it this one, can already be gone.
                Log.w(TAG, "Failed to remove overlay window", e)
            }
        }
        decorView = null
        callback = null
        onDestroy()
    }

    /** [state]: bit 0 started, bit 1 resumed. Resumed implies started. */
    internal fun setActivityState(state: Int) {
        val wasStarted = (activityState and STATE_STARTED) != 0
        val wasResumed = (activityState and STATE_RESUMED) != 0
        val resumed = (state and STATE_RESUMED) != 0
        val started = resumed || (state and STATE_STARTED) != 0
        activityState = (if (started) STATE_STARTED else 0) or (if (resumed) STATE_RESUMED else 0)

        if (!wasStarted && started) onStart()
        if (!wasResumed && resumed) onResume()
        if (wasResumed && !resumed) onPause()
        if (wasStarted && !started) onStop()
    }

    internal fun openPanel(animate: Boolean) {
        if (panelState == PanelState.CLOSED) {
            panel.open(if (animate) OPEN_DURATION_MS else 0)
        }
    }

    /** Closes the panel over [durationMs]; 0 closes it at once. */
    internal fun closePanel(durationMs: Int) {
        if (isOpen) panel.close(durationMs)
    }

    internal fun saveState(): Bundle =
        Bundle().apply {
            putBoolean(KEY_OPEN, isOpen)
            putBundle(KEY_VIEW_STATE, window.saveHierarchyState())
        }

    internal fun restoreState(state: Bundle) {
        state.getBundle(KEY_VIEW_STATE)?.let { window.restoreHierarchyState(it) }
        if (state.getBoolean(KEY_OPEN)) panel.restoreOpen()
    }

    // The launcher scrolling its workspace past the first page is replayed into the panel as a
    // drag, so a launcher swipe and a swipe on the panel itself feel the same.

    internal fun onLauncherScrollStart(eventTime: Long) {
        if (isOpen || panel.offset >= panel.touchSlop) return
        panel.setOffset(0)
        replayingLauncherScroll = true
        launcherScrollX = 0
        launcherScrollDownTime = eventTime - LAUNCHER_SCROLL_DOWN_LEAD_MS
        panel.forceDrag = true
        replayTouch(MotionEvent.ACTION_DOWN, launcherScrollDownTime)
        replayTouch(MotionEvent.ACTION_MOVE, eventTime)
    }

    internal fun onLauncherScroll(progress: Float, eventTime: Long) {
        if (!replayingLauncherScroll) return
        launcherScrollX = (progress * panel.measuredWidth).toInt()
        replayTouch(MotionEvent.ACTION_MOVE, eventTime)
    }

    internal fun onLauncherScrollEnd(eventTime: Long) {
        if (!replayingLauncherScroll) return
        replayTouch(MotionEvent.ACTION_UP, eventTime)
        replayingLauncherScroll = false
    }

    private fun replayTouch(action: Int, eventTime: Long) {
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = (if (rtl) -launcherScrollX else launcherScrollX).toFloat()
        val event = MotionEvent.obtain(launcherScrollDownTime, eventTime, action, x, 0f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        panel.dispatchTouchEvent(event)
        event.recycle()
    }

    // ---- panel state -------------------------------------------------------

    private val panelListener =
        object : SlidingPanel.Listener {
            override fun onPanelDragStarted() {
                setPanelState(PanelState.DRAGGING)
                setWindowAlpha(1f)
            }

            override fun onPanelOpening() {
                setPanelState(PanelState.DRAGGING)
                setTouchable(true)
                setWindowAlpha(1f)
            }

            override fun onPanelClosing() {
                setPanelState(PanelState.DRAGGING)
                setTouchable(false)
            }

            override fun onPanelOpened() {
                setPanelState(PanelState.OPEN)
            }

            override fun onPanelClosed() {
                setWindowAlpha(0f)
                setPanelState(PanelState.CLOSED)
            }

            override fun onPanelProgress(progress: Float) {
                try {
                    callback?.overlayScrollChanged(progress)
                } catch (e: RemoteException) {
                    // The launcher died; the service connection teardown follows.
                }
                onScroll(progress)
            }
        }

    private fun setPanelState(state: PanelState) {
        panelState = state
        setBackCallbackRegistered(state != PanelState.CLOSED)
    }

    private fun setTouchable(touchable: Boolean) {
        if (touchable) window.clearFlags(UNTOUCHABLE_FLAGS) else window.addFlags(UNTOUCHABLE_FLAGS)
    }

    private fun setWindowAlpha(alpha: Float) {
        val attrs = window.attributes
        if (attrs.alpha != alpha) {
            attrs.alpha = alpha
            window.attributes = attrs
        }
    }

    /**
     * With predictive back, which a current target SDK gets by default, back no longer arrives as
     * a key event and has to be taken through the window's dispatcher instead.
     */
    private fun setBackCallbackRegistered(register: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (register == backCallbackRegistered) return
        val dispatcher = decorView?.findOnBackInvokedDispatcher() ?: return
        if (register) {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback,
            )
        } else {
            dispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        backCallbackRegistered = register
    }

    // ---- context -----------------------------------------------------------

    override fun getSystemService(name: String): Any? {
        // Views and dialogs made from this context must use the window manager bound to the
        // launcher's token.
        if (name == WINDOW_SERVICE && ::windowManager.isInitialized) return windowManager
        return super.getSystemService(name)
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
    }

    // ---- Window.Callback ---------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressed()
            return true
        }
        return window.superDispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
        window.superDispatchKeyShortcutEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        window.superDispatchTouchEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        window.superDispatchTrackballEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        window.superDispatchGenericMotionEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean = false

    override fun onWindowAttributesChanged(attrs: LayoutParams) {
        decorView?.let { windowManager.updateViewLayout(it, attrs) }
    }

    override fun onCreatePanelView(featureId: Int): View? = null

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = false

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = true

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = true

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = false

    override fun onPanelClosed(featureId: Int, menu: Menu) {}

    override fun onContentChanged() {}

    override fun onWindowFocusChanged(hasFocus: Boolean) {}

    override fun onAttachedToWindow() {}

    override fun onDetachedFromWindow() {}

    override fun onSearchRequested(): Boolean = false

    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = false

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? = null

    override fun onWindowStartingActionMode(
        callback: ActionMode.Callback?,
        type: Int,
    ): ActionMode? = null

    override fun onActionModeStarted(mode: ActionMode?) {}

    override fun onActionModeFinished(mode: ActionMode?) {}

    private enum class PanelState {
        CLOSED,
        DRAGGING,
        OPEN,
    }

    private companion object {
        const val TAG = "FeedOverlay"

        const val STATE_STARTED = 1
        const val STATE_RESUMED = 2

        const val OPEN_DURATION_MS = 750
        const val CLOSE_DURATION_MS = 750

        /** Backdates the replayed down event so the first move already has a velocity. */
        const val LAUNCHER_SCROLL_DOWN_LEAD_MS = 30L

        const val UNTOUCHABLE_FLAGS =
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE

        const val KEY_OPEN = "open"
        const val KEY_VIEW_STATE = "view_state"
    }
}
