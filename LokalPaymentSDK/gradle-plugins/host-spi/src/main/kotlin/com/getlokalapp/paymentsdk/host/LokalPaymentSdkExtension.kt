package com.getlokalapp.paymentsdk.host

/**
 * Backs the host's `lokalPaymentSdk { }` DSL and is handed to every
 * [LokalGatewayHostContributor]. The SPM-flavored sibling of
 * [LokalPaymentSdkExtension] (that one drives the CocoaPods-flavored
 * `lokal-payment` plugin) — kept as its own type rather than shared, so the two
 * plugins stay fully independent: a host picks one or the other (see
 * docs/cocoapods-to-spm-migration-plan.md, D5).
 *
 * [xcFrameworkName] must match the name the host's own Kotlin Multiplatform build
 * passes to the KMP `XCFramework(...)` DSL — the umbrella plugin needs it to locate
 * the assembled `.xcframework` under `build/XCFrameworks/` and wrap it as the
 * generated package's binary target.
 */
open class LokalPaymentSdkExtension {
    var xcFrameworkName: String? = null
}
