@file:OptIn(ExperimentalForeignApi::class)

package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import com.getlokalapp.paymentsdk.model.ClientStatus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSCache
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.QuartzCore.kCACornerCurveContinuous
import platform.UIKit.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// Grid geometry — shared between the content-fit sheet-detent math (below) and
// the picker's own layout so the sheet is exactly as tall as its grid.
private const val GRID_COLUMNS = 4
private const val CELL_HEIGHT = 92.0
private const val GRID_TOP = 54.0
private const val GRID_BOTTOM_PAD = 36.0
// Side inset on the grid so 4 columns don't span the full sheet width — this is
// what tightens the gap between icons to match the Android chooser.
private const val GRID_SIDE_PAD = 16.0
private const val ICON_SIZE = 58.0
private const val ICON_TOP = 8.0
// iOS home-screen "squircle": ~22.37% of the tile size, drawn with the
// continuous corner curve (below), not a plain circular arc.
private const val ICON_CORNER_RADIUS = ICON_SIZE * 0.2237

// Failure codes surfaced on PaymentResult.Failure (machine-checkable) and their
// human-readable messages.
private const val ERROR_NO_UPI_APP = "no_upi_app"
private const val ERROR_NO_VIEW_CONTROLLER = "no_view_controller"
private const val MESSAGE_NO_VIEW_CONTROLLER = "upi_intent_no_view_controller"
private const val MESSAGE_OPEN_FAILED = "no_upi_app_or_open_failed"
private const val MESSAGE_NO_ALLOWED_APP = "no_allowed_upi_app_installed"

// Chooser UI strings.
private const val DETENT_ID_FIT = "upiFit"
private const val PICKER_TITLE = "Pay using UPI"

// Home-screen-style press feedback on a chooser cell.
private const val PRESS_SCALE = 0.88

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
        val installed = LokalPaymentSdk.installedUpiApps().filter { it.urlScheme != null }
        val allowed = config.allowedApps
        val apps = installed.toChooserApps(allowed)
        when {
            // Backend restricted the chooser but none of those apps resolved. Note
            // the iOS blind spot: detection only sees a scheme the host declared
            // in Info.plist under LSApplicationQueriesSchemes, so a misconfigured
            // host can produce this Failure even when the app is installed.
            allowed.isNotEmpty() && apps.isEmpty() -> {
                listener?.onFailure(ERROR_NO_UPI_APP, MESSAGE_NO_ALLOWED_APP)
                return
            }
            // No allow-list and nothing detected (often just a missing Info.plist
            // declaration) — best-effort raw open rather than a hard failure.
            apps.isEmpty() -> {
                openUrl(config.intentUrl)
                return
            }
        }
        val presenter = topmostViewController()
        if (presenter == null) {
            listener?.onFailure(ERROR_NO_VIEW_CONTROLLER, MESSAGE_NO_VIEW_CONTROLLER)
            return
        }
        val picker = UpiAppPickerController(
            apps = apps,
            onPick = { chooserApp -> openUrl(config.intentUrl.withUpiScheme(chooserApp.app.urlScheme!!)) },
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
        // Touch-blocking loader bridging the gap between the tap (or the chooser
        // dismissing) and the UPI app taking over the screen — removed once
        // openURL resolves either way.
        val loader = topmostViewController()?.view?.let(::attachLoaderOverlay)
        UIApplication.sharedApplication.openURL(
            NSURL(string = url),
            options = emptyMap<Any?, Any?>(),
        ) { success ->
            dispatch_async(dispatch_get_main_queue()) {
                loader?.removeFromSuperview()
                if (success) {
                    listener?.onPending(ClientStatus.UNKNOWN)
                } else {
                    listener?.onFailure(ERROR_NO_UPI_APP, MESSAGE_OPEN_FAILED)
                }
            }
        }
    }

    override fun setResultListener(listener: UpiIntentResultListener?) {
        this.listener = listener
    }
}

internal actual fun createUpiIntentClient(): UpiIntentClient = IosUpiIntentClient()

/**
 * One grid cell: a tap [UIButton] holding a shadowed [iconCard] wrapper (the
 * elevation) whose [icon] image is clipped to rounded corners, over a [label].
 * The card and the image are separate because a layer can't both clip its
 * content to rounded corners and cast a shadow.
 */
private class PickerCell(
    val button: UIButton,
    val iconCard: UIView,
    val icon: UIImageView,
    val label: UILabel,
)

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
    private val apps: List<UpiChooserApp>,
    private val onPick: (UpiChooserApp) -> Unit,
    private val onCancel: () -> Unit,
) : UIViewController(nibName = null, bundle = null) {

    private var picked = false
    private val titleLabel = UILabel()
    private val cells = mutableListOf<PickerCell>()

    // String -> NSString bridges at runtime in Kotlin/Native; the compiler flags
    // the cast as CAST_NEVER_SUCCEEDS (e.g. logoUrl as NSString for the cache key).
    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun viewDidLoad() {
        super.viewDidLoad()
        val root: UIView = view
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

        apps.forEach { chooserApp ->
            val displayName = chooserApp.app.displayName
            val cellButton = UIButton(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))

            // Card wrapper carries the subtle home-screen elevation; it can't clip
            // (that would clip its own shadow), so the squircle corners live on
            // the image. Continuous corner curve = the iOS app-icon shape, not a
            // plain circular-arc rounded rect.
            val iconCard = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
            iconCard.setBackgroundColor(UIColor.clearColor)
            iconCard.layer.setMasksToBounds(false)
            iconCard.layer.setCornerRadius(ICON_CORNER_RADIUS)
            iconCard.layer.setCornerCurve(kCACornerCurveContinuous)
            iconCard.layer.setShadowColor(UIColor.blackColor.CGColor)
            iconCard.layer.setShadowOpacity(0.12f)
            iconCard.layer.setShadowRadius(3.0)
            iconCard.layer.setShadowOffset(CGSizeMake(0.0, 1.5))
            // A plain UIView defaults to userInteractionEnabled=true, so it
            // swallows touches meant for the button underneath — without this the
            // icon (the whole visual target) is a dead tap zone.
            iconCard.setUserInteractionEnabled(false)

            val icon = UIImageView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
            // Fill the tile edge-to-edge like a real app icon (assets are square).
            icon.setContentMode(UIViewContentMode.UIViewContentModeScaleAspectFill)
            icon.layer.setCornerRadius(ICON_CORNER_RADIUS)
            icon.layer.setCornerCurve(kCACornerCurveContinuous)
            icon.setClipsToBounds(true)
            // The monogram is the fallback: shown immediately when there's no
            // logo_url, and only after a fetch fails when there is one. During a
            // fetch the image stays transparent (no placeholder).
            val logoUrl = chooserApp.logoUrl
            val cachedLogo = logoUrl?.let { logoCache.objectForKey(it as NSString) as? UIImage }
            when {
                logoUrl == null -> icon.setImage(monogramIcon(displayName))
                cachedLogo != null -> icon.setImage(cachedLogo)
                else -> loadRemoteLogo(logoUrl, icon) { monogramIcon(displayName) }
            }
            iconCard.addSubview(icon)

            val label = UILabel()
            label.setText(displayName)
            label.setTextAlignment(NSTextAlignmentCenter)
            label.setFont(UIFont.systemFontOfSize(11.0))
            label.setTextColor(UIColor.labelColor)
            label.setNumberOfLines(1) // single line; UILabel truncates the tail with "…" by default
            cellButton.addSubview(iconCard)
            cellButton.addSubview(label)
            cellButton.addAction(
                UIAction.actionWithHandler { animatePress(cellButton, down = true) },
                forControlEvents = UIControlEventTouchDown or UIControlEventTouchDragEnter,
            )
            cellButton.addAction(
                UIAction.actionWithHandler { animatePress(cellButton, down = false) },
                forControlEvents = UIControlEventTouchUpInside or UIControlEventTouchUpOutside or
                    UIControlEventTouchCancel or UIControlEventTouchDragExit,
            )
            cellButton.addAction(
                UIAction.actionWithHandler {
                    picked = true
                    dismissViewControllerAnimated(true) { onPick(chooserApp) }
                },
                forControlEvents = UIControlEventTouchUpInside,
            )
            content.addSubview(cellButton)
            cells.add(PickerCell(cellButton, iconCard, icon, label))
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val root: UIView = view
        val totalWidth = root.bounds.useContents { size.width }

        titleLabel.setFrame(CGRectMake(0.0, 16.0, totalWidth, 24.0))

        val cellWidth = (totalWidth - 2 * GRID_SIDE_PAD) / GRID_COLUMNS
        cells.forEachIndexed { index, cell ->
            val row = index / GRID_COLUMNS
            val column = index % GRID_COLUMNS
            cell.button.setFrame(CGRectMake(GRID_SIDE_PAD + column * cellWidth, GRID_TOP + row * CELL_HEIGHT, cellWidth, CELL_HEIGHT))
            // icon-card + label frames are in the cell button's own coordinate
            // space; the image fills the card.
            cell.iconCard.setFrame(CGRectMake((cellWidth - ICON_SIZE) / 2.0, ICON_TOP, ICON_SIZE, ICON_SIZE))
            cell.icon.setFrame(CGRectMake(0.0, 0.0, ICON_SIZE, ICON_SIZE))
            cell.label.setFrame(CGRectMake(2.0, ICON_TOP + ICON_SIZE + 6.0, cellWidth - 4.0, 16.0))
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!picked) onCancel()
    }
}

/**
 * Home-screen-style press feedback on a chooser cell: shrink + dim on
 * touch-down, ease back on release/cancel.
 */
private fun animatePress(view: UIView, down: Boolean) {
    val scale = if (down) PRESS_SCALE else 1.0
    UIView.animateWithDuration(if (down) 0.10 else 0.18) {
        view.setTransform(CGAffineTransformMakeScale(scale, scale))
        view.setAlpha(if (down) 0.65 else 1.0)
    }
}

/**
 * Dimmed, touch-blocking overlay with a centered spinner, shown over [host]
 * while `openURL` resolves. The caller removes it in the completion handler.
 */
private fun attachLoaderOverlay(host: UIView): UIView {
    val overlay = UIView(frame = host.bounds)
    overlay.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
    overlay.setBackgroundColor(UIColor.colorWithWhite(white = 0.0, alpha = 0.35))
    overlay.setAlpha(0.0)

    val spinner = UIActivityIndicatorView(activityIndicatorStyle = UIActivityIndicatorViewStyleLarge)
    spinner.setColor(UIColor.whiteColor)
    val (width, height) = host.bounds.useContents { size.width to size.height }
    spinner.setCenter(CGPointMake(width / 2.0, height / 2.0))
    spinner.setAutoresizingMask(
        UIViewAutoresizingFlexibleLeftMargin or UIViewAutoresizingFlexibleRightMargin or
            UIViewAutoresizingFlexibleTopMargin or UIViewAutoresizingFlexibleBottomMargin,
    )
    spinner.startAnimating()
    overlay.addSubview(spinner)

    host.addSubview(overlay)
    UIView.animateWithDuration(0.15) { overlay.setAlpha(1.0) }
    return overlay
}

// Process-wide logo cache keyed by URL, so re-presenting the chooser doesn't
// refetch the same handful of icons. NSCache is thread-safe and self-evicts
// under memory pressure.
private val logoCache = NSCache()

/**
 * Fetches [url] with [NSURLSession] off the main thread while [target] stays
 * transparent, then on the main queue sets the logo (success) or [fallback]
 * (failure) — the fallback appears only after the fetch/decode fails, never
 * during loading. Callers handle the cache-hit case, so this always fetches.
 * UIKit retains [target] while the sheet is presented.
 */
@Suppress("CAST_NEVER_SUCCEEDS") // url as NSString bridges at runtime in Kotlin/Native
private fun loadRemoteLogo(url: String, target: UIImageView, fallback: () -> UIImage?) {
    val key = url as NSString
    val nsUrl = NSURL(string = url)
    NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data: NSData?, _, _ ->
        val image = data?.let { UIImage(data = it) }
        if (image != null) logoCache.setObject(image, forKey = key)
        dispatch_async(dispatch_get_main_queue()) { target.setImage(image ?: fallback()) }
    }.resume()
}

/**
 * Draws a monogram (soft-colored tile + first letter) as the fallback when no
 * `logo_url` is supplied or its fetch fails. It fills the whole square — the
 * cell's image view clips it to the home-screen squircle — so the fallback reads
 * as an app tile, not a circle. The call site in [UpiAppPickerController] doesn't
 * change.
 */
@Suppress("CAST_NEVER_SUCCEEDS") // letter as NSString bridges at runtime in Kotlin/Native
private fun monogramIcon(name: String): UIImage? {
    val dim = ICON_SIZE
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(dim, dim), false, 0.0)
    colorForName(name).setFill()
    UIBezierPath.bezierPathWithRect(CGRectMake(0.0, 0.0, dim, dim)).fill()

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
