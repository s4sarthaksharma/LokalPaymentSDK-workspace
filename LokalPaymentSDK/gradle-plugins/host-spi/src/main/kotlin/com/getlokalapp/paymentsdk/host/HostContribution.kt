package com.getlokalapp.paymentsdk.host

/**
 * One gateway's contribution to the generated local Swift package. A gateway
 * contributes a [vendorPackage], a [sourceTarget], an [infoPlist] patch, one or more
 * [consumerNotes], or any combination:
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
 * - [infoPlist] — additions the umbrella plugin merges into the host app's `Info.plist`
 *   (e.g. UPI apps' `LSApplicationQueriesSchemes` for `canOpenURL` presence checks). The
 *   SPM-flavored, in-plugin equivalent of the CocoaPods-era `post_install` Ruby snippets
 *   that mutated the plist via `Xcodeproj::Plist`; only applied when the host points the
 *   plugin at its plist via `lokalPaymentSdk { iosInfoPlist = … }` — otherwise the plugin
 *   leaves the plist alone (see [LokalPaymentSdkExtension.iosInfoPlist]).
 * - [prebuildStep] — a shell snippet that must run *before each Xcode build* (e.g. Juspay's
 *   HyperSDK merchant-asset download, which needs Xcode's resolved SPM checkout path). The
 *   umbrella plugin materializes every gateway's step into one generated dispatcher script
 *   the app registers as a **single** scheme pre-build action, once — the SPM reincarnation
 *   of the CocoaPods managed `post_install` dispatch. See [PrebuildStep].
 * - [bundledResources] — files the gateway generated that MUST end up inside the built `.app`
 *   because something reads them at runtime or at build time by bundle lookup (e.g. Juspay's
 *   `LokalJuspayConfig.json`, which `IOSJuspayClient.resolveClientId` reads via
 *   `NSBundle.mainBundle.pathForResource`, and `MerchantConfig.json`, which HyperSDK's asset
 *   pipeline reads). Generating the file is not enough — it is invisible to the app until it
 *   is a member of the app target's Resources build phase, and a missing member surfaces as a
 *   *runtime* failure (a nil bundle path), not a build error. The umbrella plugin wires every
 *   declared file into the host's Resources build phase when the host opted in via
 *   `lokalPaymentSdk { iosXcodeProject = … }`, and otherwise lists them as an
 *   `INTEGRATION.md` step — the same two-path shape as [infoPlist].
 * - [consumerNotes] — one-time manual steps the app author must perform in their own iOS
 *   project/scheme for this gateway (e.g. Juspay's scheme pre-build action), which a Gradle
 *   plugin cannot do for them because they live in the app's Xcode project, not the generated
 *   package. The umbrella plugin renders them into the generated `INTEGRATION.md` so an app
 *   sees only the steps its own gateway selection requires.
 *
 * All six default to empty/null so a gateway declares only what it needs; the umbrella
 * plugin skips whichever are absent. A contribution with none of them is meaningless
 * (contributors return `null` to opt out instead).
 *
 * NOT (yet) a slot: **a shared runtime-config envelope**. [bundledResources] covers getting a
 * generated file into the app bundle, but each gateway still owns its own file and schema —
 * Juspay writes its own `{ "clientId": … }` inside its `contribute()`. If a second gateway ever
 * needs host config readable at runtime, consider aggregating every gateway's entries into one
 * namespaced `LokalPaymentConfig.json`, exactly how [infoPlist] already merges into a single
 * plist. Deferred on purpose: with one consumer it buys nothing (HyperSDK's mandated
 * `MerchantConfig.json` has a shape Juspay owns and must stay its own file regardless, so a
 * Juspay app needs two files either way), and a shared envelope would split its schema across
 * this build-time SPI and the `:juspay` runtime klib (which can't depend on this module).
 */
data class HostContribution(
    val vendorPackage: VendorPackage? = null,
    val sourceTarget: SourceTarget? = null,
    val infoPlist: InfoPlistContribution? = null,
    val prebuildStep: PrebuildStep? = null,
    val bundledResources: List<String> = emptyList(),
    val consumerNotes: List<ConsumerSetupNote> = emptyList(),
)

/**
 * A gateway's build-time setup: a shell snippet that must run *before each Xcode build*,
 * for work a Gradle sync can't do because it needs Xcode's build environment — most notably
 * locating a vendor SPM package's resolved checkout under DerivedData (whose path Gradle
 * can't predict and which only exists after Xcode resolves the package graph). Juspay's
 * HyperSDK `Fuse.rb` merchant-asset download is the motivating case.
 *
 * The umbrella plugin writes every active gateway's step into `prebuild.d/<name>.sh` beside
 * a generated `lokal-prebuild.sh` dispatcher, which the app registers as **one** Xcode scheme
 * pre-build action (with "Provide build settings from <app target>" enabled so `$BUILD_DIR`
 * etc. reach the [script]). The app wires that action once; new gateways plug into the same
 * dispatcher without further scheme edits — the SPM reincarnation of the CocoaPods managed
 * `post_install` dispatch that globbed the `build/lokal/postInstall` snippet dir.
 *
 * [name] is the snippet's file stem (also its run order — dispatched in sorted order) and must
 * be unique across gateways. [script] is `/bin/sh`-compatible; it runs under `set -eu` so any
 * failing command fails the app build loudly rather than shipping a half-configured app.
 */
data class PrebuildStep(
    val name: String,
    val script: String,
)

/**
 * Additions a gateway needs merged into the host app's `Info.plist`. The umbrella plugin
 * collects these across every active contribution and merges them idempotently into the
 * plist the host declared via [LokalPaymentSdkExtension.iosInfoPlist] — only ever adding
 * entries that aren't already present, never removing or reordering the host's own. The
 * SPM-flavored replacement for the CocoaPods `post_install` snippets that patched the plist
 * with `Xcodeproj::Plist`; if the host hasn't set `iosInfoPlist`, nothing is patched and the
 * entries are surfaced as an `INTEGRATION.md` note instead.
 *
 * [queriesSchemes] are URL schemes added to the `LSApplicationQueriesSchemes` array — the
 * schemes an app must declare to `canOpenURL:` them (e.g. UPI apps like `phonepe`, `tez`),
 * which iOS otherwise reports as absent regardless of whether they're installed.
 */
data class InfoPlistContribution(
    val queriesSchemes: List<String> = emptyList(),
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
data class VendorPackage(
    val url: String,
    val exactVersion: String,
    val packageName: String,
    val productName: String,
)

/**
 * First-party Swift source compiled straight into the generated umbrella as a regular
 * SPM `.target`. Unlike [VendorPackage] (a remote binary the app links) this is our
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
data class SourceTarget(
    val name: String,
    val sourceDir: String,
    val linkedFrameworks: List<String> = emptyList(),
)

/**
 * A one-time manual step a consuming app must perform in its own iOS project or Xcode
 * scheme for a given gateway — something the SDK's Gradle plugin cannot do on the app's
 * behalf because it lives in the app's `.xcodeproj`/scheme, not in the generated Swift
 * package (e.g. Juspay's HyperSDK scheme pre-build action). Contributed via
 * [HostContribution.consumerNotes]; the umbrella plugin collects the notes of every active
 * gateway and renders them into the generated `INTEGRATION.md`, so the app author sees
 * exactly the steps their gateway selection requires and nothing for gateways they don't use.
 *
 * [heading] names the gateway or topic (e.g. "Juspay (HyperSDK)"); [steps] are markdown-ready
 * lines rendered as a bullet list. Purely informational — no build behavior depends on it.
 */
data class ConsumerSetupNote(
    val heading: String,
    val steps: List<String>,
)
