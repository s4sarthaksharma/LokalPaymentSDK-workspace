package com.getlokalapp.paymentsdk.host

/**
 * One thing a gateway contributes to the host's generated local Swift package.
 * [LokalGatewayHostContributor.contribute] returns a *list* of these — as many of whichever
 * kinds that gateway needs, in any combination (Juspay contributes four), or an empty list to
 * opt out entirely. The umbrella plugin aggregates every active gateway's list and then picks
 * out each kind it knows how to consume:
 *
 * - [VendorPackage] — a third-party SPM package the umbrella target must link so the
 *   gateway's cinterop bindings (compiled into its own klib — see
 *   docs/cocoapods-to-spm-migration-plan.md, R1) resolve their vendor symbols at final
 *   app link time. The SPM-flavored equivalent of `RazorpayHostContributor`/`PodspecEditor`
 *   injecting `spec.dependency 'razorpay-pod'` (e.g. razorpay-checkout).
 * - [SourceTarget] — first-party Swift source this SDK owns and ships itself (no vendor),
 *   compiled straight into the umbrella as an SPM source target. The SPM-flavored
 *   equivalent of the local `:path` CocoaPod `SharedCocoapodsPlugin` unpacks from the
 *   `iossrc` Maven artifact and declares in the Podfile (e.g. native-iap's NativeIapBridge).
 * - [InfoPlistEntries] — additions the umbrella plugin merges into the host app's `Info.plist`
 *   (e.g. UPI apps' `LSApplicationQueriesSchemes` for `canOpenURL` presence checks). The
 *   SPM-flavored, in-plugin equivalent of the CocoaPods-era `post_install` Ruby snippets
 *   that mutated the plist via `Xcodeproj::Plist`; only applied when the host points the
 *   plugin at its plist via `lokalPaymentSdk { iosInfoPlist = … }` — otherwise the plugin
 *   leaves the plist alone (see [LokalPaymentSdkExtension.iosInfoPlist]).
 * - [PrebuildStep] — a shell snippet that must run *before each Xcode build* (e.g. Juspay's
 *   HyperSDK merchant-asset download, which needs Xcode's resolved SPM checkout path). The
 *   umbrella plugin materializes every gateway's step into one generated dispatcher script
 *   the app registers as a **single** scheme pre-build action, once — the SPM reincarnation
 *   of the CocoaPods managed `post_install` dispatch.
 * - [BundledResource] — a file the gateway generated that MUST end up inside the built `.app`
 *   because something reads it at runtime or at build time by bundle lookup (e.g. Juspay's
 *   `LokalJuspayConfig.json`, which `IOSJuspayClient.resolveClientId` reads via
 *   `NSBundle.mainBundle.pathForResource`, and `MerchantConfig.json`, which HyperSDK's asset
 *   pipeline reads). Generating the file is not enough — it is invisible to the app until it
 *   is a member of the app target's Resources build phase, and a missing member surfaces as a
 *   *runtime* failure (a nil bundle path), not a build error. The umbrella plugin wires every
 *   declared file into the host's Resources build phase when the host opted in via
 *   `lokalPaymentSdk { iosXcodeProject = … }`, and otherwise lists them as an
 *   step in docs/integrating-the-sdk.md §5 — the same two-path shape as [InfoPlistEntries].
 *
 * A list of closed alternatives rather than one record of nullable slots, because every
 * consumer wants *all* items of a single kind across gateways
 * (`contributions.filterIsInstance<VendorPackage>()`) and none ever reads two parts of the
 * same gateway's contribution together — so a wrapper record had nothing to group. The list
 * also collapses the two redundant ways to say "nothing" (a `null` return *and* an
 * all-defaults record) into one empty list, and lifts the accidental one-per-gateway cap the
 * nullable slots imposed: a gateway may now contribute two vendor packages or two pre-build
 * steps without an SPI change.
 *
 * Sealed because the umbrella plugin must already know how to consume every kind — a gateway
 * inventing its own would be silently dropped. Adding a kind is therefore a two-place change:
 * a subtype here plus its consumer in the plugin. The plugin sorts this hierarchy in exactly one
 * place — `Contributions.bucketed()` — whose `when` is exhaustive, so a new subtype *does* break
 * the build until it is bucketed. That catches the "added a kind, forgot the plugin" half; it
 * cannot catch a bucket nothing reads, so still wire the consumer in the same change.
 *
 * NOT (yet) a kind: **a shared runtime-config envelope**. [BundledResource] covers getting a
 * generated file into the app bundle, but each gateway still owns its own file and schema —
 * Juspay writes its own `{ "clientId": … }` inside its `contribute()`. If a second gateway ever
 * needs host config readable at runtime, consider aggregating every gateway's entries into one
 * namespaced `LokalPaymentConfig.json`, exactly how [InfoPlistEntries] already merges into a
 * single plist. Deferred on purpose: with one consumer it buys nothing (HyperSDK's mandated
 * `MerchantConfig.json` has a shape Juspay owns and must stay its own file regardless, so a
 * Juspay app needs two files either way), and a shared envelope would split its schema across
 * this build-time SPI and the `:juspay` runtime klib (which can't depend on this module).
 */
sealed interface HostContribution

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
) : HostContribution

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
) : HostContribution

/**
 * Additions a gateway needs merged into the host app's `Info.plist`. The umbrella plugin
 * collects these across every active contribution and merges them idempotently into the
 * plist the host declared via [LokalPaymentSdkExtension.iosInfoPlist] — only ever adding
 * entries that aren't already present, never removing or reordering the host's own. The
 * SPM-flavored replacement for the CocoaPods `post_install` snippets that patched the plist
 * with `Xcodeproj::Plist`; if the host hasn't set `iosInfoPlist`, nothing is patched and the
 * entries are left for the host to add by hand (docs/integrating-the-sdk.md §5).
 *
 * [queriesSchemes] are URL schemes added to the `LSApplicationQueriesSchemes` array — the
 * schemes an app must declare to `canOpenURL:` them (e.g. UPI apps like `phonepe`, `tez`),
 * which iOS otherwise reports as absent regardless of whether they're installed.
 */
data class InfoPlistEntries(
    val queriesSchemes: List<String> = emptyList(),
) : HostContribution

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
 *
 * Also the type of the SDK's *own* gateway-independent pre-build steps (the per-configuration
 * Kotlin XCFramework restage — see `kotlinXCFrameworkPrebuildStep`), which ride the same
 * dispatcher without being any gateway's contribution.
 */
data class PrebuildStep(
    val name: String,
    val script: String,
) : HostContribution

/**
 * A file the gateway generated that must ship *inside* the built `.app`, because something
 * reads it by bundle lookup at runtime or at build time (Juspay's `LokalJuspayConfig.json`
 * and `MerchantConfig.json`). Writing the file is only half the job: until it is a member of
 * the app target's Resources build phase it is invisible to the app, and the failure surfaces
 * at *runtime* as a nil bundle path rather than as a build error.
 *
 * [path] is an absolute path to the already-written file. The umbrella plugin adds every
 * declared file to the host's Resources build phase when the host opted in via
 * `lokalPaymentSdk { iosXcodeProject = … }`, and otherwise lists them in the generated
 * docs/integrating-the-sdk.md for the app to declare in its own XcodeGen/Tuist spec — the same two-path
 * shape as [InfoPlistEntries].
 */
data class BundledResource(
    val path: String,
) : HostContribution
