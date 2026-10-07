/*
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.danascape.launcher3feed.core

import android.content.res.Configuration
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager.LayoutParams
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import java.io.PrintWriter

/**
 * One launcher's connection to the feed: the [ILauncherOverlay] it binds, and the [FeedOverlay]
 * shown over its window.
 *
 * Binder calls arrive on binder threads. Each one checks the caller and is replayed on the main
 * thread in arrival order; all other state belongs to the main thread.
 */
internal class OverlaySession(
    private val service: FeedOverlayService,
    private val clientUid: Int,
    private val clientPackage: String,
    val serverVersion: Int,
) : ILauncherOverlay.Stub() {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var destroyed = false

    private var overlay: FeedOverlay? = null
    private var callback: ILauncherOverlayCallback? = null
    private var activityState = 0

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Binder.getCallingUid() != clientUid) {
            throw SecurityException("uid ${Binder.getCallingUid()} is not the bound launcher")
        }
        mainHandler.post { if (!destroyed) block() }
    }

    // ---- ILauncherOverlay --------------------------------------------------

    override fun startScroll() {
        val time = SystemClock.uptimeMillis()
        onMain { overlay?.onLauncherScrollStart(time) }
    }

    override fun onScroll(progress: Float) {
        val time = SystemClock.uptimeMillis()
        onMain { overlay?.onLauncherScroll(progress, time) }
    }

    override fun endScroll() {
        val time = SystemClock.uptimeMillis()
        onMain { overlay?.onLauncherScrollEnd(time) }
    }

    override fun windowAttached(lp: LayoutParams?, cb: ILauncherOverlayCallback?, flags: Int) {
        val args = Bundle().apply {
            putParcelable(KEY_LAYOUT_PARAMS, lp)
            putInt(KEY_CLIENT_OPTIONS, flags)
        }
        onMain { attachWindow(args, cb) }
    }

    override fun windowAttached2(bundle: Bundle?, cb: ILauncherOverlayCallback?) {
        onMain { bundle?.let { attachWindow(it, cb) } }
    }

    override fun windowDetached(isChangingConfigurations: Boolean) {
        // Across a configuration change the overlay is kept, so the next attach can carry its
        // state over.
        onMain { if (!isChangingConfigurations) destroyOverlay() }
    }

    override fun openOverlay(flags: Int) {
        onMain { overlay?.openPanel(animate = (flags and FLAG_ANIMATE) != 0) }
    }

    override fun closeOverlay(flags: Int) {
        val durationMs =
            when {
                (flags and FLAG_ANIMATE) == 0 -> 0
                (flags shr 2) > 0 -> flags shr 2
                else -> DEFAULT_CLOSE_DURATION_MS
            }
        onMain { overlay?.closePanel(durationMs) }
    }

    override fun setActivityState(state: Int) {
        onMain { updateActivityState(state) }
    }

    override fun onPause() {
        onMain { updateActivityState(activityState and STATE_RESUMED.inv()) }
    }

    override fun onResume() {
        onMain { updateActivityState(STATE_STARTED or STATE_RESUMED) }
    }

    // Voice and search are not offered; answer the synchronous calls so the launcher falls back
    // to its own handling.

    override fun requestVoiceDetection(start: Boolean) {}

    override fun getVoiceSearchLanguage(): String? = null

    override fun isVoiceDetectionRunning(): Boolean = false

    override fun hasOverlayContent(): Boolean = true

    override fun unusedMethod() {}

    override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean = false

    // ---- main thread -------------------------------------------------------

    private fun attachWindow(args: Bundle, cb: ILauncherOverlayCallback?) {
        // A new attach replaces the current overlay, keeping its state across e.g. a rotation.
        val savedState = overlay?.saveState()
        destroyOverlay()
        callback = cb

        @Suppress("DEPRECATION")
        val params = args.getParcelable<LayoutParams>(KEY_LAYOUT_PARAMS)
        val feedEnabled =
            (args.getInt(KEY_CLIENT_OPTIONS, OPTION_FEED_ENABLED) and OPTION_FEED_ENABLED) != 0
        if (params == null || !feedEnabled) {
            notifyStatus(attached = false)
            return
        }

        @Suppress("DEPRECATION")
        val configuration = args.getParcelable<Configuration>(KEY_CONFIGURATION)
        val context = configuration?.let(service::createConfigurationContext) ?: service
        val created = service.createOverlay(context)
        try {
            created.attach(params, cb, args)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to attach the overlay for $clientPackage", e)
            created.destroy()
            notifyStatus(attached = false)
            return
        }
        overlay = created
        savedState?.let(created::restoreState)
        created.setActivityState(activityState)
        notifyStatus(attached = true)
    }

    private fun updateActivityState(state: Int) {
        activityState = state
        overlay?.setActivityState(state)
    }

    private fun destroyOverlay() {
        overlay?.destroy()
        overlay = null
    }

    private fun notifyStatus(attached: Boolean) {
        try {
            callback?.overlayStatusChanged(
                STATUS_PERSISTENT_FLAGS or (if (attached) STATUS_ATTACHED else 0)
            )
        } catch (e: RemoteException) {
            // The launcher died; the service connection teardown follows.
        }
    }

    /** Tears the session down for good. Main thread. */
    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        destroyOverlay()
        callback = null
    }

    fun dump(prefix: String, writer: PrintWriter) {
        writer.println("${prefix}$clientPackage uid=$clientUid v=$serverVersion state=$activityState")
        overlay?.dump("$prefix  ", writer)
    }

    private companion object {
        const val TAG = "OverlaySession"

        const val KEY_LAYOUT_PARAMS = "layout_params"
        const val KEY_CONFIGURATION = "configuration"
        const val KEY_CLIENT_OPTIONS = "client_options"

        const val OPTION_FEED_ENABLED = 1
        const val FLAG_ANIMATE = 1

        const val STATE_STARTED = 1
        const val STATE_RESUMED = 2

        const val DEFAULT_CLOSE_DURATION_MS = 750

        /** Overlay attached, reported as Google's server does with both low bits. */
        const val STATUS_ATTACHED = 0b11

        /** Server flags the launcher stores; Google's server always reports these two. */
        const val STATUS_PERSISTENT_FLAGS = 0b11000
    }
}
