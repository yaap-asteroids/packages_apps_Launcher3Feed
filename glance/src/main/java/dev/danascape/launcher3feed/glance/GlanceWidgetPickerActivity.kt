package dev.danascape.launcher3feed.glance

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.widget.RemoteViews
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel

/**
 * Opens the system widget picker and adds whatever the user chooses to the Glanceable Hub.
 *
 * This is the same picker the hub's own edit mode opens: an `ACTION_PICK` against the launcher,
 * with the extras `CommunalEditModeViewModel` uses, so the list, the filtering and the
 * configuration flow are the real ones rather than something reimplemented here.
 *
 * It exists as an Activity because the picker returns a result, and because widgets with a
 * configuration activity need an Activity to run their `IntentSender` from. The overlay panel is a
 * plain window, so it starts this instead of picking widgets itself.
 */
class GlanceWidgetPickerActivity : Activity(), GlanceableHubWidgetClient.Callback {

    private lateinit var client: GlanceableHubWidgetClient

    /** Rank to add at, i.e. the end of the hub. Learned from the first widget update. */
    private var widgetCount = 0
    private var pending: Pair<ComponentName, UserHandle>? = null

    /** Set while a widget's configuration activity is up. */
    private var configurationResult: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = GlanceableHubWidgetClient(this)
        client.configurationHost = ::runWidgetConfiguration
        client.connect(this)

        if (savedInstanceState == null) {
            launchPicker()
        }
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    private fun launchPicker() {
        val launcher = resolveLauncherPackage()
        if (launcher == null) {
            Log.e(TAG, "No home activity to host the widget picker")
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val intent =
            Intent(Intent.ACTION_PICK).apply {
                setPackage(launcher)
                // CommunalEditModeViewModel sends these as resolved pixels
                // (resources.getDimensionPixelSize), not raw dp; Trebuchet does not read this pair
                // today, but a picker that does should get what it actually asks for.
                putExtra(EXTRA_DESIRED_WIDGET_WIDTH, (PICKER_WIDGET_WIDTH_DP * density).toInt())
                putExtra(EXTRA_DESIRED_WIDGET_HEIGHT, (PICKER_WIDGET_HEIGHT_DP * density).toInt())
                putExtra(AppWidgetManager.EXTRA_CATEGORY_FILTER, INCLUDED_CATEGORIES)
                putExtra(EXTRA_CATEGORY_EXCLUSION_FILTER, EXCLUDED_CATEGORIES)
                putExtra(EXTRA_UI_SURFACE_KEY, EXTRA_UI_SURFACE_VALUE)
                putExtra(EXTRA_PICKER_TITLE, getString(R.string.glance_add_widget))
            }

        try {
            startActivityForResult(intent, REQUEST_PICK_WIDGET)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the widget picker", e)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CONFIGURE) {
            val report = configurationResult
            configurationResult = null
            report?.invoke(resultCode == RESULT_OK)
            finish()
            return
        }

        if (requestCode != REQUEST_PICK_WIDGET) return

        if (resultCode != RESULT_OK || data == null) {
            finish()
            return
        }

        val component =
            data.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        val user = data.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)

        if (component == null || user == null) {
            Log.w(TAG, "Picker returned no widget")
            finish()
            return
        }

        // The hub may not have reported its widgets yet; hold the choice until it does.
        pending = component to user
        addPendingIfReady()
    }

    private fun addPendingIfReady() {
        val (component, user) = pending ?: return
        pending = null
        // If the widget needs configuring, SystemUI calls back into runWidgetConfiguration
        // before it commits, so this cannot finish yet.
        client.addWidget(component, user, widgetCount)
        finishWhenIdle()
    }

    /**
     * Runs a widget's configuration activity and reports the outcome back to SystemUI, which
     * commits or rolls back the widget on the strength of it.
     */
    private fun runWidgetConfiguration(appWidgetId: Int, onResult: (Boolean) -> Unit) {
        val sender = client.configureIntentSender(appWidgetId)
        if (sender == null) {
            onResult(false)
            return
        }
        configurationResult = onResult
        try {
            startIntentSenderForResult(sender, REQUEST_CONFIGURE, null, 0, 0, 0, null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start configuration for $appWidgetId", e)
            configurationResult = null
            onResult(false)
        }
    }

    /**
     * Closes once nothing is waiting on a configuration round trip.
     *
     * `addWidget` is one-way, so SystemUI's request to configure arrives after it returns.
     * This waits briefly for that to land rather than closing out from under it; if the
     * configuration does start, the activity stays up until its result comes back instead.
     */
    private fun finishWhenIdle() {
        window.decorView.postDelayed(
            { if (configurationResult == null) finish() },
            CONFIGURE_GRACE_MS,
        )
    }

    private fun resolveLauncherPackage(): String? =
        packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )
            ?.activityInfo
            ?.packageName

    // ---- hub updates -------------------------------------------------------

    override fun onWidgetsUpdated(widgets: List<CommunalWidgetContentModel>) {
        // New widgets go on the end, so the current count is the rank to add at.
        widgetCount = widgets.size
        addPendingIfReady()
    }

    override fun onWidgetViewsUpdated(appWidgetId: Int, views: RemoteViews?) = Unit

    override fun onProviderInfoUpdated(appWidgetId: Int, info: AppWidgetProviderInfo?) = Unit

    override fun onAvailabilityChanged(available: Boolean) {
        if (!available) {
            Log.w(TAG, "Glanceable Hub unavailable; cannot add a widget")
            finish()
        }
    }

    companion object {
        private const val TAG = "GlanceWidgetPicker"
        private const val REQUEST_PICK_WIDGET = 1
        private const val REQUEST_CONFIGURE = 2

        /** How long to wait for SystemUI to ask for configuration before closing. */
        private const val CONFIGURE_GRACE_MS = 1500L

        // Extras understood by the launcher's widget picker. These mirror
        // CommunalEditModeViewModel so this opens the same picker the hub does.
        private const val EXTRA_DESIRED_WIDGET_WIDTH = "desired_widget_width"
        private const val EXTRA_DESIRED_WIDGET_HEIGHT = "desired_widget_height"
        private const val EXTRA_CATEGORY_EXCLUSION_FILTER = "category_exclusion_filter"
        private const val EXTRA_PICKER_TITLE = "picker_title"
        private const val EXTRA_UI_SURFACE_KEY = "ui_surface"
        private const val EXTRA_UI_SURFACE_VALUE = "widgets_hub"

        /** SystemUI's communal_widget_picker_desired_width/height. */
        private const val PICKER_WIDGET_WIDTH_DP = 360f
        private const val PICKER_WIDGET_HEIGHT_DP = 240f

        private val INCLUDED_CATEGORIES =
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN or
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
        private val EXCLUDED_CATEGORIES =
            AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD.inv()
    }
}
