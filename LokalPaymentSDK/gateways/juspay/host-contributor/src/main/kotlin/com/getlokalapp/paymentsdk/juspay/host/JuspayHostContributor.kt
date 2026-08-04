package com.getlokalapp.paymentsdk.juspay.host

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import com.getlokalapp.paymentsdk.host.HostContribution
import com.getlokalapp.paymentsdk.host.BundledResource
import com.getlokalapp.paymentsdk.host.PrebuildStep
import com.getlokalapp.paymentsdk.host.VendorPackage
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import java.io.File

/**
 * Juspay's build-time contribution to an iOS host under SPM. The SPM-flavored sibling of
 * `JuspayHostContributor` (:host-contributor), which injects `spec.dependency 'HyperSDK'`
 * into the CocoaPods podspec. Discovered by the umbrella
 * `com.getlokalapp.paymentsdk.lokal-payment` plugin via ServiceLoader, which gates on
 * [module] (`juspay`) and only calls this when the host imports :juspay — this jar is
 * always on the buildscript classpath, so "not used" is "never called", not absence.
 *
 * Two responsibilities, both mirroring `JuspayHostContributor`'s CocoaPods equivalents:
 *
 *  1. **Link HyperSDK** — contributes the `juspay/hypersdk-ios` SPM package (product
 *     `HyperSDK`). That one package transitively pulls HyperCore, JuspaySafeBrowser and
 *     Airborne on the consumer side, so — unlike the SDK-side cinterop, which fetches all
 *     three xcframeworks by hand (see :juspay build.gradle.kts) — nothing else is declared
 *     here. `packageName` is the URL slug `hypersdk-ios`, NOT the `HyperSDK` product name
 *     (SPM resolves `.product(name:, package:)` by URL-derived identity — see
 *     [VendorPackage]).
 *
 *  2. **Emit `MerchantConfig.json` + `LokalJuspayConfig.json`** ([writeMerchantConfig],
 *     [writeLokalJuspayConfig]) — both from the same `juspayClientId` gradle property the
 *     Android host plugin forwards, so a host declares its Juspay clientId exactly once.
 *     `MerchantConfig.json` (replacing the CocoaPods-era `MerchantConfig.txt`) feeds HyperSDK's
 *     asset pipeline, which needs to know which merchant's assets to download.
 *     `LokalJuspayConfig.json` is a small SDK-owned file whose schema this SDK fully controls;
 *     `IOSJuspayClient` (in `:juspay`) reads it at runtime to resolve HyperServices' required
 *     `clientId` itself — kept separate from `MerchantConfig.json` so that runtime read never
 *     couples to HyperSDK's `clientConfigs` shape (Juspay's contract, not ours). No host code
 *     ever passes a clientId anywhere, matching how Android already resolves it entirely
 *     internally via `hypersdk.plugin`.
 *
 *  3. **Contribute the `Fuse.rb` pre-build step** ([PrebuildStep]) —
 *     HyperSDK's merchant-asset download (its "Validate Mandatory Files" build phase fails
 *     without it) needs Xcode's build environment to locate the unpacked HyperSDK binary
 *     artifact under DerivedData, so it can't run at Gradle-sync time. Under CocoaPods this
 *     rode in a managed `post_install`; here it rides in the umbrella plugin's generated
 *     pre-build dispatcher, which the app registers as a single scheme pre-build action (see
 *     [PrebuildStep]). Running `Fuse.rb` is also what writes Juspay's URL/query schemes into
 *     `Info.plist` — it patches the plist itself, via the `xcodeproj` gem — so this
 *     contributor patches no plist itself. The step then runs `ValidateHyperSDK.rb`, which
 *     only validates; see [FUSE_PREBUILD_SCRIPT] for why that one is best-effort.
 *
 * What this deliberately does NOT do (and why it can't):
 *  - **Register the scheme pre-build action** — a Gradle plugin can't edit the consumer's
 *    scheme without touching their Xcode project, which this SDK avoids (same reason adding
 *    the SPM package is a one-time manual step). Adding the *one* dispatcher pre-action is a
 *    documented host step (see docs/integrating-the-sdk.md §4); every gateway's step
 *    (including this one) then runs through it with no further scheme edits.
 *  - **Add a cinterop** — the Kotlin bindings already ride in via the published :juspay klib
 *    (Maven), compiled against the direct HyperSDK.xcframework cinterop (R1/S2).
 */
class JuspayHostContributor : LokalGatewayHostContributor {

    override val module = OWNED_MODULE

    override fun contribute(
        target: Project,
        config: LokalPaymentSdkExtension,
        dependency: Dependency,
    ): List<HostContribution> {
        // Resolve once and fail loudly here (a host that imports :juspay intends to use it),
        // mirroring the CocoaPods contributor and `hypersdk.plugin`'s own hard failure — then
        // feed both generated files from the single value.
        val clientId = target.providers.gradleProperty(CLIENT_ID_PROPERTY).orNull
        checkNotNull(clientId) {
            "Host imports :juspay but has not set the '$CLIENT_ID_PROPERTY' gradle property " +
                "(gradle.properties, or -P/ORG_GRADLE_PROJECT_…) — required to generate " +
                "iosApp/MerchantConfig.json for HyperSDK's merchant asset download and " +
                "iosApp/LokalJuspayConfig.json for the SDK's runtime clientId resolution."
        }
        val merchantConfig = writeMerchantConfig(target, clientId)
        val lokalJuspayConfig = writeLokalJuspayConfig(target, clientId)

        return listOf(
            // Both files are useless unless they end up INSIDE the built .app: HyperSDK's asset
            // pipeline reads MerchantConfig.json, and IOSJuspayClient.resolveClientId reads
            // LokalJuspayConfig.json via NSBundle.pathForResource — which returns nil (and throws)
            // when the file isn't an app-target member. Declaring them here makes the umbrella
            // plugin wire both into the host's Resources build phase.
            BundledResource(merchantConfig.absolutePath),
            BundledResource(lokalJuspayConfig.absolutePath),
            VendorPackage(
                url = "https://github.com/juspay/hypersdk-ios",
                exactVersion = VENDOR_SDK_VERSION,
                packageName = "hypersdk-ios",
                productName = "HyperSDK",
            ),
            PrebuildStep(name = "juspay", script = FUSE_PREBUILD_SCRIPT),
        )
    }

    /**
     * Writes `iosApp/MerchantConfig.json` in HyperSDK's SPM shape
     * (`{ "clientConfigs": { "<client-id>": {} } }`), in the app directory beside the
     * `.xcodeproj` — where HyperSDK's `Fuse.rb`/`ValidateHyperSDK.rb` pre-build action reads it.
     * Replaces the CocoaPods-era `MerchantConfig.txt`. Runtime clientId resolution no longer
     * reads this file — see [writeLokalJuspayConfig]. Runs eagerly inside the umbrella plugin's
     * `afterEvaluate`, so it's current on every Gradle sync.
     */
    private fun writeMerchantConfig(target: Project, clientId: String): File =
        target.file("../iosApp/MerchantConfig.json").apply {
            writeText(
                """
                {
                  "clientConfigs": {
                    "$clientId": {}
                  }
                }
                """.trimIndent() + "\n",
            )
        }

    /**
     * Writes `iosApp/LokalJuspayConfig.json` (`{ "clientId": "<client-id>" }`) beside the
     * `.xcodeproj` — a small SDK-owned file whose schema this SDK fully controls, read by
     * `IOSJuspayClient` (in `:juspay`) at runtime to resolve HyperServices' required clientId.
     * Deliberately separate from HyperSDK's own [writeMerchantConfig] output: that file's
     * `clientConfigs` shape is Juspay's contract, so reading it at runtime would couple us to a
     * schema we don't own. Like `MerchantConfig.json` it must be an app-target member — both are
     * returned as a [BundledResource], so the umbrella plugin wires them into hosts that set
     * `lokalPaymentSdk { iosXcodeProject = … }` and documents them in docs/integrating-the-sdk.md §5
     * for XcodeGen/Tuist hosts that declare resources in their own spec.
     */
    private fun writeLokalJuspayConfig(target: Project, clientId: String): File =
        target.file("../iosApp/LokalJuspayConfig.json").apply {
            writeText(
                """
                {
                  "clientId": "$clientId"
                }
                """.trimIndent() + "\n",
            )
        }

    private companion object {
        /**
         * `/bin/sh` snippet run by the umbrella plugin's pre-build dispatcher before each Xcode
         * build, mirroring the pre-action in HyperSDK's own SPM integration guide.
         *
         * `Fuse.rb` ships INSIDE HyperSDK's binary artifact (`HyperSDK.zip`), which Xcode unpacks
         * into `SourcePackages/artifacts/<package>/<target>/` while resolving the package graph.
         * It is NOT in the `hypersdk-ios` source checkout — that repo is a pure dependency
         * aggregator (a `Package.swift` declaring `.binaryTarget`s, and nothing else). Since
         * `Fuse.rb` finds `HyperSDK.xcframework` relative to its own path and unpacks the merchant
         * assets into it, it has to run where it sits; it can't be copied elsewhere.
         *
         * Three things this gets right that are easy to get wrong:
         *  - **cwd** — `Fuse.rb` reads `./MerchantConfig.json` relative to the working directory,
         *    and locates the `.xcodeproj` there to patch `Info.plist` with Juspay's URL/query
         *    schemes. Run from anywhere else it silently downloads nothing, so this `cd`s to
         *    `$PROJECT_DIR` first.
         *  - **when it runs** — `Fuse.rb` fetches its real implementation (`FuseRemote.rb`) over
         *    the network on every invocation, so running it unconditionally would put the network
         *    on the critical path of every incremental compile. Gated on a marker (plus Xcode's
         *    `clean`), exactly like HyperSDK's documented pre-action. The marker lives inside the
         *    artifact, so a re-extracted artifact — fresh DerivedData, bumped version — re-fuses
         *    by itself with no staleness bookkeeping on our side.
         *  - **what actually validates** — `Fuse.rb` exits 0 even when the asset download fails
         *    (it only prints), so it is not a reliable gate. `ValidateHyperSDK.rb` is, but
         *    `Fuse.rb` downloads it best-effort and merely warns if that fails — so a missing
         *    validator leaves the marker untouched to retry next build rather than hard-failing
         *    the build on a transient fetch of a script we don't own.
         */
        val FUSE_PREBUILD_SCRIPT = """
            set -eu
            if [ -z "${'$'}{BUILD_DIR:-}" ] || [ -z "${'$'}{PROJECT_DIR:-}" ]; then
              echo "error: Lokal Payment SDK: BUILD_DIR/PROJECT_DIR are not set. Set this scheme" >&2
              echo "       pre-build action's 'Provide build settings from' to your app target." >&2
              exit 1
            fi

            # Xcode unpacks HyperSDK.zip (which carries Fuse.rb) under SourcePackages/artifacts.
            sp_dir="${'$'}{BUILD_DIR%Build/*}SourcePackages"
            pkg_dir="${'$'}sp_dir/artifacts/hypersdk-ios/HyperSDK"
            if [ ! -f "${'$'}pkg_dir/Fuse.rb" ] && [ -d "${'$'}sp_dir/artifacts" ]; then
              # Artifact layout is Xcode's, not a published contract — fall back to a search.
              found=${'$'}(find "${'$'}sp_dir/artifacts" -name Fuse.rb -path '*hypersdk*' 2>/dev/null | head -n 1)
              [ -n "${'$'}found" ] && pkg_dir=${'$'}(dirname -- "${'$'}found")
            fi
            if [ ! -f "${'$'}pkg_dir/Fuse.rb" ]; then
              echo "error: Lokal Payment SDK could not find HyperSDK's Fuse.rb under" >&2
              echo "       ${'$'}sp_dir/artifacts. Xcode downloads HyperSDK's binary artifact while" >&2
              echo "       resolving the package graph, so this almost always means resolution did" >&2
              echo "       not complete. Check the 'Resolving package dependencies' step in the" >&2
              echo "       build log, and that the host XCFramework the generated Package.swift" >&2
              echo "       points at has been assembled (see docs/integrating-the-sdk.md)." >&2
              exit 1
            fi

            # Fuse reads ./MerchantConfig.json and finds the .xcodeproj relative to the cwd.
            cd "${'$'}PROJECT_DIR"

            marker="${'$'}pkg_dir/.lokal_fuse_completed"
            if [ ! -f "${'$'}marker" ] || [ "${'$'}{ACTION:-}" = "clean" ]; then
              echo "Lokal Payment SDK: downloading Juspay HyperSDK merchant assets"
              ruby "${'$'}pkg_dir/Fuse.rb"
            fi

            if [ -f "${'$'}pkg_dir/ValidateHyperSDK.rb" ]; then
              ruby "${'$'}pkg_dir/ValidateHyperSDK.rb"
              touch "${'$'}marker"
            else
              echo "warning: Lokal Payment SDK: HyperSDK did not provide ValidateHyperSDK.rb;" >&2
              echo "         retrying the Juspay asset download on the next build." >&2
            fi
        """.trimIndent()

        // Same gradle property JuspayHostAndroidContributor and JuspayHostContributor read — one
        // host-declared value shared across both platforms and both iOS integration modes.
        const val CLIENT_ID_PROPERTY = "juspayClientId"
    }
}
