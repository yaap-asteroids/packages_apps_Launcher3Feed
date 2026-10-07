/*
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.danascape.launcher3feed.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.util.SparseArray
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * The service a launcher binds to show the -1 screen. Apps extend it and return their
 * [FeedOverlay] from [createOverlay].
 *
 * The launcher binds with `app://<package>:<uid>?v=<version>`. The uid is checked against the
 * package here, and against the actual binder caller on every call, so one launcher can never
 * drive another's session.
 */
abstract class FeedOverlayService : Service() {

    private val sessions = SparseArray<OverlaySession>()

    /** A new overlay, built on [context] so it carries the launcher's configuration. */
    abstract fun createOverlay(context: Context): FeedOverlay

    override fun onBind(intent: Intent): IBinder? {
        val uri = intent.data ?: return null
        val uid = uri.port
        val pkg = uri.host
        if (uid == -1 || pkg == null) return null
        if (packageManager.getPackagesForUid(uid)?.contains(pkg) != true) {
            Log.w(TAG, "Rejecting bind: $pkg does not belong to uid $uid")
            return null
        }
        val version = uri.getQueryParameter("v")?.toIntOrNull() ?: Int.MAX_VALUE

        sessions[uid]?.let {
            if (it.serverVersion == version) return it
            it.destroy()
        }
        return OverlaySession(this, uid, pkg, version).also { sessions.put(uid, it) }
    }

    override fun onUnbind(intent: Intent): Boolean {
        val uid = intent.data?.port ?: return false
        sessions[uid]?.destroy()
        sessions.remove(uid)
        return false
    }

    override fun onDestroy() {
        for (i in 0 until sessions.size()) sessions.valueAt(i).destroy()
        sessions.clear()
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Sessions: ${sessions.size()}")
        for (i in 0 until sessions.size()) sessions.valueAt(i).dump("  ", writer)
    }

    private companion object {
        const val TAG = "FeedOverlayService"
    }
}
