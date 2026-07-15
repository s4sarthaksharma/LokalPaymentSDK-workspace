package com.getlokalapp.paymentsdk.upiintent

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.getlokalapp.paymentsdk.upi.UpiApp
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView

private const val PICKER_TITLE = "Pay using UPI"
private const val COLUMNS = 4
private const val ICON_DP = 48

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
    apps: List<UpiApp>,
    onPick: (UpiApp) -> Unit,
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
 * UPI apps (real launcher icons via [android.content.pm.PackageManager]).
 * Android Views only. [onPick] fires on a cell tap. [context] must be the
 * dialog's themed context.
 */
private fun buildUpiPickerSheet(
    context: Context,
    apps: List<UpiApp>,
    onPick: (UpiApp) -> Unit,
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
        rowApps.forEach { app ->
            val cell = buildCell(context, app, textColor, ::dp) {
                runCatching { packageManager.getApplicationIcon(app.packageName!!) }.getOrNull()
            }
            cell.setOnClickListener { onPick(app) }
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
    app: UpiApp,
    textColor: Int,
    dp: (Int) -> Int,
    icon: () -> android.graphics.drawable.Drawable?,
): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    isClickable = true
    setPadding(dp(4), dp(10), dp(4), dp(10))

    addView(
        ImageView(context).apply { setImageDrawable(icon()) },
        LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { bottomMargin = dp(6) },
    )
    addView(
        TextView(context).apply {
            text = app.displayName
            textSize = 12f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            isSingleLine = true // single line; ellipsize truncates the tail
            ellipsize = TextUtils.TruncateAt.END
        },
    )
}

private fun Configuration.isNightMode(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
