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
 *
 * [iosInfoPlist] optionally points the plugin at the host app's `Info.plist` so it can
 * merge in the entries active gateways contribute (e.g. UPI apps'
 * `LSApplicationQueriesSchemes` — see [InfoPlistContribution]). A path string the plugin
 * resolves with `Project.file(...)` (relative to the applying module, like any Gradle
 * path). The merge is idempotent (only adds missing entries) and mutates a git-tracked
 * file the host owns, so it is opt-in: leave it unset and the plugin patches nothing,
 * instead surfacing the same entries as an `INTEGRATION.md` note for the host to add by
 * hand. Point it at the plist a hand-managed `.xcodeproj` references (the demo) or the
 * committed plist an XcodeGen/Tuist spec references — not a fully generated one.
 */
open class LokalPaymentSdkExtension {
    var xcFrameworkName: String? = null
    var iosInfoPlist: String? = null
}
