package com.getlokalapp.paymentsdk.upiintent

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val PICKER_TITLE = "Pay using UPI"
private const val COLUMNS = 4
private const val ICON_DP = 58
// Rounded-corner fraction matching the iOS home-screen squircle (~22.37% of the
// tile). Android's Outline is a plain rounded rect — the OS's closest shape.
private const val ICON_CORNER_RATIO = 0.2237f
private const val LOGO_TIMEOUT_MS = 5000
private const val LOGO_CACHE_ENTRIES = 32

// Small shared pool for the chooser's remote logo fetches — a handful of tiny
// images shown briefly, so a fixed pool is plenty and avoids a thread per cell.
private val logoLoaderPool = Executors.newFixedThreadPool(2)

// Process-wide logo cache keyed by URL, so re-opening the chooser doesn't refetch
// the same handful of icons. Count-bounded (LruCache's default sizeOf is 1/entry)
// and thread-safe; the UPI-app universe is tiny, so 32 covers it comfortably.
private val logoCache = LruCache<String, Bitmap>(LOGO_CACHE_ENTRIES)

/**
 * Shows the SDK's own UPI app chooser as a Material [BottomSheetDialog]: a
 * draggable bottom sheet with a grab handle, swipe-to-dismiss, scrim, and
 * rounded top — all native to the component (no Compose; Material is an
 * androidMain-only dependency). The dialog is created with a Material theme
 * **from the Material library itself**, so it works regardless of the host
 * app's theme and needs no resource shipped by this module.
 *
 * Dismissal is handled by the component: back, tap-outside, and swipe-down all
 * fire [setOnCancelListener] → [onCancel]; a cell tap dismisses then calls
 * [onPick] (never a cancel). Replacing Android's `ResolverActivity`
 * ("Open with…") lets the caller launch the chosen app directly
 * (`Intent.setPackage`), which is what removed the system chooser's black
 * status bar and slide.
 */
internal fun showUpiAppPicker(
    activity: Activity,
    apps: List<UpiChooserApp>,
    onPick: (UpiChooserApp) -> Unit,
    onCancel: () -> Unit,
): Dialog {
    val dialog = BottomSheetDialog(
        activity,
        com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog,
    )
    // Build content with the dialog's themed context so Material views (drag
    // handle) and theme colors resolve correctly.
    val content = buildUpiPickerSheet(dialog.context, apps) { app ->
        dialog.dismiss()
        onPick(app)
    }
    dialog.setContentView(content)
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
    return dialog
}

/**
 * Builds the sheet content: a grab handle, the title, and a grid of installed
 * UPI apps. Each cell shows the backend logo ([UpiChooserApp.logoUrl]) when
 * present, else the real launcher icon via [android.content.pm.PackageManager].
 * Android Views only. [onPick] fires on a cell tap. [context] must be the
 * dialog's themed context.
 */
private fun buildUpiPickerSheet(
    context: Context,
    apps: List<UpiChooserApp>,
    onPick: (UpiChooserApp) -> Unit,
): View {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()

    val textColor = if (context.resources.configuration.isNightMode()) {
        Color.WHITE
    } else {
        0xFF111111.toInt()
    }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(28))
    }

    // Material grab handle — the drag affordance; the sheet is draggable regardless.
    container.addView(
        BottomSheetDragHandleView(context),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    container.addView(
        TextView(context).apply {
            text = PICKER_TITLE
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.START
            setPadding(dp(4), 0, dp(4), dp(16))
        },
    )

    val packageManager = context.packageManager
    apps.chunked(COLUMNS).forEach { rowApps ->
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        rowApps.forEach { chooserApp ->
            // The OS launcher icon is the fallback: shown immediately when there's
            // no logo_url, and only after a fetch fails when there is one.
            val fallbackIcon = {
                runCatching { packageManager.getApplicationIcon(chooserApp.app.packageName!!) }.getOrNull()
            }
            val cell = buildCell(context, chooserApp.app.displayName, textColor, ::dp) { imageView ->
                val logoUrl = chooserApp.logoUrl
                val cachedLogo = logoUrl?.let { logoCache.get(it) }
                when {
                    logoUrl == null -> imageView.setImageDrawable(fallbackIcon())
                    cachedLogo != null -> imageView.setImageBitmap(cachedLogo)
                    // Loading: leave the image transparent (no drawable). The
                    // fallback is set only if the fetch fails.
                    else -> imageView.loadRemoteLogo(logoUrl, fallbackIcon)
                }
            }
            cell.setOnClickListener { onPick(chooserApp) }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Keep cells left-aligned and uniform-width on a short last row.
        repeat(COLUMNS - rowApps.size) {
            row.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(6) },
        )
    }
    return container
}

private fun buildCell(
    context: Context,
    displayName: String,
    textColor: Int,
    dp: (Int) -> Int,
    bindIcon: (ImageView) -> Unit,
): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    isClickable = true
    setPadding(dp(4), dp(10), dp(4), dp(10))

    addView(
        ImageView(context).apply {
            // Fill the tile edge-to-edge (≈ iOS aspectFill) and clip to the same
            // proportional rounded corners, so any drawable — OS launcher icon or
            // fetched logo — takes the home-screen tile shape.
            scaleType = ImageView.ScaleType.CENTER_CROP
            val radiusPx = dp(ICON_DP) * ICON_CORNER_RATIO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            bindIcon(this)
        },
        LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { bottomMargin = dp(6) },
    )
    addView(
        TextView(context).apply {
            text = displayName
            textSize = 12f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            isSingleLine = true // single line; ellipsize truncates the tail
            ellipsize = TextUtils.TruncateAt.END
        },
    )
}

/**
 * Fetches [url] off the main thread while the image stays transparent, then on
 * the UI thread shows the logo (success) or [fallback] (failure) — the fallback
 * appears only after the fetch fails, never during loading. Guarded by
 * [View.isAttachedToWindow] so a result arriving after the sheet is dismissed is
 * dropped. Callers handle the cache-hit case, so this always fetches.
 */
private fun ImageView.loadRemoteLogo(url: String, fallback: () -> Drawable?) {
    logoLoaderPool.execute {
        val bitmap = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = LOGO_TIMEOUT_MS
                readTimeout = LOGO_TIMEOUT_MS
            }
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (bitmap != null) logoCache.put(url, bitmap)
        post {
            if (!isAttachedToWindow) return@post
            if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(fallback())
        }
    }
}

private fun Configuration.isNightMode(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
