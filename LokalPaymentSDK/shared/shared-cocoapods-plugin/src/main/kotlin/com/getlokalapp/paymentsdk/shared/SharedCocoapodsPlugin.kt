package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalPostInstallSnippets
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.concurrent.Callable

/**
 * Sets up an iOS host to consume first-party Lokal pod sources shipped over Maven,
 * with zero per-module configuration. Applied to the module that owns the iOS
 * `cocoapods {}` block and produces the umbrella framework (e.g. `composeApp`).
 *
 * Registers three tasks:
 *  - `unpackIosPodSources` — discovers every `com.getlokalapp.paymentsdk:*` dependency
 *    the host declares, leniently resolves each one's `iossrc`-classifier jar (modules
 *    that don't publish one are silently skipped), and unzips it into
 *    `build/iosPodSources/<module>/`. See each SDK module's
 *    `registerIosPodSourcePublication(...)` (buildSrc IosPodSource.kt) for the producer side.
 *  - `generateLokalPodsRuby` — writes `build/lokal/lokal_ios_pods.rb`, a helper the
 *    Podfile `require_relative`s. Its `lokal_ios_pods` method declares a `:path` pod for
 *    every unzipped pod source, deriving the pod name from the `.podspec` filename — so
 *    there's no per-pod mapping anywhere.
 *  - `prepareLokalIosPods` — umbrella (`dependsOn` the two above); the single entry point
 *    the Podfile invokes at evaluation time.
 *
 * It also *manages the consumer Podfile*: on every Gradle sync (configuration phase) it
 * writes three marker-wrapped regions into `../iosApp/Podfile` — a top-level bootstrap that
 * runs `prepareLokalIosPods` and `require_relative`s the generated helper, a `lokal_ios_pods`
 * call declared once at the Podfile root — CocoaPods' implicit root abstract target makes it
 * inherited by every target — and a snippet-dispatcher region nested inside a `post_install`
 * block (see [LokalPostInstallSnippets]). That third one deliberately does **not** claim the
 * whole `post_install do |installer| ... end` — CocoaPods' `post_install` hook doesn't chain
 * (a second one silently replaces the first), so if a host already has their own
 * `post_install` block, our markers wrap only the inner dispatch lines and get inserted
 * *inside* the host's existing block, leaving whatever else the host put there untouched. A
 * wrapper is fabricated only when the host has none. The dispatch itself globs and `eval`s
 * whatever optional Ruby snippet each gateway contributor dropped in `build/lokal/postInstall/`,
 * so no gateway needs to touch `post_install` directly either. Everything between the markers
 * is regenerated each sync (manual edits are overwritten), and the relative paths are computed
 * from the Gradle model so they can't drift. `setupLokalPodfile` runs the same patch on demand
 * from the CLI.
 *
 * This can't remove the Podfile's `pod` line entirely: CocoaPods forbids `:path` in a
 * podspec's `spec.dependency`, so a local pod must be declared in a Podfile, not injected
 * into the podspec the way the vendor `*-cocoapods-host` plugins inject trunk pods.
 */
class SharedCocoapodsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val unpack = project.tasks.register("unpackIosPodSources", Sync::class.java) { task ->
            task.into(project.layout.buildDirectory.dir("iosPodSources"))
            // Declared dependencies only — reading these does not resolve any
            // configuration. Each module's iossrc is pulled at that module's own
            // declared version, so this plugin needs no baked-in SDK version.
            val sdkDeps = project.configurations
                .flatMap { it.dependencies }
                .filter { it.group == SDK_GROUP && it.version != null }
                .distinctBy { it.name }
            sdkDeps.forEach { dep ->
                // `@jar` = artifact-only (skip the KMP variant/metadata + klib graph);
                // the lenient view yields nothing for modules without an iossrc classifier.
                val srcJar = project.configurations.detachedConfiguration(
                    project.dependencies.create("$SDK_GROUP:${dep.name}:${dep.version}:iossrc@jar"),
                ).apply { isTransitive = false }
                    .incoming.artifactView { it.isLenient = true }.files
                task.from(Callable { srcJar.files.map { project.zipTree(it) } }) { spec ->
                    spec.into(dep.name)
                    spec.exclude("META-INF/**")
                }
            }
        }

        val generateRuby = project.tasks.register("generateLokalPodsRuby") { task ->
            val outFile = project.layout.buildDirectory.file("lokal/lokal_ios_pods.rb")
            task.outputs.file(outFile)
            task.doLast {
                outFile.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(RUBY_HELPER)
                }
            }
        }

        project.tasks.register("prepareLokalIosPods") { task ->
            task.dependsOn(unpack, generateRuby)
        }

        // --- Consumer Podfile management -------------------------------------------------
        // Everything below is computed at configuration time (plain File/String values), so
        // both the on-sync `afterEvaluate` hook and the `setupLokalPodfile` task are
        // configuration-cache friendly — neither touches `Project` at execution time.
        val podfile = project.file("../iosApp/Podfile")
        val podfileDir = podfile.parentFile
        fun rel(to: File): String =
            podfileDir.toPath().toAbsolutePath().normalize()
                .relativize(to.toPath().toAbsolutePath().normalize())
                .toString()

        val gradlewRel = rel(File(project.rootDir, "gradlew"))
        val rootRel = rel(project.rootDir)
        val rubyRel = rel(project.layout.buildDirectory.file("lokal/lokal_ios_pods.rb").get().asFile)
        val taskPath = "${project.path}:prepareLokalIosPods"

        val bootstrapBlock = listOf(
            "$BOOTSTRAP_START (managed by the Lokal Payment SDK on Gradle sync) — DO NOT EDIT OR DELETE.",
            "# Regenerated on every Gradle sync; manual changes between these markers are overwritten.",
            "# Required to run the Lokal Payment SDK. Unpacks every Maven-sourced iOS pod source AND",
            "# generates the pod helper via the Lokal Payment SDK Gradle plugin, then loads it. Runs at",
            "# Podfile-evaluation time (NOT pre_install): require_relative below needs the file present.",
            "unless system(\"$gradlewRel\", \"-p\", \"$rootRel\", \"$taskPath\")",
            "  raise \"prepareLokalIosPods failed — are the SDK iossrc artifacts published to Maven?\"",
            "end",
            "require_relative '$rubyRel'",
            BOOTSTRAP_END,
        )

        val podsBlock = listOf(
            "$PODS_START (managed by the Lokal Payment SDK on Gradle sync) — DO NOT EDIT OR DELETE.",
            "# Regenerated every sync; manual edits between these markers are overwritten. Required to",
            "# run the Lokal Payment SDK. Declared at the Podfile root so CocoaPods' implicit root",
            "# abstract target makes these pods inherited by EVERY target. Adds a :path pod for each",
            "# first-party pod auto-discovered from Maven (pod name = .podspec filename).",
            "lokal_ios_pods",
            "$PODS_END",
        )

        // Nested one level inside a post_install block — either a host's pre-existing one, or
        // one this plugin fabricates when there isn't one (see upsertPostInstall) — so these
        // lines are indented for readability either way, and never include the surrounding
        // `post_install do |installer|` / `end` themselves (those stay outside our markers, so
        // a host's own pre-existing block, and anything else in it, is never touched).
        val postInstallDirRel = rel(project.layout.buildDirectory.dir(LokalPostInstallSnippets.BUILD_RELATIVE_DIR).get().asFile)
        val postInstallInner = listOf(
            "  $POST_INSTALL_START (managed by the Lokal Payment SDK on Gradle sync) — DO NOT EDIT OR DELETE.",
            "  # Regenerated every sync; manual edits between these markers are overwritten. Dispatches",
            "  # every gateway's optional post-install Ruby snippet from here instead of any gateway",
            "  # claiming this whole post_install block itself — see",
            "  # com.getlokalapp.paymentsdk.host.LokalPostInstallSnippets for why.",
            "  Dir.glob(File.join(__dir__, '$postInstallDirRel/*.rb')).sort.each do |f|",
            "    eval(File.read(f), binding, f)",
            "  end",
            "  $POST_INSTALL_END",
        )

        project.tasks.register("setupLokalPodfile") { task ->
            task.group = "lokal payment sdk"
            task.description = "Writes/refreshes the Lokal Payment SDK managed blocks in the consumer Podfile."
            task.doLast {
                task.logger.lifecycle(patchPodfile(podfile, bootstrapBlock, podsBlock, postInstallInner))
                writeQuerySchemesSnippet(project)
            }
        }

        // Auto-run on every Gradle sync (the configuration phase re-runs on sync).
        project.afterEvaluate {
            val msg = patchPodfile(podfile, bootstrapBlock, podsBlock, postInstallInner)
            if (msg.contains("patched")) project.logger.lifecycle(msg) else project.logger.info(msg)
            writeQuerySchemesSnippet(project)
        }
    }

    /**
     * Drops `build/lokal/postInstall/shared-query-schemes.rb` — registers
     * `LSApplicationQueriesSchemes` entries for the UPI apps a host needs to `canOpenURL`
     * against (presence-check before attempting a UPI intent redirect) in the host app's
     * `Info.plist`. Unlike the gateway-specific contributors (e.g.
     * `com.getlokalapp.paymentsdk.juspay.host.JuspayHostContributor`'s URL-scheme snippet),
     * this is **not** gated on any particular gateway module — every host applying this
     * plugin gets these UPI app queries regardless of which gateway(s) it imports, since
     * UPI intent resolution is a `:shared` concern, not a per-gateway one.
     *
     * Uses `Xcodeproj::Plist` for safe nested-plist mutation instead of a text/regex
     * patch, and merges into any existing `LSApplicationQueriesSchemes` array rather than
     * overwriting it, so entries another SDK/host already declared survive. Idempotent:
     * re-running (every sync / `pod install`) only adds schemes that aren't already
     * present.
     */
    private fun writeQuerySchemesSnippet(project: Project) {
        val dir = project.layout.buildDirectory.dir(LokalPostInstallSnippets.BUILD_RELATIVE_DIR).get().asFile
        dir.mkdirs()
        dir.resolve("shared-query-schemes.rb").writeText(
            """
            # Generated by the Lokal Payment SDK — do not edit. Registers the UPI app query
            # schemes needed in LSApplicationQueriesSchemes for canOpenURL presence checks
            # before a UPI intent redirect, in the host app's Info.plist.
            installer.aggregate_targets.each do |aggregate_target|
              aggregate_target.user_project.native_targets.each do |native_target|
                next unless native_target.product_type == 'com.apple.product-type.application'

                native_target.build_configurations.each do |config|
                  info_plist_rel = config.build_settings['INFOPLIST_FILE']
                  next if info_plist_rel.nil?

                  info_plist_path = File.expand_path(info_plist_rel, aggregate_target.user_project.project_dir)
                  next unless File.exist?(info_plist_path)

                  plist = Xcodeproj::Plist.read_from_path(info_plist_path)

                  upi_query_schemes = [
                    'credpay', 'phonepe', 'paytmmp', 'tez', 'bhim', 'myairtel', 'slice-upi',
                    'ppe', 'amazonpay', 'kiwi', 'navipay', 'mobikwik', 'popclubapp', 'super',
                    'postpe', 'jupiter', 'hdfcbanknb', 'aunb', 'imobileappnb', 'simplypayupi',
                    'tnupi', 'magnetapp', 'lxme', 'indmoney', 'whatsapp', 'canaraaipe', 'fpupi',
                    'scapia', 'salaryse', 'bajajpayupi', 'curieapp', 'aufmobile', 'devtools',
                    'cugext'
                  ]

                  schemes = plist['LSApplicationQueriesSchemes'] || []
                  upi_query_schemes.each { |scheme| schemes << scheme unless schemes.include?(scheme) }
                  plist['LSApplicationQueriesSchemes'] = schemes

                  Xcodeproj::Plist.write_to_path(plist, info_plist_path)
                end
              end
            end
            """.trimIndent() + "\n",
        )
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"

        const val BOOTSTRAP_START = "# >>> lokal-payment-sdk bootstrap"
        const val BOOTSTRAP_END = "# <<< lokal-payment-sdk bootstrap"
        const val PODS_START = "# >>> lokal-payment-sdk pods"
        const val PODS_END = "# <<< lokal-payment-sdk pods"
        const val POST_INSTALL_START = "# >>> lokal-payment-sdk post_install"
        const val POST_INSTALL_END = "# <<< lokal-payment-sdk post_install"

        // No `$` appears here, so the triple-quoted string needs no escaping. The Ruby
        // `#{...}` below is Ruby interpolation, not Kotlin's `${...}`.
        val RUBY_HELPER = """
            # Generated by the Lokal Payment SDK — do not edit.
            # Declares every first-party iOS pod whose source was unpacked from Maven into
            # build/iosPodSources/<module>/ by :composeApp:unpackIosPodSources. The pod name
            # is the .podspec filename, so there's no per-pod name mapping. `__dir__` keeps
            # the source lookup correct regardless of where the Podfile requiring this file
            # lives; the :path is then made relative to the Podfile dir (Dir.pwd during
            # `pod install`) so the committed Podfile.lock stays portable across machines.
            require 'pathname'
            def lokal_ios_pods
              root = File.expand_path('../iosPodSources', __dir__)
              Dir.glob("#{root}/**/*.podspec").each do |spec|
                rel = Pathname.new(File.dirname(spec)).relative_path_from(Pathname.pwd).to_s
                pod File.basename(spec, '.podspec'), :path => rel
              end
            end
        """.trimIndent() + "\n"
    }
}

/**
 * Idempotently writes three managed regions into [podfile]: a bootstrap region and a pods
 * region, both at the Podfile root before the first `target` (runs the Gradle task +
 * `require_relative`s the helper; `lokal_ios_pods`, inherited by every target via CocoaPods'
 * implicit root abstract target) — and a post-install dispatch region, nested inside a
 * `post_install` block (see [upsertPostInstall]; the single dispatcher for every gateway's
 * optional post-install snippet, see [LokalPostInstallSnippets]). Marker-delimited regions are
 * replaced/regenerated in place — and a pods block left inside a target by an older version is
 * relocated to the root. Writes only when the text actually changes. Never throws on a missing
 * or target-less Podfile (reported and skipped so a Gradle sync isn't broken).
 */
private fun patchPodfile(podfile: File, bootstrap: List<String>, pods: List<String>, postInstallInner: List<String>): String {
    if (!podfile.exists()) return "Lokal: Podfile not found at ${podfile.path} — skipped"

    val original = podfile.readText()
    val lines = original.split("\n").toMutableList()

    val bootstrapAction = replaceOrInsert(
        lines,
        startToken = "# >>> lokal-payment-sdk bootstrap",
        endToken = "# <<< lokal-payment-sdk bootstrap",
        block = bootstrap,
        insertAt = { ls ->
            val t = ls.indexOfFirst { it.trimStart().startsWith("target ") }
            if (t < 0) ls.size else t
        },
    )

    val podsAction = syncPodsAtRoot(lines, pods)

    val postInstallAction = upsertPostInstall(lines, postInstallInner)

    val patched = lines.joinToString("\n")
    val summary = "bootstrap=$bootstrapAction, pods=$podsAction, post_install=$postInstallAction"
    if (patched == original) return "Lokal Podfile already up to date ($summary)"
    podfile.writeText(patched)
    return "Lokal Podfile patched ($summary)"
}

private val POST_INSTALL_LINE = Regex("""^post_install\s+do\b""")

/**
 * Ensures [inner] (the snippet-dispatch lines, never the `post_install do |installer|` / `end`
 * wrapper itself) runs exactly once per `pod install`, cooperating with a host-authored
 * `post_install do ... end` block if one already exists instead of assuming ownership of the
 * whole thing — CocoaPods' `post_install` hook doesn't chain, so a second, independent
 * `post_install do ... end` would silently clobber whichever one CocoaPods evaluates last, with
 * no error. A wrapper is fabricated (appended at the end of the file) only when the Podfile has
 * none. Also migrates a Podfile still on an older version of this plugin, where the markers
 * wrapped the *entire* `post_install do ... end` — that wrapper is stripped along with the
 * managed region before re-attaching cleanly, rather than leaving orphaned `post_install do` /
 * `end` lines behind. Mutates [lines] in place; returns a one-line status.
 */
private fun upsertPostInstall(lines: MutableList<String>, inner: List<String>): String {
    var migrating = false
    val start = lines.indexOfFirst { it.trimStart().startsWith("# >>> lokal-payment-sdk post_install") }
    if (start >= 0) {
        val end = (start until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("# <<< lokal-payment-sdk post_install") }
            ?: return "corrupt (found start without end; left unchanged)"
        val existing = lines.subList(start, end + 1).toList()
        val isOldWrapperShape = existing.any { POST_INSTALL_LINE.containsMatchIn(it.trimStart()) }
        if (!isOldWrapperShape) {
            if (existing == inner) return "unchanged"
            repeat(end - start + 1) { lines.removeAt(start) }
            lines.addAll(start, inner)
            return "updated"
        }
        repeat(end - start + 1) { lines.removeAt(start) }
        migrating = true
    }

    val hostPostInstall = lines.indexOfFirst { POST_INSTALL_LINE.containsMatchIn(it.trimStart()) }
    val prefix = if (migrating) "migrated, " else ""
    return if (hostPostInstall >= 0) {
        lines.addAll(hostPostInstall + 1, inner)
        "${prefix}inserted inside existing post_install"
    } else {
        // Trailing "" so the file still ends in a newline (matches replaceOrInsert's insert path).
        lines.addAll(lines.size, listOf("post_install do |installer|") + inner + listOf("end", ""))
        "${prefix}inserted (new post_install block)"
    }
}

/**
 * Ensures the pods [block] is present exactly once at the Podfile root (before the first
 * `target`), where CocoaPods' implicit root abstract target makes the pods inherited by every
 * concrete target. Every existing managed pods region is stripped first — including one left
 * inside a target by an older version, which is thereby relocated to the root — so a stale
 * block, a manual edit, or a removed target can't leave leftovers or duplicates. Mutates
 * [lines] in place; returns a one-line status.
 */
private fun syncPodsAtRoot(lines: MutableList<String>, block: List<String>): String {
    // Strip every existing managed pods region wherever it currently sits (e.g. an older
    // version put it inside the first target), plus one trailing blank line so repeated
    // syncs don't accumulate blanks.
    var stripped = 0
    while (true) {
        val start = lines.indexOfFirst { it.trimStart().startsWith("# >>> lokal-payment-sdk pods") }
        if (start < 0) break
        val end = (start until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("# <<< lokal-payment-sdk pods") }
            ?: return "corrupt (found start without end; left unchanged)"
        repeat(end - start + 1) { lines.removeAt(start) }
        if (start < lines.size && lines[start].isBlank()) lines.removeAt(start)
        stripped++
    }

    // Insert once at the root, right before the first target, so it lands in the implicit
    // root abstract target. Falls back to end-of-file when there's no target yet.
    val t = lines.indexOfFirst { it.trimStart().startsWith("target ") }
    val at = if (t < 0) lines.size else t
    lines.addAll(at, block + "")
    return if (stripped > 1) "at root (consolidated $stripped blocks)" else "at root"
}

/**
 * Replaces the region between [startToken]/[endToken] with [block], or seeds [block] at the
 * index returned by [insertAt] (with a trailing blank line for readability) when the markers
 * are absent. Mutates [lines] in place and returns what it did.
 */
private fun replaceOrInsert(
    lines: MutableList<String>,
    startToken: String,
    endToken: String,
    block: List<String>,
    insertAt: (List<String>) -> Int,
): String {
    val start = lines.indexOfFirst { it.trimStart().startsWith(startToken) }
    if (start >= 0) {
        val end = (start until lines.size).firstOrNull { lines[it].trimStart().startsWith(endToken) }
            ?: return "corrupt (found start without end; left unchanged)"
        val existing = lines.subList(start, end + 1).toList()
        if (existing == block) return "unchanged"
        repeat(end - start + 1) { lines.removeAt(start) }
        lines.addAll(start, block)
        return "updated"
    }

    val at = insertAt(lines)
    if (at < 0) return "skipped (no target found)"
    lines.addAll(at, block + "")
    return "inserted"
}
