package com.getlokalapp.paymentsdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Base for the SDK's manifest-declared startup hooks — the ContentProvider
 * auto-init trick (AndroidX Startup/WorkManager/Firebase do the same): the
 * platform instantiates every manifest provider at process start, before
 * `Application.onCreate()` and any host code, so [onAppStart] is a
 * guaranteed, synchronous, zero-host-code startup callback. These hooks are
 * never used as actual providers — no one ever queries them — so this base
 * absorbs the six dead `Cursor`/CRUD overrides and subclasses implement only
 * [onAppStart].
 *
 * Public only because gateway modules (separate compilations) subclass it —
 * this is SDK infrastructure, not host API; hosts never touch it. Each
 * subclass must be declared in its own module's AndroidManifest.xml with a
 * unique `android:authorities` suffix (see any gateway module's manifest for
 * the pattern).
 *
 * [onAppStart] runs during process creation: keep it a bare in-memory setup
 * (register a handler, install a callback) — no I/O, no heavy work.
 */
abstract class SdkInitProvider : ContentProvider() {

    /** The one method subclasses implement — runs once at process start. */
    protected abstract fun onAppStart()

    final override fun onCreate(): Boolean {
        onAppStart()
        return true
    }

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null
    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
