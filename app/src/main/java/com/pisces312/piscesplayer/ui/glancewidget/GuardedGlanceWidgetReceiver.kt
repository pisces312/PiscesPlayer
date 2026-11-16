package com.pisces312.piscesplayer.ui.glancewidget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.pisces312.piscesplayer.data.database.DatabaseVersionGuard
import timber.log.Timber

/**
 * Base receiver that refuses to refresh any home-screen widget while the database on disk
 * belongs to a newer build (see [DatabaseVersionGuard]).
 *
 * This is not a nicety: Android re-broadcasts widget updates on every package install, which
 * happens *before* the user launches the app. Glance composes with the Compose runtime and
 * loads artwork through Coil, so a refresh reaches the library repositories and opens Room —
 * which, for a newer on-disk schema, throws from `onDowngrade` on a background dispatcher and
 * kills the process. On a downgraded install this was the first crash, ahead of `MainActivity`.
 *
 * Dropping the broadcast leaves the last RemoteViews on screen untouched; stale widget content
 * is a far better outcome than a crash loop triggered by the launcher.
 */
abstract class GuardedGlanceWidgetReceiver : GlanceAppWidgetReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (DatabaseVersionGuard.isBlocked) {
            Timber.tag(TAG).w(
                "Skipping widget broadcast %s: database schema is newer than this build",
                intent.action
            )
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (DatabaseVersionGuard.isBlocked) return
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        if (DatabaseVersionGuard.isBlocked) return
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    private companion object {
        const val TAG = "GuardedGlanceReceiver"
    }
}
