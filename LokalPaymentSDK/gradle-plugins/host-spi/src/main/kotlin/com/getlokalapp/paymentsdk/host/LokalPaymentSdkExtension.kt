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
 * `LSApplicationQueriesSchemes` — see [InfoPlistEntries]). A path string the plugin
 * resolves with `Project.file(...)` (relative to the applying module, like any Gradle
 * path). The merge is idempotent (only adds missing entries) and mutates a git-tracked
 * file the host owns, so it is opt-in: leave it unset and the plugin patches nothing,
 * instead leaving the same entries for the host to add by
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
 * manual "Add Local…" step instead. **Hand-managed `.xcodeproj` only** (the demo). XcodeGen/Tuist
 * hosts regenerate their `pbxproj` from a spec and must declare the package there instead —
 * they simply leave this unset, exactly as they leave [iosInfoPlist] pointed at a committed
 * plist rather than a generated one.
 *
 * [iosXcodeSchemes] is the third sibling, and registers the generated `lokal-prebuild.sh`
 * dispatcher as a build pre-action on every sync — the one-time "Edit Scheme ▸ Build ▸
 * Pre-actions ▸ +" step, automated. Same edit lifecycle as the two above: paths resolved with
 * `Project.file(...)`, an idempotent formatting-preserving edit (a scheme already running the
 * dispatcher is left byte-for-byte untouched) of git-tracked files the host owns. Its
 * *default*, though, is deliberately not theirs:
 *
 * - a non-empty list → exactly those schemes, each validated and each failing loudly if it
 *   isn't a readable shared `.xcscheme` (an explicit opt-in pointing somewhere wrong must not
 *   silently do nothing);
 * - unset **and** [iosXcodeProject] set → every shared scheme of that `.xcodeproj` that builds
 *   its application target, discovered on each sync so a scheme added later is picked up;
 * - unset **and** [iosXcodeProject] unset → nothing is touched, the manual steps surfaced as an
 *   host's own responsibility, exactly like the two siblings above;
 * - `emptyList()` → an explicit opt-out that keeps [iosXcodeProject]'s wiring but leaves every
 *   scheme alone.
 *
 * Discovery keys off [iosXcodeProject] rather than being its own switch because the two
 * populations coincide: a host that lets the plugin edit its `pbxproj` has a hand-managed,
 * committed `.xcodeproj`, and its schemes are committed too. XcodeGen/Tuist hosts leave
 * [iosXcodeProject] unset because their project is generated — and so are their schemes, where
 * a patch would be discarded on the next `generate` — so they are excluded for free, with no
 * second rule to keep in sync.
 *
 * Defaulting to "wire it" rather than "leave it alone" is what breaks the symmetry with
 * [iosInfoPlist] and [iosXcodeProject], and it is the dispatcher's failure mode that earns it:
 * those two fail loudly at setup (a missing package won't link, a missing query scheme is a
 * visible UPI bug), while a missing pre-action fails *silently* — the dispatcher is what keeps
 * the staged Kotlin binary current (see `kotlinXCFrameworkPrebuildStep`), so forgetting it
 * reads as a Kotlin edit that "didn't take" rather than as a missing build step.
 *
 * Only schemes under `xcshareddata/xcschemes/` qualify, never one Xcode left in `xcuserdata`:
 * only a shared scheme is committed, and a pre-action every developer needs has to travel with
 * the repo. Discovery is scoped to the `.xcodeproj`'s own shared schemes and never scans a
 * `.xcworkspace` — with a workspace there is no way to tell which of its projects is the app
 * without guessing. A scheme shared at the workspace level still works, listed explicitly.
 */
open class LokalPaymentSdkExtension {
    var xcFrameworkName: String? = null
    var iosInfoPlist: String? = null
    var iosXcodeProject: String? = null
    var iosXcodeSchemes: List<String>? = null
}
