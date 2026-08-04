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
 *
 * [iosXcodeProject] is the exact `.xcodeproj` sibling of [iosInfoPlist]: point it at the
 * host app's hand-managed `.xcodeproj` (or its `project.pbxproj`) and the plugin wires the
 * generated local Swift package in as a package product dependency on every sync — the
 * one-time "Add Local…" step, automated. Same lifecycle as [iosInfoPlist]: a path resolved
 * with `Project.file(...)`, an idempotent edit (a project already pointing at the package
 * is left untouched, zero-diff) of a git-tracked file the host owns, and opt-in — leave it
 * unset and the plugin edits nothing, surfacing the manual "Add Local…" steps as an
 * `INTEGRATION.md` note instead. **Hand-managed `.xcodeproj` only** (the demo). XcodeGen/Tuist
 * hosts regenerate their `pbxproj` from a spec and must declare the package there instead —
 * they simply leave this unset, exactly as they leave [iosInfoPlist] pointed at a committed
 * plist rather than a generated one.
 *
 * [iosXcodeScheme] is the third sibling: point it at a **shared** `.xcscheme` and the plugin
 * registers the generated `lokal-prebuild.sh` dispatcher as a build pre-action on every sync —
 * the one-time "Edit Scheme ▸ Build ▸ Pre-actions ▸ +" step, automated. Same lifecycle as the
 * two above: a path resolved with `Project.file(...)`, an idempotent formatting-preserving edit
 * (a scheme already running the dispatcher is left byte-for-byte untouched) of a git-tracked
 * file the host owns, and opt-in — unset and the plugin edits nothing, surfacing the manual
 * steps in `INTEGRATION.md` instead.
 *
 * Must be a scheme under `xcshareddata/xcschemes/`, not one Xcode left in `xcuserdata`: only a
 * shared scheme is committed, and a pre-action every developer needs has to travel with the
 * repo. Worth automating rather than leaving manual because the dispatcher is what keeps the
 * staged Kotlin binary current (see `kotlinXCFrameworkPrebuildStep`) — forget it and Xcode
 * silently builds against a stale framework, which reads as a Kotlin edit that "didn't take"
 * rather than as a missing build step.
 */
open class LokalPaymentSdkExtension {
    var xcFrameworkName: String? = null
    var iosInfoPlist: String? = null
    var iosXcodeProject: String? = null
    var iosXcodeScheme: String? = null
}
