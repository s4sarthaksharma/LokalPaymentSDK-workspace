package com.getlokalapp.paymentsdk.host

/**
 * Backs the host's `lokalPaymentSdk { }` DSL and is handed to every
 * [LokalGatewayHostContributor].
 *
 * [gateways] is the host's **complete** gateway selection, and the only supported way to get
 * gateway modules onto the classpath. The host names the entries directly —
 * `import com.getlokalapp.paymentsdk.host.LokalGateway.*` once at the top of its build script,
 * then `gateways = listOf(JUSPAY, UPI_INTENT)`. [LokalGateway] is deliberately *not* mirrored as
 * properties of this extension: that would put six non-settings into this DSL's autocomplete and
 * make every new gateway two edits instead of one. The umbrella plugin adds one Maven coordinate per entry
 * (plus `:shared` unconditionally) to the applying module's `commonMain`, at the plugin's own
 * version. A host does not — and must not — declare
 * `implementation("com.getlokalapp.paymentsdk:<gateway>")` itself; doing so alongside an empty
 * [gateways] is detected and fails the build with a migration message, since the alternative
 * is a silent "no handler registered for gateway X" at runtime. Selecting here rather than
 * declaring coordinates is what makes every gateway's version equal to the plugin's by
 * construction instead of by convention. The same list drives the iOS gate below, so a gateway
 * left out of it contributes nothing to `Package.swift` either.
 *
 * [xcFrameworkName] must match the name the host's own Kotlin Multiplatform build
 * passes to the KMP `XCFramework(...)` DSL — the umbrella plugin needs it to locate
 * the assembled `.xcframework` under `build/XCFrameworks/` and wrap it as the
 * generated package's binary target. Required only when the applying module has Apple
 * Kotlin/Native targets — an Android-only host applies this plugin for [gateways] alone and
 * leaves it unset, which skips the iOS half entirely (`Package.swift`, the plist merge, the
 * `pbxproj` wiring, the scheme pre-actions). With iOS targets present it is still a hard
 * error to leave unset.
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
    var gateways: List<LokalGateway> = emptyList()

    var xcFrameworkName: String? = null
    var iosInfoPlist: String? = null
    var iosXcodeProject: String? = null
    var iosXcodeSchemes: List<String>? = null
}

/**
 * Every gateway module a host can ship, and the Maven artifactId it publishes under (group
 * `com.getlokalapp.paymentsdk`, version supplied by the umbrella plugin). How a host selects from
 * these is [LokalPaymentSdkExtension.gateways]'s concern, documented there.
 *
 * The single source of truth for "which gateways exist" — a list that was previously implicit in
 * three places free to silently disagree (`settings.gradle.kts`'s `include(":gateways:…")` lines,
 * each host's version-catalog entries, and every contributor's generated `OWNED_MODULE`). A
 * gateway absent from here is unreachable by every host, so **adding an entry is a required step
 * when adding a gateway** (see docs/adding-a-new-gateway.md).
 *
 * [artifactId] must match the gateway's Gradle project name, since that is what it publishes as
 * and what each contributor's `OWNED_MODULE` resolves to (the umbrella plugins gate by comparing
 * the two). Nothing validates the match at configuration time — a mismatch surfaces as an
 * unresolved dependency naming a coordinate the host never wrote.
 *
 * Not listed, deliberately: `:shared`, which the umbrella plugin adds unconditionally (a host
 * with no gateways still needs `LokalPaymentSdk`), and `:webview`, which is an `implementation`
 * detail of [WEB_CHECKOUT] and never host-facing.
 */
enum class LokalGateway(val artifactId: String) {
    RAZORPAY_CHECKOUT("razorpay-checkout"),
    RAZORPAY_CUSTOMUI("razorpay-customui"),
    UPI_INTENT("upi-intent"),
    NATIVE_IAP("native-iap"),
    JUSPAY("juspay"),
    WEB_CHECKOUT("web-checkout"),
}
