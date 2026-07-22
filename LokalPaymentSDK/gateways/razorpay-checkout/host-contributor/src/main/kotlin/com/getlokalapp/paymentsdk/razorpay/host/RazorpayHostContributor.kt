package com.getlokalapp.paymentsdk.razorpay.host

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import com.getlokalapp.paymentsdk.host.HostContribution
import com.getlokalapp.paymentsdk.host.VendorPackage
import org.gradle.api.Project

/**
 * Razorpay's build-time contribution to an iOS host under SPM: declares
 * `razorpay/razorpay-pod`'s `RazorpayCheckout` product as a dependency the umbrella
 * plugin must link into the generated `Package.swift` — the SPM-flavored sibling of
 * `RazorpayHostContributor` (:host-contributor), which does the equivalent by
 * injecting `spec.dependency 'razorpay-pod'` into the host's generated podspec.
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment` plugin
 * via ServiceLoader.
 *
 * Self-gates on the host actually depending on :razorpay-checkout, identically to
 * `RazorpayHostContributor`. This jar is always on the buildscript classpath (the
 * umbrella depends on it), so "razorpay not used" is a `null` return: nothing is
 * added to the generated manifest and no razorpay-pod package is linked unless
 * razorpay-checkout is imported.
 *
 * `packageName` is `"razorpay-pod"` — the URL slug, NOT `"RazorpayCheckout"` (the
 * pinned tag's own `Package(name: ...)` declaration). Confirmed empirically via
 * `swift package dump-package`: SPM resolves a consumer's `.product(name:, package:)`
 * by the dependency's URL-derived local identity, not its internal declared name —
 * using `"RazorpayCheckout"` there fails with "unknown package 'RazorpayCheckout'".
 *
 * Deliberately does NOT add a cinterop to the host module: the Kotlin bindings
 * already ride in via the published :razorpay-checkout klib (Maven, compiled
 * against a direct framework cinterop — see docs/cocoapods-to-spm-migration-plan.md,
 * R1), so all the host needs is the vendor package linked at the app target.
 */
class RazorpayHostContributor : LokalGatewayHostContributor {

    override fun contribute(target: Project, config: LokalPaymentSdkExtension): HostContribution? {
        val importsRazorpay = target.configurations
            .flatMap { it.dependencies }
            .any { it.group == SDK_GROUP && it.name == RAZORPAY_CHECKOUT_MODULE }
        if (!importsRazorpay) return null

        return HostContribution(
            vendorPackage = VendorPackage(
                url = "https://github.com/razorpay/razorpay-pod",
                exactVersion = VENDOR_SDK_VERSION,
                packageName = "razorpay-pod",
                productName = "RazorpayCheckout",
            ),
        )
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
        const val RAZORPAY_CHECKOUT_MODULE = "razorpay-checkout"
    }
}
