# CocoaPods → Swift Package Manager Migration Plan

> **Audience:** an engineer/agent migrating LokalPaymentSDK's iOS integration
> from CocoaPods to Swift Package Manager (SPM), with working knowledge of this
> repo's module layout. Read [`architecture-reference.md`](./architecture-reference.md)
> first for how the SDK is structured, and [`adding-a-new-gateway.md`](./adding-a-new-gateway.md)
> for the gateway/host-contributor model this plan retargets.
> **Status:** approved design, not yet implemented.
> **Goal:** end state is **SPM-only** — CocoaPods fully retired. Multiple internal
> Lokal apps consume the SDK; all are moving to SPM, so there are no external
> partners whose Pods integration must be preserved forever. CocoaPods and SPM
> may coexist *during* the transition.

---

## 0. How to use this document

Work top to bottom. **Do §6 (Phase 0 spike) before committing to the rest** — the
one genuinely unverified mechanic (feeding a vendor XCFramework to a Kotlin/Native
cinterop *without* CocoaPods) must be proven on the zero-vendor gateway first. If it
doesn't work as described, stop and report back rather than forcing it.

Code blocks marked `// SKETCH` are shapes to adapt, not verbatim truth.

---

## 1. Why this is smaller than it looks — the umbrella-framework insight

The critical fact that shrinks this migration: **the SDK already consolidates into a
single iOS framework on the consumer side.** The consuming app's KMP module (e.g.
`composeApp` in the demo) declares every SDK module as a **Maven klib**
(`implementation(libs.lokalpaymentsdk.*)`) and the Kotlin CocoaPods plugin compiles
them all into **one umbrella framework** (`ComposeApp.framework`).

Consequences:

- Gateways are **not** shipped as separate binary frameworks. They are klibs folded
  into the one umbrella. **There is no multi-framework type-identity / duplicate-Kotlin-
  runtime problem to solve** — the classic KMP "don't ship N frameworks" trap does not
  apply here.
- The only iOS-*native* binaries in play are:
  1. the consumer's single umbrella framework,
  2. third-party vendor SDKs (`razorpay-pod`, `HyperSDK`),
  3. the first-party `NativeIapBridge` Swift source (`:gateways:native-iap`).
- The per-gateway `cocoapods { pod("razorpay-pod") }` / `pod("HyperSDK")` blocks are
  **cinterops** — they exist so the gateway klib compiles against vendor headers. The
  vendor *runtime* is linked into the app separately, via each gateway's
  `LokalGatewayHostContributor` injecting `spec.dependency 'razorpay-pod'` / `'HyperSDK'`
  into the umbrella's generated podspec (see the tail of `composeApp.podspec`).

Everything on the **Kotlin / Gradle / Maven layer is Pods-agnostic and stays unchanged.**
Klibs keep publishing to Maven exactly as today. Only the iOS-native packaging and the
consumer-side wiring change.

---

## 2. What actually changes — the four seams

| # | Today (CocoaPods) | Under SPM | Difficulty |
|---|---|---|---|
| S1 | Umbrella built via `org.jetbrains.kotlin.native.cocoapods` + `syncFramework` Xcode script phase | Umbrella built as an **XCFramework** (KMP native `XCFramework` output), consumed as an SPM **binary target**. Rebuilt on demand by a Gradle task (D1). | Medium |
| S2 | Gateway cinterops via `pod("razorpay-pod")` / `pod("HyperSDK")` (CocoaPods-resolved headers) | **Direct Kotlin/Native cinterop** against the vendor **XCFramework**, no CocoaPods | **Hardest** — see R1 |
| S3 | Vendor runtime linked via injected `spec.dependency … trunk` | Vendor linked via **SPM package** in the app (`razorpay/razorpay-pod`, `juspay/hypersdk-ios`) | Easy per vendor — both ship SPM |
| S4 | Juspay `Fuse.rb` via managed Podfile `post_install`; `MerchantConfig.txt` | **Xcode scheme pre-build action** running `Fuse.rb` + `ValidateHyperSDK.rb`; `MerchantConfig.json` | Medium — Juspay only |

Vendor SPM availability (verified 2026-07):
- **Razorpay** — `github.com/razorpay/razorpay-pod` serves both Pod and SPM (iOS 13+).
- **Juspay HyperSDK** — `github.com/juspay/hypersdk-ios` is the dedicated SPM repo. Its
  asset pipeline (`Fuse.rb`) is **not** fully automated under SPM: it requires an Xcode
  **scheme pre-build action** plus a **`MerchantConfig.json`** (`{ "clientConfigs": { "<client-id>": {} } }`).
  This is the SPM relocation of today's `post_install` snippet — see S4 / Phase 2.3.

> **Evaluated and rejected for R1/S2: JetBrains' own `swiftPMDependencies` /
> SwiftPM-import KMP Gradle DSL** (2026-07-21). This is a real, officially-documented
> feature (`kotlinlang.org/docs/multiplatform/multiplatform-spm-import.html`) that lets a
> Kotlin/Native target import an Objective-C-compatible SwiftPM package directly, no
> CocoaPods — on its face, a candidate to replace the hand-rolled cinterop in R1.
> Confirmed working in production in the sibling `matrimony-kmp` repo (Facebook/MoEngage/
> AppsFlyer iOS SDKs, zero CocoaPods anywhere in that repo, running on the same *stable*
> Kotlin 2.4.0 this SDK pins — despite the docs stating `2.4.20-Beta1` as the minimum).
> **Rejected because of where it's used, not whether it works:** every real usage found
> (the docs' own examples, and matrimony-kmp's) is at the *final app module* — never
> inside a published library later consumed by other apps. That's not incidental: the
> docs explicitly state exporting a module that uses SwiftPM import as a Swift package
> itself is **not yet supported** ([KT-84420](https://youtrack.jetbrains.com/issue/KT-84420)).
> Our gateway modules exist specifically to encapsulate a vendor SDK behind a klib
> published to Maven for *other teams'* apps to consume (see razorpay-checkout's own
> `implementation, not api` comment on its Razorpay dependency) — the one thing this
> feature doesn't yet support. Confirmed matrimony-kmp doesn't contradict this: Razorpay/
> Juspay are Android-only in its Kotlin code entirely; iOS payment integration there is
> hand-wired natively outside Kotlin. Revisit if/when KT-84420 closes.

---

## 3. Design decisions (locked)

- **D1 — Local dev loop: prebuilt + Gradle rebuild task.** SPM binary targets are
  prebuilt; there is *no* per-Xcode-build Gradle step (unlike today's `syncFramework`
  script phase). Kotlin edits are picked up by running a Gradle task that rebuilds the
  umbrella XCFramework; Xcode then consumes the refreshed binary. Accepted trade-off:
  slower inner loop for engineers editing Kotlin from Xcode, in exchange for an
  idiomatic SPM integration.
- **D2 — Consumer wiring: generated `Package.swift`.** The retargeted `lokal-payment`
  plugin writes a **generated local `Package.swift`** (analogous to today's generated
  `lokal_ios_pods.rb`): it unpacks first-party iOS bits from Maven and emits SPM targets
  instead of `:path` pods. The consuming app adds **one local package dependency**. This
  replaces Podfile-region editing. Rationale: declarative, diffable, and far less fragile
  than patching `project.pbxproj`.
  - **D2a — opt-in `.xcodeproj` auto-wiring (added post-migration).** The default above
    stands: the SDK does not touch `project.pbxproj`, and XcodeGen/Tuist hosts wire the
    package in their spec. But a *hand-managed* `.xcodeproj` (the demo's shape) otherwise
    needs a manual one-time "Add Local…". For that case only, the plugin exposes
    `lokalPaymentSdk { iosXcodeProject = "<path>.xcodeproj" }` — the exact sibling of the
    existing `iosInfoPlist` opt-in: a path the host sets, an **idempotent** edit (a project
    already pointing at the package is left byte-for-byte untouched) of a git-tracked file
    the host owns, and off by default (unset → the manual steps are surfaced in
    `INTEGRATION.md` instead). It performs the same edit Xcode's "Add Local…" writes —
    an `XCLocalSwiftPackageReference` + `XCSwiftPackageProductDependency` + `PBXBuildFile`
    and their three array references — via targeted, formatting-preserving text insertion
    (never a full reserialize), keeping D2's "diffable, minimal-diff" property intact.
    XcodeGen/Tuist hosts never set it (they'd regenerate the `pbxproj` and clobber the edit),
    exactly as they leave `iosInfoPlist` pointed at a committed plist, not a generated one.
- **D3 — Distribution stays on Maven.** Maven remains the source of truth for first-party
  artifacts (klibs, and any first-party iOS source such as `NativeIapBridge`). The
  consumer-side plugin unpacks from Maven and generates local SPM targets. We are *not*
  moving first-party distribution to GitHub Releases zips.
- **D4 — A new parallel SPI, not the same interface retargeted.** ~~Originally sketched
  as "the `LokalGatewayHostContributor` SPI survives and retargets."~~ **Revised after
  Phase 1 implementation:** the repo already has a precedent for a second, parallel
  plugin+SPI pair per build phase/concern — `settings-spi`/`settings-plugin` is a
  separate pair from `cocoapods-host-spi`/`cocoapods-host-plugin`. Podspec text-editing
  and Package.swift-manifest generation are different enough jobs (imperative file patch
  vs. structured data the umbrella plugin must aggregate into ONE manifest) that forcing
  both through one `contribute()` method would mean every contributor branching on mode
  internally. Instead: a new `gradle-plugins:spm-host-spi` (`LokalGatewaySpmContributor`,
  returning a `SpmContribution` data value instead of doing its own file IO — the
  umbrella plugin owns the single generated manifest and aggregates every contribution
  into it) and `gradle-plugins:spm-host-plugin` (`LokalPaymentSpmPlugin`), fully
  independent of the CocoaPods pair. The self-gating convention (return nothing/null
  unless the host imports the gateway) carries over unchanged. `RazorpayHostContributor`
  (CocoaPods) is untouched; a sibling `RazorpaySpmContributor` (SPM) sits alongside it.
- **D5 — The "flag" is which plugin a host applies, not a boolean.** ~~Originally
  sketched as "keep Pods working behind a flag."~~ **Revised:** because D4 makes these
  two fully independent plugins, the Pods/SPM choice is simply which one a host applies —
  `com.getlokalapp.paymentsdk.lokal-payment` (Pods) or `com.getlokalapp.paymentsdk.lokal-payment-spm`
  (SPM) — never both. Zero runtime branching in shared contributor code; each of the
  "multiple teams'" apps picks independently, so any single gateway regressing under SPM
  doesn't block the others. Remove the Pods plugin only in Phase 3.

---

## 4. Migration order

Ordered by vendor coupling (least → most), which is also the natural "test one provider
at a time" seam:

1. **`native-iap`** — no external vendor (own `NativeIapBridge` Swift + StoreKit). Phase 0 spike.
2. **`web-checkout` / `upi-intent` / `razorpay-customui`** — pure-KMP, no vendor cinterop; only need to keep folding into the XCFramework umbrella.
3. **`razorpay-checkout`** — direct cinterop vs. Razorpay XCFramework + `razorpay-pod` SPM package.
4. **`juspay`** — direct cinterop vs. HyperSDK XCFramework + `hypersdk-ios` SPM package + rewrite contributor (`Fuse.rb` → scheme pre-build action, `MerchantConfig.txt` → `.json`). **Last, most work.**

> **Status — ALL gateways migrated (2026-07-22).** Actual order run: `razorpay-checkout`
> first (as the hardest-vendor pilot), then `native-iap`, then `juspay`; the pure-KMP
> gateways rode in for free. Every gateway builds + links via SPM through the demo app
> (Debug + Release, `xcodebuild` confirmed: each vendor framework `otool -L`-linked into
> the app binary). Only **Phase 3 (drop CocoaPods)** remains, gated on on-device
> validation across the consuming apps — see §5.

---

## 5. Phased plan

> **Phase 0 spike status: two core mechanics PROVEN (2026-07-21).**
> - **R1 — vendor cinterop without CocoaPods (Razorpay):** a direct Kotlin/Native
>   framework cinterop against the hand-fetched `Razorpay.xcframework` (v1.4.3, from
>   the git tag; no LFS, real fat Mach-O) compiled `:gateways:razorpay-checkout`'s
>   real `iosMain` unchanged — the `.def` used `package = cocoapods.razorpay_pod` so
>   the existing imports resolved. Forced `--rerun-tasks`; **zero** cocoapods tasks in
>   the graph. **Implication: gateway Kotlin source needs no changes — only build wiring.**
> - **Consumption plumbing (`:shared`):** KMP `XCFramework("Shared")` assembled a valid
>   two-slice XCFramework via `assembleSharedDebugXCFramework` (the D1 rebuild task); a
>   local `Package.swift` `binaryTarget` accepted it; and `swiftc` compiled+linked a
>   Swift file that imports the module and calls `LokalPaymentSdk.shared.VERSION`
>   (`otool -L` confirms it links `@rpath/Shared.framework/Shared`) — zero CocoaPods.
>
> **Not yet exercised (well-understood, deferred):** running the linked binary on a
> booted simulator; a *combined* umbrella link (Spike A vendor cinterop + Spike B
> XCFramework in one framework that also links the vendor SPM package); the
> `native-iap` `NativeIapBridge`-as-SPM-source-target + cinterop wrinkle; Gradle-side
> `Package.swift` generation (D2); Juspay's scheme pre-build `Fuse.rb` (S4). The
> `:shared`-only spike deliberately isolated the consumption mechanic from the bridge
> cinterop.

### Phase 0 — Spike the mechanics on the zero-vendor gateway (`native-iap`)
Prove the end-to-end loop with **no third-party unknowns**:
- Switch the umbrella from cocoapods-plugin output to **XCFramework** output (KMP
  `XCFramework`/`withXCFramework`), driven by a Gradle rebuild task (D1).
- Consume it from the demo `iosApp` as a **local SPM binary target**.
- Turn `NativeIapBridge` into an **SPM source target** (it is plain Swift + StoreKit —
  cleaner as an SPM target than a hand-written podspec).
- Confirm the demo app builds, links, and runs an IAP flow with **CocoaPods removed for
  this gateway** while the rest stay on Pods.
- **Exit criterion:** IAP purchase flow works end-to-end via SPM. Measure the rebuild-task
  cost to confirm D1 is acceptable.

### Phase 1 — Retarget the host plugin

> **Status: DONE (2026-07-21).** Built per the revised D4/D5 above:
> `gradle-plugins:spm-host-spi` (`LokalGatewaySpmContributor`, `SpmContribution`/
> `SpmVendorPackage`, `LokalPaymentSdkSpmExtension`), `gradle-plugins:spm-host-plugin`
> (`LokalPaymentSpmPlugin`, id `com.getlokalapp.paymentsdk.lokal-payment-spm`,
> generates `build/lokal/spmPackage/Package.swift`: a `binaryTarget` wrapping the
> host's `XCFramework`, plus a thin wrapper source target tying it and every
> contributed vendor package's product together into one product — a `binaryTarget`
> can't declare its own dependencies, so without the wrapper a listed vendor package
> would never actually link), and a real `gateways:razorpay-checkout:spm-host-contributor`
> (`RazorpaySpmContributor`), all compiling and wired into `settings.gradle.kts`.
>
> Verified for real, not just compiled: a scratch harness (`includeBuild`, outside
> both repos) applied `lokal-payment-spm`, declared a fake `razorpay-checkout`
> dependency to trigger the contributor's self-gate, and inspected the generated
> manifest. **`swift package dump-package` on the output actually resolved the real
> `razorpay/razorpay-pod` GitHub repo** and confirmed both that `1.4.3` exists and that
> it declares a product literally named `RazorpayCheckout` — the strongest validation
> possible short of a real Xcode build.
>
> **One important correction surfaced by that verification, now fixed:** a
> `.product(name:, package:)` reference's `package:` argument is the dependency's
> **URL-derived local identity** (last path segment of the git URL, e.g. `"razorpay-pod"`)
> — NOT its own internal `Package(name: ...)` declaration (`"RazorpayCheckout"`). Using
> the latter fails with `unknown package 'RazorpayCheckout'`. `SpmVendorPackage.packageName`
> and its doc comment now say this explicitly, since it's non-obvious and easy to get
> backwards (an earlier draft of this plan got it backwards).
>
> **Not yet done (deferred to Phase 2.1, the combined proof):** wiring the demo
> `composeApp` to actually apply `lokal-payment-spm` for real (switching its
> `cocoapods{}` block to `XCFramework(...)`), the one-time manual Xcode "Add Local
> Package" step, and an actual linked/running app. Juspay's contribution shape
> (scheme pre-build script + `MerchantConfig.json`) is also deferred — `SpmContribution`
> intentionally carries only what Razorpay needs today (YAGNI); extend it when Juspay's
> SPM contributor is built (Phase 2.3).

### Phase 2 — Migrate vendor cinterops one at a time — ✅ DONE (2026-07-22)
1. **`razorpay-checkout`** ✅ → direct cinterop vs. Razorpay XCFramework (`fetchRazorpayXcFramework`
   fetches the git-tag tarball); `RazorpaySpmContributor` contributes the `razorpay/razorpay-pod`
   SPM package. Builds + links through the demo, Debug + Release.
   - **As-built decision (S1):** the umbrella binaryTarget wires the **release** XCFramework only
     (`XCFrameworks/release/`). A binaryTarget's `path:` is static and SPM validates it before any
     Run Script phase, so per-configuration swapping is impossible — instead ship ONE release
     binary (config is orthogonal to slice), which links correctly in every Xcode configuration,
     exactly like a normal vendored SDK. Enabled `kotlin.mpp.enableCInteropCommonization=true`
     (the hierarchical `iosMain` needs it to see the per-target cinterop).
2. **`web-checkout` / `upi-intent` / `razorpay-customui`** ✅ → pure-KMP; fold into the umbrella as
   plain klibs, verified by the demo build. No vendor work.
3. **`native-iap`** ✅ → no vendor SDK, but owns `NativeIapBridge.swift`. Needed a new
   **source-target** contribution shape: SDK-side, `generateNativeIapBridgeInterface` runs
   `swiftc -emit-objc-header` + a modulemap for the cinterop (no binary — headers only);
   consumer-side, `NativeIapSpmContributor` ships the Swift (resolved from the `iossrc` Maven
   artifact) into the umbrella `Package.swift` as a `.target` linking `StoreKit`. Extended
   `SpmContribution` with `SpmSourceTarget`. Builds + links (StoreKit + bridge symbols confirmed).
4. **`juspay`** ✅ → direct cinterop vs. HyperSDK XCFramework (`fetchHyperSdkXcFramework` fetches
   HyperSDK **+ its transitive HyperCore + Airborne** xcframeworks from Juspay's public release CDN
   — the headers `#import <HyperCore/…>` / `@import Airborne`); `JuspaySpmContributor` contributes
   the `juspay/hypersdk-ios` SPM package (which pulls the whole transitive graph on the consumer
   side). Builds + links (HyperSDK/HyperCore/JuspaySafeBrowser/Airborne all `otool -L`-linked).
   - `writePostInstallSnippet` (`juspay.rb` in `post_install`) → **Xcode scheme pre-build action**
     running `Fuse.rb` + `ValidateHyperSDK.rb`. A Gradle plugin can't inject a scheme pre-action,
     so this is a documented one-time host step (like adding the SPM package itself).
   - `writeMerchantConfig` (`.txt`) → **`MerchantConfig.json`** (`{ "clientConfigs": { "<id>": {} } }`),
     auto-emitted by the contributor.
   - **Correction to the original plan:** URL-schemes generation does **not** "stay" — it was a
     `post_install` snippet, which SPM has no host for. It's no longer needed: `ValidateHyperSDK.rb`
     (run by the pre-build action) writes Juspay's URL/query schemes into `Info.plist` itself.
   - **D9 dropped:** `SKIP_HYPERSDK_VALIDATION` was only for the CocoaPods synthetic build's
     "Validate Mandatory Files" script phase; a direct cinterop runs no script phase, so it's gone.
   - **Left to on-device:** live asset download (Fuse.rb) + a real sandbox transaction.

### Phase 3 — Drop CocoaPods — ✅ DONE (2026-07-22, after on-device validation)
Executed once the SPM path was confirmed on-device. What was removed / changed:
- Deleted `cocoapods-host-spi`, `cocoapods-host-plugin`, `cocoapods-host-contributor-support`,
  and each gateway's CocoaPods `host-contributor` (razorpay-checkout, juspay).
- Deleted all `.podspec` files (shared, native-iap, NativeIapBridge, demo composeApp) and the
  demo's `Podfile`, `Podfile.lock`, `Pods/`, `iosApp.xcworkspace`.
- Removed the `org.jetbrains.kotlin.native.cocoapods` plugin + `cocoapods {}` blocks from
  `shared` and `webview` (now plain klibs).
- The sole umbrella plugin took the plain id `com.getlokalapp.paymentsdk.lokal-payment`
  (dropped the `-spm` suffix now that there's no CocoaPods sibling); D5 (the CocoaPods/SPM
  choice) is gone.
- Renamed the cinterop packages `cocoapods.*` → `vendor.*` (def files + iosMain imports) so no
  `cocoapods` label remains in source.
- **KEPT** `registerIosPodSourcePublication` / the `iossrc` artifact — repurposed to ship
  native-iap's Swift source to its SPM source target (dropped the `*.podspec` include).
- **UPI query schemes**: were injected via the CocoaPods `post_install`; now a static
  `LSApplicationQueriesSchemes` list already committed in the demo's `Info.plist` (a documented
  one-time step for consumers).

Verified: full SDK `publishToMavenLocal` + demo `assembleComposeAppReleaseXCFramework` +
`xcodebuild` (Debug) all green with zero CocoaPods anywhere.

Follow-up (optional, not done): rename the remaining `Spm`/`spm` qualifiers on modules
(`spm-host-*`), classes (`LokalPaymentSpmPlugin`, `LokalGatewaySpmContributor`, …) and the
`lokalPaymentSdkSpm { }` extension, now that SPM is the only flavor.

Original checklist, for reference:
- Delete all `.podspec` files, the Podfile-management code, `lokal_ios_pods.rb`
  generation, and the `org.jetbrains.kotlin.native.cocoapods` plugin usages.
- Remove the Pods/SPM flag (D5) and any dead cocoapods host-contributor code paths.
- Update `adding-a-new-gateway.md` to describe the SPM contributor shape.

---

## 6. Risks & unverified mechanics

- **R1 (highest) — vendor XCFramework → cinterop without CocoaPods. ✅ CONFIRMED
  (2026-07-21, Razorpay).** The gateway klibs cinterop against vendor headers at compile
  time. Today CocoaPods resolves those headers. Under SPM we feed the vendor
  **XCFramework** to a plain Kotlin/Native cinterop (`.def` with `-F<slice>` + `-fmodules`).
  Proven: `:gateways:razorpay-checkout` compiled unchanged against a direct cinterop with
  zero cocoapods tasks. Still to confirm at *link* time in the combined umbrella (Phase 2.1).
- **R2 — where the vendor XCFramework comes from at SDK build time.** Options: download
  from the vendor's SPM release, or a vendor-provided binary. Must be reproducible on CI.
- **R3 — `Package.swift` generation shape.** Confirm apps can consume a *generated local*
  package cleanly (path dependency) and that regenerating on Gradle sync doesn't fight
  Xcode's `Package.resolved`.
- **R4 — role of today's Maven `iossrc` `:path` pods.** Pin down exactly what the umbrella
  needs at compile vs. link time so the generated SPM targets mirror it faithfully.
- **R5 — Juspay asset pipeline under SPM.** The scheme pre-build action must run before
  compile and find `MerchantConfig.json`; validate `ValidateHyperSDK.rb` passes without a
  Podfile. Keep a `SKIP_HYPERSDK_VALIDATION`-style escape hatch as the current integration does.

---

## 7. Out of scope
- Android integration (Gradle/Maven) — entirely unaffected.
- Any Kotlin/common source changes — this is packaging/distribution only.
- Moving first-party distribution off Maven (explicitly rejected, D3).
