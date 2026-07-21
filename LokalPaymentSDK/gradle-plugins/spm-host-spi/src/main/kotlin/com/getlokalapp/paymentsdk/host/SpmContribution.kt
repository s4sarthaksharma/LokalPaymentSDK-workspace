package com.getlokalapp.paymentsdk.host

/**
 * One gateway's contribution to the generated local Swift package: the vendor SPM
 * package the umbrella target must link so the gateway's cinterop bindings
 * (compiled into its own klib — see docs/cocoapods-to-spm-migration-plan.md, R1) can
 * resolve their vendor symbols at final app link time. The SPM-flavored equivalent
 * of `RazorpayHostContributor`/`PodspecEditor` injecting
 * `spec.dependency 'razorpay-pod'` into the host's generated podspec.
 */
data class SpmContribution(
    val vendorPackage: SpmVendorPackage,
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
