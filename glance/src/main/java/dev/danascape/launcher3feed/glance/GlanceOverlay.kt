package dev.danascape.launcher3feed.glance

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import dev.danascape.launcher3feed.core.FeedOverlay

/**
 * A -1 panel that shows the same widgets as the Glanceable Hub on the lock screen, and edits the
 * same hub.
 *
 * SystemUI hosts the widgets and streams their [RemoteViews] here, so a widget shown here is the
 * same instance the lock screen shows. Adding, removing or resizing here changes the lock screen
 * too — there is only one hub.
 *
 * Edit mode follows the hub's: long press to enter, which dims the panel and raises a toolbar of
 * "Add widget" and "Done"; tapping a widget selects it, turning the leading button into "Remove"
 * and outlining the widget with drag handles to resize it.
 */
class GlanceOverlay(private val context: Context) :
    FeedOverlay(context), GlanceableHubWidgetClient.Callback {

    private val client = GlanceableHubWidgetClient(context)
    private val hubMetrics = HubMetrics.from(context)

    /** One frame per app widget id, reused across hub updates so widgets do not flicker. */
    private val widgetFrames = mutableMapOf<Int, GlanceWidgetFrame>()
    private val widgetViews = mutableMapOf<Int, GlanceWidgetHostView>()

    /** Latest hub state; resize resends the whole ranking, so the order is needed too. */
    private var widgets: List<CommunalWidgetContentModel.Available> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var widgetColumn: LinearLayout
    private lateinit var messageView: TextView
    private lateinit var toolbar: GlanceEditToolbar

    private var editMode = false
    private var selectedId: Int? = null

    /**
     * Widget content that arrived while the launcher was not visible, newest per widget. Applying
     * RemoteViews inflates views, so it waits until the panel can be seen instead of running for
     * every clock tick or media update while another app is in front.
     */
    private val deferredViews = mutableMapOf<Int, RemoteViews?>()
    private var started = false

    /** Ids and spans last laid out; an update that changes neither leaves the views alone. */
    private var laidOut: List<Pair<Int, Int>> = emptyList()

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val pad = hubMetrics.itemSpacingPx(density)

        widgetColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipToPadding = false
                // Long pressing empty panel space enters edit mode, as on the hub. This lives on
                // the column rather than the root because the ScrollView sits on top of the root
                // and would swallow the gesture before it ever arrived.
                isLongClickable = true
                setOnLongClickListener {
                    setEditMode(true)
                    true
                }
            }

        messageView =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_SP)
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                setText(R.string.glance_unavailable)
            }
        widgetColumn.addView(messageView)

        toolbar =
            GlanceEditToolbar(
                    context,
                    onAddWidget = ::openWidgetPicker,
                    onRemoveWidget = ::removeSelected,
                    onDone = { setEditMode(false) },
                )
                .apply { visibility = View.GONE }

        root =
            FrameLayout(context).apply {
                addView(
                    ScrollView(context).apply {
                        isFillViewport = true
                        clipToPadding = false
                        addView(widgetColumn)
                    }
                )
                addView(
                    toolbar,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP,
                    ),
                )
            }

        // The overlay window draws behind the system bars, so without this the first widget sits
        // under the status bar icons. Real insets are preferred; the status bar height is only a
        // fallback for when this window never gets an inset dispatch.
        applyBarPadding(fallbackStatusBarHeight(), 0)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            applyBarPadding(bars.top, bars.bottom)
            insets
        }
        root.requestApplyInsets()
        root.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB ->
            // Cell height comes from the space actually available, so a height change has to
            // re-run the layout.
            if (b - t != oldB - oldT) layoutWidgets()
        }

        return root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // FeedOverlay builds the content view here, so the column exists before the first
        // widget update can arrive.
        super.onCreate(savedInstanceState)
        client.connect(this)
    }

    override fun onStart() {
        started = true
        deferredViews.forEach { (id, views) -> widgetViews[id]?.updateAppWidget(views) }
        deferredViews.clear()
    }

    override fun onStop() {
        started = false
    }

    override fun onDestroy() {
        client.disconnect()
        widgetFrames.clear()
        widgetViews.clear()
        deferredViews.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (editMode) {
            setEditMode(false)
            return
        }
        super.onBackPressed()
    }

    // ---- insets ------------------------------------------------------------

    private var barTop = 0
    private var barBottom = 0

    private fun applyBarPadding(top: Int, bottom: Int) {
        barTop = top
        barBottom = bottom
        layoutColumnPadding()
    }

    /**
     * Matches `gridContentPadding`: in edit mode the toolbar's own top inset (here, [barTop] in
     * place of its cutout-derived one), its height and one more gap sit above the first widget,
     * with just one gap below the last. Outside edit mode the hub bakes in the average of those
     * two instead of removing them, so a widget's height only changes by half as much when edit
     * mode toggles, rather than jumping the toolbar's full height.
     *
     * [barBottom] has no equivalent in that formula — the hub's own root handles nav bar
     * clearance outside of it — and is added on top of the result as this window's own, in both
     * modes.
     */
    private fun layoutColumnPadding() {
        val density = context.resources.displayMetrics.density
        val pad = hubMetrics.itemSpacingPx(density)
        val toolbarHeightPx = (HubMetrics.TOOLBAR_HEIGHT_DP * density).toInt()

        val editTop = barTop + toolbarHeightPx + pad
        val editBottom = pad
        val top = if (editMode) editTop else (editTop + editBottom) / 2
        val bottom = if (editMode) editBottom else (editTop + editBottom) / 2

        widgetColumn.setPadding(pad, top, pad, bottom + barBottom)
        toolbar.setPadding(toolbar.paddingLeft, barTop, toolbar.paddingRight, 0)
    }

    private fun fallbackStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    // ---- edit mode ---------------------------------------------------------

    private fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) selectedId = null

        toolbar.visibility = if (enabled) View.VISIBLE else View.GONE
        toolbar.removeEnabled = false
        applyEditBackground(enabled)

        widgetFrames.values.forEach { it.editMode = enabled }
        layoutColumnPadding()
    }

    /**
     * The hub is transparent over the wallpaper in both normal and edit mode — its root Box paints
     * nothing and `Theme.EditWidgetsActivity` sets a transparent window background. What separates
     * edit mode is a window blur behind it, not a scrim, so this does the same and only falls back
     * to a light dim where blur is unsupported.
     */
    private fun applyEditBackground(editing: Boolean) {
        val blurSupported =
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

        if (blurSupported) {
            window.setBackgroundBlurRadius(if (editing) editBlurRadiusPx() else 0)
            root.setBackgroundColor(Color.TRANSPARENT)
        } else {
            // No blur on this device, so fall back to a light dim rather than nothing.
            root.setBackgroundColor(if (editing) EDIT_DIM_COLOR else Color.TRANSPARENT)
        }
    }

    /** SystemUI's `max_window_blur_radius`, the blur the hub transitions to. */
    private fun editBlurRadiusPx(): Int {
        val id =
            context.resources.getIdentifier("max_window_blur_radius", "dimen", "com.android.systemui")
        return if (id > 0) {
            context.resources.getDimensionPixelSize(id)
        } else {
            DEFAULT_EDIT_BLUR_PX
        }
    }

    private fun selectWidget(appWidgetId: Int) {
        selectedId = if (selectedId == appWidgetId) null else appWidgetId
        widgetFrames.forEach { (id, frame) -> frame.editSelected = id == selectedId }
        toolbar.removeEnabled = selectedId != null
    }

    private fun removeSelected() {
        val id = selectedId ?: return
        selectedId = null
        toolbar.removeEnabled = false
        client.deleteWidget(id)
        // The hub pushes a fresh list, which is what actually re-renders the panel.
    }

    private fun openWidgetPicker() {
        // The system widget picker, the same one the hub's edit mode opens. It runs in its own
        // Activity because the overlay panel is a plain window and cannot take an Activity result.
        context.startActivity(
            Intent(context, GlanceWidgetPickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun resizeWidget(appWidgetId: Int, spanDelta: Int) {
        val current = widgets.firstOrNull { it.appWidgetId == appWidgetId } ?: return
        val rows = HubMetrics.rowsFor(widgets.map { it.spanY })
        // rowsFor only ever answers RESPONSIVE_ROWS or FIXED_ROWS, so this tells the two scales
        // apart the same way it already does for layout.
        val range =
            if (rows == HubMetrics.RESPONSIVE_ROWS) {
                RESPONSIVE_MIN_SPAN..rows
            } else {
                FIXED_MIN_SPAN..FIXED_MAX_SPAN
            }
        val target = (current.spanY + spanDelta).coerceIn(range)
        if (target == current.spanY) return
        client.resizeWidget(appWidgetId, target, widgets.map { it.appWidgetId })
    }

    // ---- hub updates -------------------------------------------------------

    override fun onAvailabilityChanged(available: Boolean) {
        if (!available) {
            widgetFrames.clear()
            widgetViews.clear()
            deferredViews.clear()
            widgets = emptyList()
            laidOut = emptyList()
            widgetColumn.removeAllViews()
            showMessage(R.string.glance_unavailable)
        }
    }

    override fun onWidgetsUpdated(updated: List<CommunalWidgetContentModel>) {
        // Only widgets that are ready can be rendered; Pending ones are still installing.
        widgets =
            updated.filterIsInstance<CommunalWidgetContentModel.Available>().sortedBy { it.rank }

        val liveIds = widgets.map { it.appWidgetId }.toSet()
        widgetFrames.keys.retainAll(liveIds)
        widgetViews.keys.retainAll(liveIds)
        deferredViews.keys.retainAll(liveIds)
        if (selectedId !in liveIds) selectedId = null

        if (widgets.isEmpty()) {
            laidOut = emptyList()
            widgetColumn.removeAllViews()
            showMessage(R.string.glance_empty)
            toolbar.removeEnabled = false
            return
        }

        // The hub resends the whole list on any change; re-adding every view would detach and
        // re-lay out all widgets for nothing.
        if (widgets.map { it.appWidgetId to it.spanY } != laidOut) layoutWidgets()
        toolbar.removeEnabled = selectedId != null
    }

    /**
     * Lays the widgets out at hub proportions.
     *
     * The hub divides the height it is given into [HubMetrics.RESPONSIVE_ROWS] rows and a widget
     * takes `spanY` of them, so this needs the panel's real height and has to re-run when it
     * changes.
     */
    private fun layoutWidgets() {
        if (widgets.isEmpty()) return

        val density = context.resources.displayMetrics.density
        val gap = hubMetrics.itemSpacingPx(density)
        val available =
            root.height - widgetColumn.paddingTop - widgetColumn.paddingBottom
        if (available <= 0) return

        val rows = HubMetrics.rowsFor(widgets.map { it.spanY })
        val rowPitch = hubMetrics.rowPitchPx(rows, available, density).toFloat()
        widgetColumn.removeAllViews()
        laidOut = widgets.map { it.appWidgetId to it.spanY }

        widgets.forEach { widget ->
            val frame = widgetFrames.getOrPut(widget.appWidgetId) { newFrame(widget) }
            frame.editMode = editMode
            frame.editSelected = widget.appWidgetId == selectedId
            frame.rowPitchPx = rowPitch
            (frame.parent as? ViewGroup)?.removeView(frame)
            frame.layoutParams =
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        hubMetrics.widgetHeightPx(widget.spanY, rows, available, density),
                    )
                    .apply { topMargin = gap }
            widgetColumn.addView(frame)
        }

        toolbar.removeEnabled = selectedId != null
    }

    override fun onWidgetViewsUpdated(appWidgetId: Int, views: RemoteViews?) {
        if (!started) {
            deferredViews[appWidgetId] = views
            return
        }
        val hostView = widgetViews[appWidgetId]
        if (hostView == null) {
            // Content arrived before the hub told us about the widget; the next list update
            // creates the view and SystemUI re-sends.
            Log.d(TAG, "No host view for $appWidgetId yet")
            return
        }
        hostView.updateAppWidget(views)
    }

    override fun onProviderInfoUpdated(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        widgetViews[appWidgetId]?.setAppWidget(appWidgetId, info)
    }

    // ---- helpers -----------------------------------------------------------

    private fun newFrame(widget: CommunalWidgetContentModel.Available): GlanceWidgetFrame {
        val hostView =
            GlanceWidgetHostView(context).apply {
                // SystemUI owns this id. Telling the view about it is what makes RemoteViews
                // inflate and clicks resolve against the right provider.
                setAppWidget(widget.appWidgetId, widget.providerInfo)
            }
        widgetViews[widget.appWidgetId] = hostView

        return GlanceWidgetFrame(
                context,
                appWidgetId = widget.appWidgetId,
                onSelected = { selectWidget(widget.appWidgetId) },
                onResize = { delta -> resizeWidget(widget.appWidgetId, delta) },
                onLongPress = {
                    setEditMode(true)
                    selectWidget(widget.appWidgetId)
                },
            )
            .apply {
                addView(
                    hostView,
                    0,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
    }

    private fun showMessage(textRes: Int) {
        messageView.setText(textRes)
        (messageView.parent as? ViewGroup)?.removeView(messageView)
        widgetColumn.addView(messageView)
    }

    companion object {
        private const val TAG = "GlanceOverlay"
        private const val MESSAGE_SP = 16f

        /** The responsive grid's smallest widget is one row; its largest fills every row. */
        private const val RESPONSIVE_MIN_SPAN = 1

        /**
         * `CommunalContentSize.FixedSize`: THIRD is the smallest, FULL the largest. The fixed grid
         * is deprecated and not what a current build lays out with (see [HubMetrics]); this range
         * is only a fallback should widgets ever arrive on that scale. It only bounds the result,
         * so a drag can still land on a span the fixed grid has no named size for (4 or 5); the
         * hub does not expose which exact sizes that scale allows from here.
         */
        private const val FIXED_MIN_SPAN = 2
        private const val FIXED_MAX_SPAN = 6

        /** Used only where cross-window blur is unavailable. */
        private const val EDIT_DIM_COLOR = 0x66000000

        /** Fallback for SystemUI's max_window_blur_radius (23px). */
        private const val DEFAULT_EDIT_BLUR_PX = 23
    }
}
