package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.PrebuildStep
import org.gradle.api.Project
import java.io.File

/**
 * Picks the host's Kotlin XCFramework per Xcode configuration — Debug builds link the debug
 * binary, everything else links release — without the host declaring anything.
 *
 * ## Why a staged copy at all
 *
 * A `Package.swift` `binaryTarget` has exactly one static `path:`, and SwiftPM validates it
 * while *resolving the package graph*. Resolution runs before scheme pre-actions and before
 * every build phase (confirmed in a real Xcode build log: "Resolving package dependencies…"
 * precedes "Run pre-actions"), and it happens once per workspace, not once per configuration —
 * `$CONFIGURATION` does not exist at manifest-evaluation time. So the manifest cannot name a
 * per-configuration path, and nothing running inside the build can change which path SwiftPM
 * validated.
 *
 * What it *can* do is change what lives at that path. [writePackageSwift] therefore points the
 * `binaryTarget` at a fixed `XCFrameworks/current/` location, and the pre-build step from
 * [kotlinXCFrameworkPrebuildStep] restages that location from `XCFrameworks/debug/` or
 * `XCFrameworks/release/` before the targets build. Declaring both variants as separate binary
 * targets is not an alternative: both artifacts would have to exist at resolve time (paying both
 * Kotlin/Native link costs), and SPM product dependencies are not configuration-conditional.
 *
 * ## Why the mtime bump is load-bearing
 *
 * Xcode's staleness check on a local `binaryTarget` xcframework is **mtime-based**, established
 * empirically against Xcode 26.5 with two xcframeworks whose headers differed, so that "which
 * variant did Xcode actually read" was decidable from whether compilation succeeded:
 *
 *  - swap that preserves source mtimes (`rsync -a --delete`) → **stale**, Xcode kept using the
 *    previously staged binary on a warm DerivedData;
 *  - swap that freshens mtimes (`rsync` + `touch`, or delete + copy) → picked up, and repeated
 *    `debug → release → debug` switching was correct every time;
 *  - retargeting a symlink → **stale**; Xcode caches through the symlink, which is why this
 *    stages a real copy instead of the far cheaper symlink flip.
 *
 * A staging step that copied without touching would work on a cold build and then silently serve
 * a stale Kotlin binary on every warm build — the worst possible failure mode — so [stageXCFramework]
 * always freshens timestamps on anything it writes.
 */
internal const val STAGE_TASK_BASE = "lokalStageKotlinXCFramework"

/** The fixed path the generated `Package.swift` binary target points at. */
private const val STAGED_VARIANT = "current"

private fun xcFrameworkVariantDir(project: Project, variant: String, name: String): File =
    project.layout.buildDirectory.dir("XCFrameworks/$variant/$name.xcframework").get().asFile

internal fun stagedXCFrameworkDir(project: Project, name: String): File =
    xcFrameworkVariantDir(project, STAGED_VARIANT, name)

private fun stagingStampFile(project: Project): File =
    project.layout.buildDirectory.file("XCFrameworks/.lokal-staged").get().asFile

/**
 * Registers `lokalStageKotlinXCFrameworkDebug` / `…Release`, each assembling its variant's
 * XCFramework and then staging it into `XCFrameworks/current/`. One task is what the pre-build
 * step invokes and what `INTEGRATION.md` tells a new developer to run once after cloning (the
 * staged copy has to exist before Xcode's first resolve, which no pre-action can precede).
 *
 * Every value the task action needs is captured as a [File] outside the action so the task stays
 * configuration-cache compatible — no `Project` reference leaks into execution.
 */
internal fun registerXCFrameworkStagingTasks(project: Project, xcFrameworkName: String) {
    listOf("Debug", "Release").forEach { variant ->
        val source = xcFrameworkVariantDir(project, variant.lowercase(), xcFrameworkName)
        val staged = stagedXCFrameworkDir(project, xcFrameworkName)
        val stamp = stagingStampFile(project)
        project.tasks.register("$STAGE_TASK_BASE$variant") { task ->
            task.group = "lokal payment sdk"
            task.description = "Assembles the $variant $xcFrameworkName XCFramework and stages " +
                "it as the binary the generated Swift package links."
            task.dependsOn("assemble$xcFrameworkName${variant}XCFramework")
            task.doLast {
                stageXCFramework(source, staged, stamp, variant.lowercase())
            }
        }
    }
}

/**
 * Best-effort seed of `XCFrameworks/current/` at configuration time, so a host that has already
 * assembled *some* variant is resolvable in Xcode without first running a staging task. Prefers
 * the most recently assembled variant — that's the one the developer last asked for. Does nothing
 * when nothing has been assembled yet (INTEGRATION.md covers that first run) or when a staged copy
 * is already present, which is the common case and must stay free of side effects.
 */
internal fun seedStagedXCFrameworkIfMissing(project: Project, xcFrameworkName: String) {
    val staged = stagedXCFrameworkDir(project, xcFrameworkName)
    if (!staged.listFiles().isNullOrEmpty()) return
    val newest = listOf("release", "debug")
        .map { it to xcFrameworkVariantDir(project, it, xcFrameworkName) }
        .filter { (_, dir) -> dir.isDirectory }
        .maxByOrNull { (_, dir) -> dir.lastModified() }
        ?: return
    stageXCFramework(newest.second, staged, stagingStampFile(project), newest.first)
}

/**
 * Replaces [staged] with the contents of [source] and freshens every timestamp it wrote, so
 * Xcode's mtime-based staleness check actually notices (see this file's header). Skips the copy
 * when [stamp] shows the same variant and an unchanged source tree, keeping no-op builds cheap —
 * an XCFramework is hundreds of megabytes, and this runs before every Xcode build.
 */
private fun stageXCFramework(source: File, staged: File, stamp: File, variant: String) {
    check(source.isDirectory) {
        "Lokal Payment SDK: expected an assembled XCFramework at $source. Assemble it with the " +
            "$STAGE_TASK_BASE${variant.replaceFirstChar { it.uppercase() }} task."
    }
    val signature = "$variant\n" + source.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .joinToString("\n") { "${it.relativeTo(source).path}:${it.length()}:${it.lastModified()}" }
        .hashCode()
    if (staged.isDirectory && stamp.isFile && stamp.readText() == signature) return

    staged.deleteRecursively()
    staged.parentFile.mkdirs()
    source.copyRecursively(staged, overwrite = true)
    val now = System.currentTimeMillis()
    staged.walkBottomUp().forEach { it.setLastModified(now) }
    stamp.parentFile.mkdirs()
    stamp.writeText(signature)
}

/**
 * The SDK's own pre-build step: maps `$CONFIGURATION` onto a Kotlin variant and delegates to the
 * staging task, so the assemble-and-stage logic lives in exactly one place (Kotlin) rather than
 * being reimplemented in shell. Named to sort first in `prebuild.d/`, ahead of gateway steps.
 *
 * Running Gradle from inside an Xcode build is the same shape as the KMP-standard
 * `embedAndSignAppleFrameworkForXcode` build phase, and it is what makes pressing Run pick up
 * Kotlin changes — the manual "re-run Gradle after editing Kotlin" step, and the silently-stale
 * binary it invited, both disappear. `LOKAL_SKIP_KOTLIN_ASSEMBLE` opts out for CI that already
 * ran Gradle itself.
 */
internal fun kotlinXCFrameworkPrebuildStep(project: Project, xcFrameworkName: String): PrebuildStep =
    PrebuildStep(
        name = "00-kotlin-xcframework",
        script = """
            set -eu
            # Nothing to stage for a clean; Xcode runs pre-actions for that action too.
            if [ "${'$'}{ACTION:-}" = "clean" ]; then exit 0; fi

            if [ -n "${'$'}{LOKAL_SKIP_KOTLIN_ASSEMBLE:-}" ]; then
              echo "Lokal Payment SDK: LOKAL_SKIP_KOTLIN_ASSEMBLE set — leaving the staged Kotlin binary as-is"
              exit 0
            fi

            # Anything that isn't recognisably a Debug configuration gets the optimized binary:
            # an unknown configuration is far more likely to be a release/archive variant, and
            # shipping a debug Kotlin binary is the worse way to be wrong.
            case "${'$'}{CONFIGURATION:-}" in
              Debug*|debug*) variant=Debug ;;
              "") echo "warning: Lokal Payment SDK: CONFIGURATION is not set (is 'Provide build settings from' set to your app target?); staging Release." >&2
                  variant=Release ;;
              *) variant=Release ;;
            esac

            echo "Lokal Payment SDK: staging ${'$'}variant Kotlin XCFramework for configuration ${'$'}{CONFIGURATION:-<unset>}"
            cd "${project.rootDir.absolutePath}"
            ./gradlew "${project.path}:$STAGE_TASK_BASE${'$'}variant"
        """.trimIndent(),
    )
