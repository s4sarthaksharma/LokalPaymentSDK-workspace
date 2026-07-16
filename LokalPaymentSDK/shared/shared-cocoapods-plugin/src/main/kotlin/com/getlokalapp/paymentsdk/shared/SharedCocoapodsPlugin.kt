package com.getlokalapp.paymentsdk.shared

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
 * writes two marker-wrapped blocks into `../iosApp/Podfile` — a top-level bootstrap that
 * runs `prepareLokalIosPods` and `require_relative`s the generated helper, and a
 * `lokal_ios_pods` call declared once at the Podfile root — CocoaPods' implicit root
 * abstract target makes it inherited by every target. Everything
 * between the markers is regenerated each sync (manual edits are overwritten), and the
 * four relative paths in the bootstrap are computed from the Gradle model so they can't
 * drift. `setupLokalPodfile` runs the same patch on demand from the CLI.
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

        project.tasks.register("setupLokalPodfile") { task ->
            task.group = "lokal payment sdk"
            task.description = "Writes/refreshes the Lokal Payment SDK managed blocks in the consumer Podfile."
            task.doLast { task.logger.lifecycle(patchPodfile(podfile, bootstrapBlock, podsBlock)) }
        }

        // Auto-run on every Gradle sync (the configuration phase re-runs on sync).
        project.afterEvaluate {
            val msg = patchPodfile(podfile, bootstrapBlock, podsBlock)
            if (msg.contains("patched")) project.logger.lifecycle(msg) else project.logger.info(msg)
        }
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"

        const val BOOTSTRAP_START = "# >>> lokal-payment-sdk bootstrap"
        const val BOOTSTRAP_END = "# <<< lokal-payment-sdk bootstrap"
        const val PODS_START = "# >>> lokal-payment-sdk pods"
        const val PODS_END = "# <<< lokal-payment-sdk pods"

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
 * Idempotently writes two managed blocks into [podfile], both at the Podfile root before the
 * first `target`: a bootstrap region (runs the Gradle task + `require_relative`s the helper)
 * and a pods region (`lokal_ios_pods`, inherited by every target via CocoaPods' implicit root
 * abstract target). Marker-delimited regions are replaced/regenerated in place — and a pods
 * block left inside a target by an older version is relocated to the root. Writes only when
 * the text actually changes. Never throws on a missing or target-less Podfile (reported and
 * skipped so a Gradle sync isn't broken).
 */
private fun patchPodfile(podfile: File, bootstrap: List<String>, pods: List<String>): String {
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

    val patched = lines.joinToString("\n")
    val summary = "bootstrap=$bootstrapAction, pods=$podsAction"
    if (patched == original) return "Lokal Podfile already up to date ($summary)"
    podfile.writeText(patched)
    return "Lokal Podfile patched ($summary)"
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
