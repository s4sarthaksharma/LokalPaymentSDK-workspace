package com.getlokalapp.paymentsdk.hostcontext

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Auto-init hook — the same manifest-declared-ContentProvider trick
 * AndroidX Startup/WorkManager/Firebase use to run library setup with zero
 * host code, since the platform guarantees providers attach before any host
 * Activity. Declared in `:shared`'s own AndroidManifest.xml, merged into the
 * host automatically. Registers [ActivityTracker]; does nothing else — never
 * queried.
 */
internal class ActivityTrackerInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        ActivityTracker.install(application)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
