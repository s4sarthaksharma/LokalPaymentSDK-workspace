@file:OptIn(ExperimentalForeignApi::class)

package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import com.getlokalapp.paymentsdk.model.ClientStatus
import com.getlokalapp.paymentsdk.upi.UpiApp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.UIKit.*

// Grid geometry — shared between the content-fit sheet-detent math (below) and
// the picker's own layout so the sheet is exactly as tall as its grid.
private const val GRID_COLUMNS = 4
private const val CELL_HEIGHT = 82.0
private const val GRID_TOP = 54.0
private const val GRID_BOTTOM_PAD = 36.0
private const val ICON_SIZE = 44.0

// Failure codes surfaced on PaymentResult.Failure (machine-checkable) and their
// human-readable messages.
private const val ERROR_NO_UPI_APP = "no_upi_app"
private const val ERROR_NO_VIEW_CONTROLLER = "no_view_controller"
private const val MESSAGE_NO_VIEW_CONTROLLER = "upi_intent_no_view_controller"
private const val MESSAGE_OPEN_FAILED = "no_upi_app_or_open_failed"

// Chooser UI strings.
private const val DETENT_ID_FIT = "upiFit"
private const val PICKER_TITLE = "Pay using UPI"

/**
 * iOS launcher. UPI works on iOS via `UIApplication.openURL`, but there is no
 * OS-level app chooser for a `upi://` scheme, so when the deep link is the
 * generic `upi://` this presents an **in-SDK** chooser: [UpiAppPickerController],
 * a grid of the UPI apps detected via [LokalPaymentSdk.installedUpiApps].
 * Picking an app rewrites the URL to that app's scheme (see [withUpiScheme]) and
 * opens it. A link that already names an app (`phonepe://…`) skips the chooser
 * and opens directly. Because iOS has no intent-result callback, a successful
 * open maps to [UpiIntentResultListener.onPending] with [ClientStatus.UNKNOWN];
 * dismissing the chooser maps to [UpiIntentResultListener.onCancelled].
 *
 * ⚠️ Detection depends on the **host** declaring the UPI schemes in its
 * `Info.plist` under `LSApplicationQueriesSchemes` — otherwise
 * [LokalPaymentSdk.installedUpiApps] returns empty even when apps exist. When
 * empty we fall back to opening the raw `upi://` URL rather than failing.
 *
 * Runs on the caller's thread — UIKit requires main; the flow is expected to be
 * collected on the main dispatcher, same assumption as the iOS Razorpay client.
 */
internal class IosUpiIntentClient : UpiIntentClient {

    private var listener: UpiIntentResultListener? = null

    override fun launch(config: UpiIntentConfig) {
        // Only disambiguate a generic upi:// link. An app-specific scheme
        // (phonepe://, tez://…) already names its target — open it directly.
        if (!config.intentUrl.isGenericUpiScheme()) {
            openUrl(config.intentUrl)
            return
        }
        val apps = LokalPaymentSdk.installedUpiApps().filter { it.urlScheme != null }
        if (apps.isEmpty()) {
            // No detectable apps (often just a missing Info.plist declaration) —
            // best-effort raw open rather than a hard failure.
            openUrl(config.intentUrl)
            return
        }
        val presenter = topmostViewController()
        if (presenter == null) {
            listener?.onFailure(ERROR_NO_VIEW_CONTROLLER, MESSAGE_NO_VIEW_CONTROLLER)
            return
        }
        val picker = UpiAppPickerController(
            apps = apps,
            onPick = { app -> openUrl(config.intentUrl.withUpiScheme(app.urlScheme!!)) },
            onCancel = { listener?.onCancelled() },
        )
        // Float it as a rounded bottom-sheet card, sized to exactly fit the grid
        // (a content-fit custom detent) so there's no dead space below.
        val rows = (apps.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        val sheetHeight = GRID_TOP + rows * CELL_HEIGHT + GRID_BOTTOM_PAD
        picker.sheetPresentationController?.apply {
            setDetents(
                listOf(
                    UISheetPresentationControllerDetent.customDetentWithIdentifier(DETENT_ID_FIT) { _ -> sheetHeight },
                ),
            )
            setPrefersGrabberVisible(true)
            setPreferredCornerRadius(24.0)
        }
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private fun openUrl(url: String) {
        UIApplication.sharedApplication.openURL(
            NSURL(string = url),
            options = emptyMap<Any?, Any?>(),
        ) { success ->
            if (success) {
                listener?.onPending(ClientStatus.UNKNOWN)
            } else {
                listener?.onFailure(ERROR_NO_UPI_APP, MESSAGE_OPEN_FAILED)
            }
        }
    }

    override fun setResultListener(listener: UpiIntentResultListener?) {
        this.listener = listener
    }
}

internal actual fun createUpiIntentClient(): UpiIntentClient = IosUpiIntentClient()

/** One grid cell: a tap [UIButton] with an [UIImageView] icon over a [UILabel]. */
private class PickerCell(val button: UIButton, val icon: UIImageView, val label: UILabel)

/**
 * Custom chooser modeled on the iOS bottom-sheet panel: a frosted-glass
 * ([UIVisualEffectView]) card with a title and the detected UPI apps in a grid.
 * Each cell is built by hand ([UIImageView] icon + single-line truncating
 * [UILabel]) inside a tap button — not `UIButtonConfiguration`, whose
 * icon-over-title layout clipped the icon when the name wrapped, and not a
 * `UICollectionView`, which Kotlin/Native can't cleanly conform to. A
 * swipe-down dismissal (no selection) reaches [viewDidDisappear] → [onCancel].
 * UIKit retains the controller while presented, so no extra strong reference is
 * needed.
 */
private class UpiAppPickerController(
    private val apps: List<UpiApp>,
    private val onPick: (UpiApp) -> Unit,
    private val onCancel: () -> Unit,
) : UIViewController(nibName = null, bundle = null) {

    private var picked = false
    private val titleLabel = UILabel()
    private val cells = mutableListOf<PickerCell>()

    override fun viewDidLoad() {
        super.viewDidLoad()
        val root: UIView = view ?: return
        root.backgroundColor = UIColor.clearColor

        val glass = UIVisualEffectView(effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleSystemThinMaterial))
        glass.setFrame(root.bounds)
        glass.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        root.addSubview(glass)
        val content: UIView = glass.contentView

        titleLabel.setText(PICKER_TITLE)
        titleLabel.setTextAlignment(NSTextAlignmentCenter)
        titleLabel.setFont(UIFont.boldSystemFontOfSize(17.0))
        titleLabel.setTextColor(UIColor.labelColor)
        content.addSubview(titleLabel)

        apps.forEach { app ->
            val cellButton = UIButton(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
            val icon = UIImageView(image = monogramIcon(app.displayName))
            val label = UILabel()
            label.setText(app.displayName)
            label.setTextAlignment(NSTextAlignmentCenter)
            label.setFont(UIFont.systemFontOfSize(12.0))
            label.setTextColor(UIColor.labelColor)
            label.setNumberOfLines(1) // single line; UILabel truncates the tail with "…" by default
            cellButton.addSubview(icon)
            cellButton.addSubview(label)
            cellButton.addAction(
                UIAction.actionWithHandler {
                    picked = true
                    dismissViewControllerAnimated(true) { onPick(app) }
                },
                forControlEvents = UIControlEventTouchUpInside,
            )
            content.addSubview(cellButton)
            cells.add(PickerCell(cellButton, icon, label))
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val root: UIView = view ?: return
        val totalWidth = root.bounds.useContents { size.width }

        titleLabel.setFrame(CGRectMake(0.0, 16.0, totalWidth, 24.0))

        val cellWidth = totalWidth / GRID_COLUMNS
        cells.forEachIndexed { index, cell ->
            val row = index / GRID_COLUMNS
            val column = index % GRID_COLUMNS
            cell.button.setFrame(CGRectMake(column * cellWidth, GRID_TOP + row * CELL_HEIGHT, cellWidth, CELL_HEIGHT))
            // icon + label frames are in the cell button's own coordinate space
            cell.icon.setFrame(CGRectMake((cellWidth - ICON_SIZE) / 2.0, 4.0, ICON_SIZE, ICON_SIZE))
            cell.label.setFrame(CGRectMake(2.0, 4.0 + ICON_SIZE + 6.0, cellWidth - 4.0, 16.0))
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!picked) onCancel()
    }
}

/**
 * Draws a round monogram (soft-colored circle + first letter) as a stand-in for
 * the real app logo. Replace this with bundled per-app assets when available;
 * the call site in [UpiAppPickerController] doesn't change.
 */
private fun monogramIcon(name: String): UIImage? {
    val dim = ICON_SIZE
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(dim, dim), false, 0.0)
    colorForName(name).setFill()
    UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, dim, dim)).fill()

    val letter = name.take(1).uppercase()
    val paragraph = NSMutableParagraphStyle().apply { setAlignment(NSTextAlignmentCenter) }
    val attributes: Map<Any?, *> = mapOf(
        NSForegroundColorAttributeName to UIColor.whiteColor,
        NSFontAttributeName to UIFont.boldSystemFontOfSize(19.0),
        NSParagraphStyleAttributeName to paragraph,
    )
    (letter as NSString).drawInRect(CGRectMake(0.0, (dim - 24.0) / 2.0, dim, 24.0), attributes)

    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

/**
 * Stable-per-name soft tint so the same app always gets the same monogram
 * color. Muted (not pure primaries) to read as calm iOS-style chips rather than
 * saturated blocks.
 */
private fun colorForName(name: String): UIColor {
    val palette = listOf(
        rgb(0.40, 0.47, 0.92),
        rgb(0.29, 0.70, 0.47),
        rgb(0.95, 0.55, 0.25),
        rgb(0.58, 0.42, 0.90),
        rgb(0.90, 0.40, 0.55),
        rgb(0.25, 0.68, 0.78),
        rgb(0.91, 0.42, 0.38),
        rgb(0.55, 0.48, 0.42),
    )
    val idx = (name.firstOrNull()?.code ?: 0) % palette.size
    return palette[idx]
}

private fun rgb(r: Double, g: Double, b: Double): UIColor =
    UIColor.colorWithRed(red = r, green = g, blue = b, alpha = 1.0)
