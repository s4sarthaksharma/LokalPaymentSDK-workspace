package com.getlokalapp.paymentsdk.host

/**
 * One gateway's contribution to the generated local Swift package. A gateway
 * contributes a [vendorPackage], a [sourceTarget], or both:
 *
 * - [vendorPackage] — a third-party SPM package the umbrella target must link so the
 *   gateway's cinterop bindings (compiled into its own klib — see
 *   docs/cocoapods-to-spm-migration-plan.md, R1) resolve their vendor symbols at final
 *   app link time. The SPM-flavored equivalent of `RazorpayHostContributor`/`PodspecEditor`
 *   injecting `spec.dependency 'razorpay-pod'` (e.g. razorpay-checkout).
 * - [sourceTarget] — first-party Swift source this SDK owns and ships itself (no vendor),
 *   compiled straight into the umbrella as an SPM source target. The SPM-flavored
 *   equivalent of the local `:path` CocoaPod `SharedCocoapodsPlugin` unpacks from the
 *   `iossrc` Maven artifact and declares in the Podfile (e.g. native-iap's NativeIapBridge).
 *
 * Both are nullable so a gateway declares only what it needs; the umbrella plugin skips
 * whichever side is absent. A contribution with neither is meaningless (contributors
 * return `null` to opt out instead).
 */
data class SpmContribution(
    val vendorPackage: SpmVendorPackage? = null,
    val sourceTarget: SpmSourceTarget? = null,
)

/**
 * A single `.package(url:, exact:)` dependency, plus the product of that package the
 * generated umbrella target must depend on so the app links it transitively without
 * knowing the vendor package exists.
 *
 * [packageName] and [productName] are independent and easy to conflate: [packageName]
 * is the *local dependency identity* SPM derives from [url] — its last path segment,
 * minus a trailing `.git` — NOT the dependency's own internal `Package(name: ...)`
 * declaration. Confirmed empirically (`swift package dump-package`) against
 * razorpay-pod: its own manifest declares `Package(name: "RazorpayCheckout", ...)`,
 * but a consumer's `.product(name:, package:)` must reference it as `"razorpay-pod"`
 * (the URL slug) — using `"RazorpayCheckout"` there fails with "unknown package".
 * [productName] is whichever of that package's `products` the umbrella target wants
 * to link (that one *does* match the dependency's own declared product name).
 */
data class SpmVendorPackage(
    val url: String,
    val exactVersion: String,
    val packageName: String,
    val productName: String,
)

/**
 * First-party Swift source compiled straight into the generated umbrella as a regular
 * SPM `.target`. Unlike [SpmVendorPackage] (a remote binary the app links) this is our
 * own code, with no prebuilt artifact and no remote package — the umbrella plugin copies
 * the `.swift` files into `Sources/<name>/` of the generated package and declares a
 * target the umbrella depends on, so they compile and link with the app.
 *
 * [name] is the SPM target/module name (also the `Sources/<name>/` folder) and the
 * dependency the umbrella target lists. [sourceDir] is an absolute path to a directory
 * whose `*.swift` files are copied in — the contributor is responsible for materializing
 * it (e.g. unzipping the module's `iossrc` Maven artifact). [linkedFrameworks] are system
 * frameworks the source needs at link time (e.g. `StoreKit`), emitted as
 * `linkerSettings: [.linkedFramework(...)]`.
 */
data class SpmSourceTarget(
    val name: String,
    val sourceDir: String,
    val linkedFrameworks: List<String> = emptyList(),
)
