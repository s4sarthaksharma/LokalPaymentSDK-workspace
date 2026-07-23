package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import com.getlokalapp.paymentsdk.host.HostContribution
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.ServiceLoader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * The sole iOS umbrella plugin (`com.getlokalapp.paymentsdk.lokal-payment`); it took
 * over the plain `lokal-payment` id when the CocoaPods umbrella was removed in Phase 3
 * — see docs/cocoapods-to-spm-migration-plan.md (D5). Applied to the module that
 * declares the host's `XCFramework("<name>")` and produces the umbrella framework.
 *
 * Generates `build/lokal/spmPackage/Package.swift`: a `binaryTarget` wrapping the
 * host's own XCFramework (named via [LokalPaymentSdkExtension.xcFrameworkName]),
 * plus, per [LokalGatewayHostContributor] contribution, either/both of: a
 * `.package(url:, exact:)` dependency + product reference (vendor packages, e.g.
 * razorpay-pod), and a first-party Swift `.target` whose `.swift` files are copied into
 * `Sources/<name>/` (SDK-owned source, e.g. native-iap's NativeIapBridge). A thin
 * wrapper source target ties the binary target, every vendor product, and every
 * first-party source target together into the one product the app depends on — a
 * `binaryTarget` can't declare dependencies of its own, so without this wrapper a
 * contribution would be listed but never actually linked.
 *
 * Regenerated on every Gradle sync (configuration phase, mirroring how
 * `SharedCocoapodsPlugin` regenerates the Podfile's managed regions). The app wires the
 * generated folder in as a **local Swift package once** — declaratively in an XcodeGen
 * `project.yml` / Tuist `Project.swift` (how Lokal's apps manage their iOS projects), or by
 * hand via Xcode's "Add Local…" for a hand-managed `.xcodeproj` like the demo's. For that
 * hand-managed case the host can instead set `lokalPaymentSdk { iosXcodeProject = … }` and
 * the plugin performs that "Add Local…" for it, idempotently, on every sync (see
 * [patchXcodeProjectIfConfigured]). This is the one opt-in exception to the D2 rule that the
 * SDK doesn't edit `project.pbxproj`: off by default, and never for XcodeGen/Tuist hosts,
 * which own their generated project and leave the property unset — see the plan doc's D2
 * rationale. Every regeneration after that one-time wiring is picked up automatically — SPM
 * re-resolves a local package's manifest on every build. Alongside `Package.swift` the plugin
 * also writes an `INTEGRATION.md` carrying the app-specific wiring steps for the host's actual
 * gateway selection — see [writeIntegrationNotes] and docs/integrating-the-sdk.md.
 */
class LokalPaymentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val config = project.extensions.create(
            "lokalPaymentSdk",
            LokalPaymentSdkExtension::class.java,
        )

        // afterEvaluate so contributors see the host's fully-declared dependency set
        // (their import self-gate), same timing as LokalPaymentPlugin.
        project.afterEvaluate {
            val xcFrameworkName = requireNotNull(config.xcFrameworkName) {
                "Host applies 'com.getlokalapp.paymentsdk.lokal-payment' but has " +
                    "not set 'lokalPaymentSdk { xcFrameworkName = \"...\" }' — " +
                    "required to locate the assembled .xcframework this plugin wraps."
            }
            // Gate once, here — not once per contributor. Discover every contributor keyed by
            // the module it owns, then scan the host's declared dependencies a single time:
            // group `com.getlokalapp.paymentsdk` narrows the host's whole dependency set down to
            // our gateway modules, keyed by module name. Call only the contributor whose module
            // the host actually imports, handing it the resolved Dependency (native-iap reads its
            // version/coordinate off it). A contributor may still return null for a
            // present-but-inapplicable case (e.g. a missing artifact).
            val contributors = ServiceLoader.load(
                LokalGatewayHostContributor::class.java,
                LokalGatewayHostContributor::class.java.classLoader,
            ).associateBy { it.module }
            val contributions = project.configurations.asSequence()
                .flatMap { it.dependencies.asSequence() }
                .filter { it.group == SDK_GROUP }
                .associateBy { it.name }
                .mapNotNull { (name, dep) -> contributors[name]?.contribute(project, config, dep) }

            val umbrellaTargetName = "${xcFrameworkName}Umbrella"
            writePackageSwift(project, xcFrameworkName, umbrellaTargetName, contributions)

            // Info.plist: the baseline UPI query schemes (an ungated `:shared` concern —
            // UPI app detection is available to every host regardless of gateway, matching
            // the CocoaPods-era `shared-query-schemes.rb`) plus whatever active gateways add.
            val queriesSchemes = (BASELINE_UPI_QUERY_SCHEMES +
                contributions.mapNotNull { it.infoPlist }.flatMap { it.queriesSchemes })
                .distinct()
            val plistPatched = patchInfoPlistIfConfigured(project, config, queriesSchemes)

            // project.pbxproj: wire the generated local package into a hand-managed .xcodeproj
            // when the host opted in via `lokalPaymentSdk { iosXcodeProject = … }` — the exact
            // sibling of the Info.plist merge above (see patchXcodeProjectIfConfigured).
            val xcodeProjectWired = patchXcodeProjectIfConfigured(project, config, umbrellaTargetName)

            // Build-time steps: one generated dispatcher the app registers as a single Xcode
            // scheme pre-build action (see writePrebuildDispatcher / PrebuildStep).
            val prebuildScript = writePrebuildDispatcher(project, contributions)

            writeIntegrationNotes(
                project, config, xcFrameworkName, umbrellaTargetName, contributions,
                queriesSchemes, plistPatched, xcodeProjectWired, prebuildScript,
            )
        }
    }

    private fun writePackageSwift(
        project: Project,
        xcFrameworkName: String,
        umbrellaTargetName: String,
        contributions: List<HostContribution>,
    ) {
        val packageDir = project.layout.buildDirectory.dir("lokal/spmPackage").get().asFile
        val sourcesDir = File(packageDir, "Sources/$umbrellaTargetName")
        sourcesDir.mkdirs()

        // A regular (non-binary) target needs at least one source file to exist;
        // this one carries no logic — its only job is to declare the dependencies
        // that tie the binary target and every vendor product into one product.
        writeIfChanged(
            File(sourcesDir, "Umbrella.swift"),
            "// GENERATED by the Lokal Payment SDK — do not edit.\n",
        )

        // Always the RELEASE XCFramework (build/XCFrameworks/release/), never debug.
        // A Package.swift binaryTarget has one static `path:` and SPM validates it while
        // resolving the package graph — before any Xcode Run Script phase runs — so there
        // is no way to swap the artifact per Debug/Release configuration at build time.
        // Instead we ship ONE release binary, exactly like a normal vendored SDK (Razorpay's
        // own Razorpay.xcframework is a single release build used in both Debug and Release
        // app builds). Config (debug/release) is orthogonal to slice (device/simulator), so
        // the release XCFramework — which still carries the ios-arm64 and ios-simulator
        // slices — links correctly in every Xcode configuration, simulator or device. The
        // only thing given up is stepping into the Kotlin SDK from an Xcode Debug build,
        // which is not part of this SDK's workflow. See docs/cocoapods-to-spm-migration-plan.md.
        val xcFrameworkDir = project.layout.buildDirectory
            .dir("XCFrameworks/release/$xcFrameworkName.xcframework").get().asFile
        val xcFrameworkRelPath = packageDir.toPath().toAbsolutePath().normalize()
            .relativize(xcFrameworkDir.toPath().toAbsolutePath().normalize())
            .toString()

        val vendorPackages = contributions.mapNotNull { it.vendorPackage }
        val sourceTargets = contributions.mapNotNull { it.sourceTarget }

        // Copy each first-party source target's .swift files into Sources/<name>/ so the
        // generated package compiles them straight into the app. The contributor already
        // materialized sourceDir (e.g. unzipped the module's iossrc Maven artifact) — see
        // SourceTarget. Each target's folder is rebuilt from scratch so a source file
        // removed upstream doesn't linger.
        sourceTargets.forEach { st ->
            val destDir = File(packageDir, "Sources/${st.name}")
            destDir.deleteRecursively()
            destDir.mkdirs()
            File(st.sourceDir).listFiles { f -> f.isFile && f.name.endsWith(".swift") }
                ?.forEach { src -> File(destDir, src.name).writeText(src.readText()) }
        }

        // Built as an explicit line list (mirroring SharedCocoapodsPlugin's Ruby
        // block generation) rather than a triple-quoted template with embedded
        // multi-line interpolation — trimIndent() can't reliably re-indent a block
        // whose own line count/content varies per host. Array literals use trailing
        // commas throughout (valid SwiftPM-manifest Swift) so per-element comma
        // bookkeeping doesn't break as vendor packages and source targets vary.
        val lines = mutableListOf(
            "// swift-tools-version:5.9",
            "// GENERATED by the Lokal Payment SDK — do not edit. Regenerated on every",
            "// Gradle sync; the app wires this folder in as a local Swift package ONCE",
            "// (XcodeGen/Tuist spec, or Xcode 'Add Local…') — see the generated",
            "// INTEGRATION.md beside this file, or docs/integrating-the-sdk.md.",
            "import PackageDescription",
            "",
            "let package = Package(",
            "    name: \"$umbrellaTargetName\",",
            "    platforms: [.iOS(.v16)],",
            "    products: [",
            "        .library(name: \"$umbrellaTargetName\", targets: [\"$umbrellaTargetName\"]),",
            "    ],",
            "    dependencies: [",
        )
        vendorPackages.forEach { v ->
            lines += "        .package(url: \"${v.url}\", exact: \"${v.exactVersion}\"),"
        }
        lines += listOf(
            "    ],",
            "    targets: [",
            "        .binaryTarget(name: \"$xcFrameworkName\", path: \"$xcFrameworkRelPath\"),",
        )
        // First-party Swift source targets, compiled straight into the package.
        sourceTargets.forEach { st ->
            lines += "        .target("
            lines += "            name: \"${st.name}\","
            if (st.linkedFrameworks.isEmpty()) {
                lines += "            path: \"Sources/${st.name}\""
            } else {
                lines += "            path: \"Sources/${st.name}\","
                lines += "            linkerSettings: ["
                st.linkedFrameworks.forEach { fw ->
                    lines += "                .linkedFramework(\"$fw\"),"
                }
                lines += "            ]"
            }
            lines += "        ),"
        }
        lines += listOf(
            "        .target(",
            "            name: \"$umbrellaTargetName\",",
            "            dependencies: [",
            "                \"$xcFrameworkName\",",
        )
        vendorPackages.forEach { v ->
            lines += "                .product(name: \"${v.productName}\", package: \"${v.packageName}\"),"
        }
        sourceTargets.forEach { st ->
            lines += "                \"${st.name}\","
        }
        lines += listOf(
            "            ],",
            "            path: \"Sources/$umbrellaTargetName\"",
            "        ),",
            "    ]",
            ")",
        )

        writeIfChanged(File(packageDir, "Package.swift"), lines.joinToString("\n") + "\n")
    }

    /**
     * Writes `INTEGRATION.md` beside the generated `Package.swift` — the app-specific,
     * regenerated-every-sync onboarding steps for THIS host's actual gateway selection: the
     * local-package path, the product to link vs. the module to `import` (deliberately
     * different names), the rebuild task, ready-to-paste XcodeGen/Tuist wiring, and any
     * per-gateway one-time steps contributed via [HostContribution.consumerNotes] (e.g.
     * Juspay's scheme pre-build action). The durable, hand-maintained companion is
     * docs/integrating-the-sdk.md; this file is the machine-generated, always-current view.
     * The lifecycle pointer is logged only when the file actually changes (e.g. a gateway was
     * added or removed) so a plain rebuild stays quiet.
     */
    private fun writeIntegrationNotes(
        project: Project,
        config: LokalPaymentSdkExtension,
        xcFrameworkName: String,
        umbrellaTargetName: String,
        contributions: List<HostContribution>,
        queriesSchemes: List<String>,
        plistPatched: Boolean,
        xcodeProjectWired: Boolean,
        prebuildScript: File?,
    ) {
        val packageDir = project.layout.buildDirectory.dir("lokal/spmPackage").get().asFile
        val assembleTask = "${project.path}:assemble${xcFrameworkName}ReleaseXCFramework"
        val packageAbsPath = packageDir.toPath().toAbsolutePath().normalize().toString()
        val notes = contributions.flatMap { it.consumerNotes }

        val md = mutableListOf(
            "# Integrating $umbrellaTargetName into your iOS app",
            "",
            "GENERATED by the Lokal Payment SDK — regenerated on every Gradle sync. Do not",
            "edit; changes are overwritten. It reflects *this* module's current gateway",
            "selection. Full hand-written guide: LokalPaymentSDK/docs/integrating-the-sdk.md",
            "",
            "## The names you need",
            "",
            "| Thing | Value |",
            "| --- | --- |",
            "| Local Swift package | `$packageAbsPath` |",
            "| Product to link | `$umbrellaTargetName` |",
            "| Swift import | `import $xcFrameworkName` |",
            "",
            "The product and the import differ on purpose: link the **$umbrellaTargetName**",
            "product, but write `import $xcFrameworkName` to call the SDK's API.",
            "",
            "## 1. Rebuild after any Kotlin change",
            "",
            "```",
            "./gradlew $assembleTask",
            "```",
            "",
            "SPM binary targets are prebuilt, so there is no per-Xcode-build Gradle step —",
            "re-run this whenever you change Kotlin, then build in Xcode as usual.",
        )

        // Section 2 mirrors the Info.plist note's two-branch shape: when the host set
        // `iosXcodeProject`, the package is wired for them and there's nothing to do by hand;
        // otherwise surface the manual "Add Local…" wiring — and the opt-in that automates it.
        md += ""
        md += "## 2. Add the local package (one-time)"
        md += ""
        if (xcodeProjectWired) {
            md += "The SDK wires this local package into the `.xcodeproj` you pointed"
            md += "`lokalPaymentSdk { iosXcodeProject = … }` at, on every Gradle sync — the"
            md += "`$umbrellaTargetName` product is added as a package product dependency for you."
            md += "The edit is idempotent (a project already wired is left untouched), so there is"
            md += "nothing to do by hand."
        } else {
            md += "Point your project at the package folder above. In a committed spec, use a path"
            md += "relative to the spec file (the demo uses `../composeApp/build/lokal/spmPackage`)."
            md += ""
            md += "### XcodeGen — project.yml"
            md += ""
            md += "```yaml"
            md += "packages:"
            md += "  $xcFrameworkName:"
            md += "    path: <relative-path-to>/build/lokal/spmPackage"
            md += "targets:"
            md += "  YourApp:"
            md += "    dependencies:"
            md += "      - package: $xcFrameworkName"
            md += "        product: $umbrellaTargetName"
            md += "```"
            md += ""
            md += "### Tuist — Project.swift"
            md += ""
            md += "```swift"
            md += "let project = Project("
            md += "    name: \"YourApp\","
            md += "    packages: [.local(path: \"<relative-path-to>/build/lokal/spmPackage\")],"
            md += "    targets: ["
            md += "        .target("
            md += "            name: \"YourApp\","
            md += "            // …"
            md += "            dependencies: [.package(product: \"$umbrellaTargetName\")]"
            md += "        ),"
            md += "    ]"
            md += ")"
            md += "```"
            md += ""
            md += "### Hand-managed .xcodeproj (Xcode's \"Add Local…\")"
            md += ""
            md += "*File ▸ Add Package Dependencies… ▸ Add Local…* → select the package folder"
            md += "above, then add the **$umbrellaTargetName** product to your app target. Or set"
            md += "`lokalPaymentSdk { iosXcodeProject = \"<path-to>.xcodeproj\" }` and the SDK does"
            md += "this for you on every sync (hand-managed `.xcodeproj` only — not XcodeGen/Tuist,"
            md += "which own their generated project)."
        }
        md += ""
        md += "## 3. Import and use"
        md += ""
        md += "```swift"
        md += "import $xcFrameworkName"
        md += "```"
        if (queriesSchemes.isNotEmpty()) {
            md += ""
            md += "## 4. Info.plist — UPI query schemes"
            md += ""
            if (plistPatched) {
                val plistPath = project.file(config.iosInfoPlist!!)
                    .toPath().toAbsolutePath().normalize().toString()
                md += "The SDK keeps `LSApplicationQueriesSchemes` in your `Info.plist` up to"
                md += "date on every Gradle sync (needed for `canOpenURL` UPI-app detection):"
                md += ""
                md += "`$plistPath`"
                md += ""
                md += "The merge is idempotent — it only adds schemes that aren't already there,"
                md += "and never touches your other entries. Nothing to do by hand."
            } else {
                md += "Add these schemes to `LSApplicationQueriesSchemes` in your app's"
                md += "`Info.plist` so `canOpenURL` can detect installed UPI apps:"
                md += ""
                md += "```xml"
                md += "<key>LSApplicationQueriesSchemes</key>"
                md += "<array>"
                queriesSchemes.forEach { md += "    <string>$it</string>" }
                md += "</array>"
                md += "```"
                md += ""
                md += "Or set `lokalPaymentSdk { iosInfoPlist = \"<path-to>/Info.plist\" }` and the"
                md += "SDK will merge them for you on every sync."
            }
        }
        if (prebuildScript != null) {
            md += ""
            md += "## 5. Xcode pre-build action (one-time)"
            md += ""
            md += "One or more gateways run a script before each Xcode build (e.g. Juspay's"
            md += "HyperSDK asset download). Register it **once** as a scheme pre-build action —"
            md += "new gateways plug into the same action, so you never edit this again:"
            md += ""
            md += "1. Xcode → Product → Scheme → Edit Scheme → Build → Pre-actions → **+** →"
            md += "   New Run Script Action."
            md += "2. Set **Provide build settings from** to your app target (so `\$BUILD_DIR` and"
            md += "   friends are in scope for the script)."
            md += "3. Script body:"
            md += ""
            md += "```sh"
            md += "\"${prebuildScript.toPath().toAbsolutePath().normalize()}\""
            md += "```"
            md += ""
            md += "In a committed XcodeGen/Tuist spec, reference the script by a path relative to"
            md += "the spec rather than the absolute one above."
        }
        if (notes.isNotEmpty()) {
            md += ""
            md += "## 6. Gateway-specific one-time setup"
            md += ""
            md += "Only the gateways this module enabled appear here."
            notes.forEach { note ->
                md += ""
                md += "### ${note.heading}"
                md += ""
                note.steps.forEach { step -> md += "- $step" }
            }
        }

        val integrationFile = File(packageDir, "INTEGRATION.md")
        val changed = writeIfChanged(integrationFile, md.joinToString("\n") + "\n")
        if (changed) {
            project.logger.lifecycle("LokalPaymentSDK: iOS integration steps updated → $integrationFile")
        }
    }

    /**
     * Materializes every gateway's [HostContribution.prebuildStep] into `prebuild.d/<name>.sh`
     * beside a generated `lokal-prebuild.sh` dispatcher, and returns the dispatcher (or null if
     * no gateway contributed a step). The app registers the dispatcher as one Xcode scheme
     * pre-build action; it runs each snippet in sorted order under `set -eu`, failing the build
     * loudly if any step fails. The SPM reincarnation of the CocoaPods managed `post_install`
     * dispatch. The `prebuild.d` dir is rebuilt from scratch each sync so a gateway that was
     * removed doesn't leave a stale snippet behind.
     */
    private fun writePrebuildDispatcher(project: Project, contributions: List<HostContribution>): File? {
        val packageDir = project.layout.buildDirectory.dir("lokal/spmPackage").get().asFile
        val stepsDir = File(packageDir, "prebuild.d")
        val dispatcher = File(packageDir, "lokal-prebuild.sh")
        val steps = contributions.mapNotNull { it.prebuildStep }

        stepsDir.deleteRecursively()
        if (steps.isEmpty()) {
            dispatcher.delete()
            return null
        }
        stepsDir.mkdirs()
        steps.forEach { step ->
            File(stepsDir, "${step.name}.sh").apply {
                writeText("#!/bin/sh\n" + step.script.trimEnd('\n') + "\n")
                setExecutable(true)
            }
        }
        dispatcher.writeText(PREBUILD_DISPATCHER)
        dispatcher.setExecutable(true)
        return dispatcher
    }

    /** Writes [file] only when its content differs; returns whether it wrote. */
    private fun writeIfChanged(file: File, content: String): Boolean {
        if (!file.exists() || file.readText() != content) {
            file.writeText(content)
            return true
        }
        return false
    }

    /**
     * Merges [schemes] into `LSApplicationQueriesSchemes` of the host's `Info.plist` when the
     * host opted in via `lokalPaymentSdk { iosInfoPlist = … }`; returns whether the plist is
     * SDK-managed (i.e. the property was set), regardless of whether this run actually added
     * anything new. Unset → returns false and the plist is left untouched, the schemes being
     * surfaced as an `INTEGRATION.md` note instead (see [writeIntegrationNotes]). The
     * SPM-flavored replacement for the CocoaPods-era `post_install` snippet that patched the
     * plist via `Xcodeproj::Plist`. Fails loudly if the configured path isn't a file, since an
     * explicit opt-in with a wrong path would otherwise silently do nothing.
     */
    private fun patchInfoPlistIfConfigured(
        project: Project,
        config: LokalPaymentSdkExtension,
        schemes: List<String>,
    ): Boolean {
        val path = config.iosInfoPlist ?: return false
        val plistFile = project.file(path)
        if (!plistFile.isFile) {
            throw GradleException(
                "lokalPaymentSdk { iosInfoPlist = \"$path\" } does not resolve to a file " +
                    "(looked at $plistFile). Point it at the committed Info.plist your app " +
                    "target uses, or remove the property to add the UPI query schemes by hand.",
            )
        }
        val added = mergeQueriesSchemes(plistFile, schemes)
        if (added > 0) {
            project.logger.lifecycle(
                "LokalPaymentSDK: merged $added UPI query scheme(s) into $plistFile",
            )
        }
        return true
    }

    /**
     * Wires the generated local Swift package into the host's hand-managed `.xcodeproj` when the
     * host opted in via `lokalPaymentSdk { iosXcodeProject = … }`; returns whether the project is
     * SDK-managed (i.e. the property was set), regardless of whether this run actually changed
     * anything. Unset → returns false and the `.xcodeproj` is left untouched, the "Add Local…"
     * step surfaced as an `INTEGRATION.md` note instead (see [writeIntegrationNotes]). The
     * `project.pbxproj` sibling of [patchInfoPlistIfConfigured]: an opt-in, idempotent edit of a
     * git-tracked file the host owns. Accepts either the `.xcodeproj` bundle or its inner
     * `project.pbxproj`. Fails loudly if the path isn't a readable pbxproj, mirroring the
     * plist path's fail-loud contract.
     *
     * Hand-managed `.xcodeproj` only (the demo). XcodeGen/Tuist hosts regenerate their `pbxproj`
     * from a spec and must declare the package there instead — they simply leave this unset,
     * exactly as they leave [LokalPaymentSdkExtension.iosInfoPlist] pointed at a committed plist
     * rather than a generated one.
     */
    private fun patchXcodeProjectIfConfigured(
        project: Project,
        config: LokalPaymentSdkExtension,
        umbrellaProductName: String,
    ): Boolean {
        val path = config.iosXcodeProject ?: return false
        val target = project.file(path)
        val pbxproj = when {
            target.isDirectory && target.name.endsWith(".xcodeproj") -> File(target, "project.pbxproj")
            else -> target // a project.pbxproj pointed at directly
        }
        if (!pbxproj.isFile) {
            throw GradleException(
                "lokalPaymentSdk { iosXcodeProject = \"$path\" } does not resolve to a " +
                    ".xcodeproj (or its project.pbxproj) — looked at $pbxproj. Point it at the " +
                    "hand-managed .xcodeproj your app uses, or remove the property to wire the " +
                    "local package by hand.",
            )
        }
        val packageDir = project.layout.buildDirectory.dir("lokal/spmPackage").get().asFile
        val changed = wireLocalPackage(pbxproj, packageDir, umbrellaProductName)
        if (changed) {
            project.logger.lifecycle(
                "LokalPaymentSDK: wired local package '$umbrellaProductName' into $pbxproj",
            )
        }
        return true
    }

    /**
     * Idempotently adds the local Swift package at [packageDir] to [pbxproj] as a package product
     * dependency named [umbrellaProductName] on the project's single application target. Returns
     * whether it wrote (false → already wired, file left byte-for-byte untouched — the
     * "if it is there don't do anything" contract).
     *
     * Faithful to what Xcode itself writes for an "Add Local…" (see the demo's project.pbxproj):
     * an `XCLocalSwiftPackageReference` (relativePath → [packageDir], resolved relative to the
     * directory containing the `.xcodeproj`), an `XCSwiftPackageProductDependency` for the
     * umbrella product, and a `PBXBuildFile` that links it — plus the three array entries that
     * reference them (`packageReferences` on the project, `packageProductDependencies` and the
     * Frameworks build phase `files` on the app target). Missing object sections / arrays are
     * created; existing ones are appended to. Object IDs are derived deterministically (SHA-1 of
     * the package path + product name + role) so re-runs stay stable and never collide.
     *
     * Editing pbxproj text is exactly the fragility D2 warned about, so this mirrors
     * [mergeQueriesSchemes]: targeted, formatting-preserving insertions (never a full reserialize)
     * scoped to the specific project/target objects by their IDs, so a plain sync is a zero-line
     * diff and a first wiring adds only the lines Xcode would. Fails loudly if the format predates
     * Xcode 15 (`objectVersion < 56`, which has no `XCLocalSwiftPackageReference`) or if the single
     * application target can't be unambiguously identified — no silent half-wiring.
     */
    private fun wireLocalPackage(pbxproj: File, packageDir: File, umbrellaProductName: String): Boolean {
        var text = pbxproj.readText()

        // relativePath is resolved by Xcode against the directory that CONTAINS the .xcodeproj
        // bundle (pbxproj → .xcodeproj → that dir), matching the demo's ../composeApp/... value.
        val projectSrcRoot = pbxproj.parentFile.parentFile
            ?: error("$pbxproj is not inside an .xcodeproj bundle")
        val relativePath = projectSrcRoot.toPath().toAbsolutePath().normalize()
            .relativize(packageDir.toPath().toAbsolutePath().normalize())
            .toString()
        val relPathValue = pbxValue(relativePath)

        // Idempotency: a reference already pointing at our package → nothing to do.
        if (text.contains("relativePath = $relPathValue;")) return false

        val objectVersion = Regex("""objectVersion = (\d+);""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (objectVersion == null || objectVersion < 56) {
            throw GradleException(
                "$pbxproj has objectVersion ${objectVersion ?: "?"}; local Swift package " +
                    "references (XCLocalSwiftPackageReference) need the Xcode 15 project format " +
                    "(objectVersion >= 56). Upgrade the project format in Xcode, or wire the " +
                    "local package by hand and leave lokalPaymentSdk { iosXcodeProject } unset.",
            )
        }

        // The one application target and its Frameworks build phase. Scoping every array edit to
        // these specific object IDs is what keeps the patch surgical on multi-target projects.
        val (appTargetBlock, appTargetId) = singleApplicationTarget(pbxproj, text)
        val frameworksPhaseId = Regex("""([0-9A-Fa-f]{24}) /\* Frameworks \*/""")
            .find(appTargetBlock)?.groupValues?.get(1)
            ?: throw GradleException(
                "app target in $pbxproj has no Frameworks build phase to link the package " +
                    "product into. Add one in Xcode, or wire the local package by hand.",
            )

        // Deterministic 24-hex object IDs (role-salted), guaranteed absent from the file.
        val localRefId = pbxId("$relativePath|localref", text)
        val productDepId = pbxId("$relativePath|$umbrellaProductName|productdep", text)
        val buildFileId = pbxId("$relativePath|$umbrellaProductName|buildfile", text)

        val localRefComment = "XCLocalSwiftPackageReference \"${packageDir.name}\""

        // 1. PBXBuildFile object (one-liner, exactly Xcode's shape) + its Frameworks files entry.
        // Newer Xcode projects (file-system-synchronized groups) can lack a PBXBuildFile section
        // entirely, so create it if absent — same as the two package sections added below.
        val buildFileObject = "\t\t$buildFileId /* $umbrellaProductName in Frameworks */ = " +
            "{isa = PBXBuildFile; productRef = $productDepId /* $umbrellaProductName */; };\n"
        text = addObjectToSection(text, "PBXBuildFile", buildFileObject)
        text = insertIntoArrayById(
            text, frameworksPhaseId, "files",
            "\t\t\t\t$buildFileId /* $umbrellaProductName in Frameworks */,\n", pbxproj,
        )

        // 2. XCLocalSwiftPackageReference object + the project's packageReferences entry. Added
        //    before the XCSwiftPackageProductDependency section below so, when both sections are
        //    freshly created, they land in Xcode's conventional alphabetical section order — no
        //    spurious reorder diff the first time the project is opened and saved in Xcode.
        val localRefObject =
            "\t\t$localRefId /* $localRefComment */ = {\n" +
            "\t\t\tisa = XCLocalSwiftPackageReference;\n" +
            "\t\t\trelativePath = $relPathValue;\n" +
            "\t\t};\n"
        text = addObjectToSection(text, "XCLocalSwiftPackageReference", localRefObject)
        val projectId = Regex("""([0-9A-Fa-f]{24}) /\* Project object \*/""")
            .find(text)?.groupValues?.get(1)
            ?: throw GradleException("$pbxproj has no PBXProject object to add packageReferences to.")
        text = insertIntoArrayById(
            text, projectId, "packageReferences",
            "\t\t\t\t$localRefId /* $localRefComment */,\n", pbxproj,
        )

        // 3. XCSwiftPackageProductDependency object + the target's packageProductDependencies entry.
        val productDepObject =
            "\t\t$productDepId /* $umbrellaProductName */ = {\n" +
            "\t\t\tisa = XCSwiftPackageProductDependency;\n" +
            "\t\t\tpackage = $localRefId /* $localRefComment */;\n" +
            "\t\t\tproductName = ${pbxValue(umbrellaProductName)};\n" +
            "\t\t};\n"
        text = addObjectToSection(text, "XCSwiftPackageProductDependency", productDepObject)
        text = insertIntoArrayById(
            text, appTargetId, "packageProductDependencies",
            "\t\t\t\t$productDepId /* $umbrellaProductName */,\n", pbxproj,
        )

        pbxproj.writeText(text)
        return true
    }

    /**
     * The `.xcodeproj`'s single `com.apple.product-type.application` target, as (block text, id).
     * Fails loudly on zero or multiple, since silently picking one would wire the wrong target.
     */
    private fun singleApplicationTarget(pbxproj: File, text: String): Pair<String, String> {
        val section = Regex(
            """/\* Begin PBXNativeTarget section \*/(.*?)/\* End PBXNativeTarget section \*/""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.groupValues?.get(1)
            ?: throw GradleException("$pbxproj has no PBXNativeTarget section — not an app project?")

        val blocks = Regex(
            """\t\t([0-9A-Fa-f]{24}) /\* .*? \*/ = \{.*?\n\t\t\};""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(section)
            .filter { it.value.contains("com.apple.product-type.application") }
            .map { it.value to it.groupValues[1] }
            .toList()

        return when (blocks.size) {
            1 -> blocks.single()
            0 -> throw GradleException(
                "$pbxproj has no application target to wire the package into.",
            )
            else -> throw GradleException(
                "$pbxproj has ${blocks.size} application targets; can't tell which to wire. " +
                    "Wire the local package by hand and leave lokalPaymentSdk { iosXcodeProject } unset.",
            )
        }
    }

    /**
     * Inserts [entry] into the `<key> = ( … )` array of the object identified by [objectId],
     * creating the array (placed before `productName`/`productRefGroup`, mirroring where Xcode
     * emits it) when the object doesn't declare it yet. Scoped to the one object whose block
     * starts with [objectId], so sibling targets are never touched. Array items are one indent
     * (a tab) deeper than the object's keys.
     */
    private fun insertIntoArrayById(
        text: String,
        objectId: String,
        key: String,
        entry: String,
        pbxproj: File,
    ): String {
        val block = Regex(
            """\t\t$objectId /\* .*? \*/ = \{.*?\n\t\t\};""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.value
            ?: throw GradleException("$pbxproj: object $objectId not found while adding $key.")

        val opening = "$key = (\n"
        val newBlock = if (block.contains(opening)) {
            block.replaceFirst(opening, opening + entry)
        } else {
            // Create the array. Anchor before the first key Xcode keeps after it, so the new
            // array lands in a natural spot; fall back to the object's closing brace.
            val arrayDecl = "\t\t\t$key = (\n$entry\t\t\t);\n"
            val anchor = when {
                block.contains("\t\t\tproductName = ") -> "\t\t\tproductName = "
                block.contains("\t\t\tproductRefGroup = ") -> "\t\t\tproductRefGroup = "
                else -> null
            }
            if (anchor != null) block.replaceFirst(anchor, arrayDecl + anchor)
            else block.replaceFirst("\n\t\t};", "\n$arrayDecl\t\t};")
        }
        return text.replace(block, newBlock)
    }

    /**
     * Appends [objectText] to the named object section, creating the section just before the
     * objects-dict close (`\t};\n\trootObject`) when absent. The create branch matches how Xcode
     * lays these trailing package sections out (see the demo's XCLocalSwiftPackageReference /
     * XCSwiftPackageProductDependency sections).
     */
    private fun addObjectToSection(text: String, sectionName: String, objectText: String): String {
        val begin = "/* Begin $sectionName section */"
        val end = "/* End $sectionName section */"
        return if (text.contains(begin)) {
            text.replace(end, objectText + end)
        } else {
            val section = "\n$begin\n$objectText$end\n"
            text.replace("\t};\n\trootObject = ", section + "\t};\n\trootObject = ")
        }
    }

    /** Quotes a pbxproj scalar value when it isn't a bare identifier/path token. */
    private fun pbxValue(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9._/-]+"))) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A deterministic 24-hex object ID from [seed], role-salted until absent from [existing]. */
    private fun pbxId(seed: String, existing: String): String {
        fun hex(s: String) = MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        var salt = 0
        var id = hex(seed)
        while (existing.contains(id)) id = hex("$seed#${salt++}")
        return id
    }

    /**
     * Idempotently adds [schemes] as `<string>` entries under the `LSApplicationQueriesSchemes`
     * array in [plistFile], creating the key/array if absent. Returns how many were newly added
     * (0 → already current, and the file is left untouched). Preserves the file's existing
     * formatting: parses with whitespace retained, appends only the new nodes with matching
     * indentation, and writes back without re-indenting — so a plain sync is a zero-line diff
     * and adding a scheme shows only the added lines. DTD loading is disabled so parsing never
     * reaches out to apple.com for the plist DOCTYPE.
     */
    private fun mergeQueriesSchemes(plistFile: File, schemes: List<String>): Int {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder().apply {
            // The plist DOCTYPE points at apple.com; resolve it to nothing so parsing stays offline.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val doc = builder.parse(plistFile)

        val rootDict = elementChildren(doc.documentElement).firstOrNull { it.tagName == "dict" }
            ?: error("$plistFile has no <dict> under <plist> — not a well-formed Info.plist.")

        // dict children are indented one unit; array items one unit deeper.
        val keyIndent = leadingWhitespace(rootDict) ?: "\n\t"
        val itemIndent = keyIndent + "\t"

        // Locate the LSApplicationQueriesSchemes <array> — the element sibling right after its
        // <key> — or synthesize the key/array pair at the end of the dict if it's absent.
        val dictChildren = elementChildren(rootDict)
        val keyIndex = dictChildren.indexOfFirst {
            it.tagName == "key" && it.textContent == QUERIES_SCHEMES_KEY
        }
        val array: Element = dictChildren.getOrNull(keyIndex + 1)
            ?.takeIf { keyIndex >= 0 && it.tagName == "array" }
            ?: run {
                val newArray = doc.createElement("array")
                val newKey = doc.createElement("key").apply { textContent = QUERIES_SCHEMES_KEY }
                val dictTail = trailingWhitespace(rootDict)
                insertBeforeOrAppend(rootDict, doc.createTextNode(keyIndent), dictTail)
                insertBeforeOrAppend(rootDict, newKey, dictTail)
                insertBeforeOrAppend(rootDict, doc.createTextNode(keyIndent), dictTail)
                insertBeforeOrAppend(rootDict, newArray, dictTail)
                newArray
            }

        val existing = elementChildren(array)
            .filter { it.tagName == "string" }
            .map { it.textContent }
            .toMutableSet()

        val arrayTail = trailingWhitespace(array) // the "\n\t" before </array>, if the array had entries
        var added = 0
        for (scheme in schemes) {
            if (!existing.add(scheme)) continue
            insertBeforeOrAppend(array, doc.createTextNode(itemIndent), arrayTail)
            insertBeforeOrAppend(array, doc.createElement("string").apply { textContent = scheme }, arrayTail)
            added++
        }
        // A freshly-created (previously empty) array has no closing-indent text node, so add one
        // — otherwise </array> would glue onto the last <string>.
        if (arrayTail == null && added > 0) array.appendChild(doc.createTextNode(keyIndent))

        if (added == 0) return 0

        writePlist(doc, plistFile)
        return added
    }

    private fun writePlist(doc: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "-//Apple//DTD PLIST 1.0//EN")
            setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "http://www.apple.com/DTDs/PropertyList-1.0.dtd")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            // We manage whitespace by hand (above), so leave re-indenting off for a minimal diff.
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        // The transformer packs the prolog onto one line; restore the conventional plist layout
        // (declaration / DOCTYPE / <plist> each on their own line) so the top of the file matches
        // how Xcode writes it.
        var out = writer.toString()
            // Match Apple's declaration exactly — the transformer adds a `standalone` attribute
            // the original plist doesn't carry.
            .replace(" standalone=\"no\"?>", "?>")
            .replace("?><!DOCTYPE", "?>\n<!DOCTYPE")
            .replaceFirst(Regex("""("[^"]*PropertyList-1\.0\.dtd">)<plist"""), "$1\n<plist")
        if (!out.endsWith("\n")) out += "\n"
        file.writeText(out)
    }

    /** The element (non-text) children of [node], in document order. */
    private fun elementChildren(node: Node): List<Element> {
        val out = ArrayList<Element>()
        val kids = node.childNodes
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(out::add)
        return out
    }

    /** The whitespace text node immediately before [parent]'s first element child (its indent), if any. */
    private fun leadingWhitespace(parent: Node): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element) {
                val prev = n.previousSibling
                return prev?.takeIf { it.nodeType == Node.TEXT_NODE && it.textContent.isBlank() }
                    ?.textContent
            }
        }
        return null
    }

    /** [parent]'s last child when it is a whitespace-only text node (the indent before its close tag). */
    private fun trailingWhitespace(parent: Node): Node? =
        parent.lastChild?.takeIf { it.nodeType == Node.TEXT_NODE && it.textContent.isBlank() }

    private fun insertBeforeOrAppend(parent: Node, child: Node, ref: Node?) {
        if (ref != null) parent.insertBefore(child, ref) else parent.appendChild(child)
    }

    private companion object {
        // The Maven group shared by every Lokal Payment SDK gateway module. Gating the host's
        // dependency scan on it is what isolates "which of our gateways did the host import".
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"

        const val QUERIES_SCHEMES_KEY = "LSApplicationQueriesSchemes"

        /**
         * The generated pre-build dispatcher. Host-independent (locates its own snippet dir via
         * `dirname "$0"`), so it's a constant rather than per-host template. Runs every `.sh`
         * snippet in `prebuild.d` in sorted order; `set -eu` makes any failing step fail the build.
         */
        val PREBUILD_DISPATCHER = """
            #!/bin/sh
            # GENERATED by the Lokal Payment SDK — do not edit. Regenerated on every Gradle sync.
            #
            # One Xcode scheme pre-build action for every gateway's iOS build-time setup. Add
            # this script ONCE as a scheme pre-build action, with "Provide build settings from
            # <your app target>" enabled so build settings (${'$'}BUILD_DIR, ${'$'}SRCROOT, …) are in
            # scope. Each gateway that needs build-time work drops a snippet in prebuild.d/; this
            # runs them all, in order, and fails the build loudly if any step fails.
            set -eu
            steps_dir="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)/prebuild.d"
            [ -d "${'$'}steps_dir" ] || exit 0
            for step in "${'$'}steps_dir"/*.sh; do
              [ -e "${'$'}step" ] || continue
              echo "Lokal Payment SDK: prebuild step ${'$'}(basename -- "${'$'}step")"
              sh "${'$'}step"
            done
        """.trimIndent() + "\n"

        /**
         * The UPI apps the SDK checks for with `canOpenURL` before offering a UPI-intent
         * redirect — every host gets these regardless of gateway (UPI detection is a `:shared`
         * concern). Kept verbatim from the CocoaPods-era `shared-query-schemes.rb`.
         */
        val BASELINE_UPI_QUERY_SCHEMES = listOf(
            "credpay", "phonepe", "paytmmp", "tez", "bhim", "myairtel", "slice-upi",
            "ppe", "amazonpay", "kiwi", "navipay", "mobikwik", "popclubapp", "super",
            "postpe", "jupiter", "hdfcbanknb", "aunb", "imobileappnb", "simplypayupi",
            "tnupi", "magnetapp", "lxme", "indmoney", "whatsapp", "canaraaipe", "fpupi",
            "scapia", "salaryse", "bajajpayupi", "curieapp", "aufmobile", "devtools",
            "cugext",
        )
    }
}
