package com.getlokalapp.paymentsdk.buildsrc

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Name of the guard task. Registered once on the root project by
 * [registerTestSuiteGuard] and wired into every runtime module's `check` by
 * [paymentSdkTestConventions].
 */
internal const val TEST_SUITE_GUARD_TASK = "verifyTestSuitesPresent"

/**
 * Standard KMP common-test source directory. The guard is layout-based rather than
 * reading the Kotlin source sets, which keeps `buildSrc` on the plain Gradle API
 * (no Kotlin Gradle Plugin on its compile classpath). Every module in this repo uses
 * the standard layout; a module that ever moves its test sources must update this.
 */
private const val COMMON_TEST_SRC = "src/commonTest/kotlin"

/**
 * Matches a `@Test` annotation use. Deliberately a text heuristic rather than
 * compiling anything: the guard only needs to distinguish "this module has at least
 * one executing test" from "this module has none".
 */
private val TEST_ANNOTATION = Regex("""@Test\b""")

/**
 * Modules exempt from carrying their own `commonTest` suite.
 *
 * **Currently empty, deliberately.** `:test-kit` was going to be exempt — it exists to
 * *host* fixtures rather than to be tested — but its fixtures are load-bearing for every
 * later phase (a bug in `RecordingLogger` or `runGatewayTest` silently weakens every test
 * that uses them), so it carries self-tests and is held to the same standard as everything
 * else.
 *
 * The mechanism stays so that granting an exemption is an explicit, reviewable edit rather
 * than a pattern that quietly matches future modules.
 */
private val EXEMPT_PATHS = emptySet<String>()

private class GuardedModule(val path: String, val commonTestDir: File)

/**
 * Registers the empty-test-suite guard on the root project.
 *
 * Implements the requirement recorded in `docs/SDK_REVIEW.md`:
 *
 * > CI must fail when expected test source sets are empty. A small verification task
 * > can assert a minimum test count per runtime module so `NO-SOURCE` cannot
 * > masquerade as success.
 *
 * That review found `./gradlew allTests` passing while `:shared`, `:webview` and four
 * gateway modules all reported `NO-SOURCE` — a green build proving nothing.
 *
 * **Enumeration is dynamic**, never a hardcoded module list: every subproject applying
 * the Kotlin Multiplatform plugin is a runtime module and is checked. Adding
 * `include(":gateways:foo")` to `settings.gradle.kts` is therefore the only act needed
 * to bring a new gateway under the guard — it cannot be escaped by forgetting to opt
 * in, which is exactly how a hardcoded list would leak. The JVM-only
 * `:gradle-plugins:*` modules are excluded automatically (they do not apply KMP); their
 * tests are covered by `docs/TESTING_05_BUILD_RELEASE_AND_CI.md`.
 *
 * **Counts test *sources*, never `build/test-results`.** Stale result XML from a
 * previous run — including, in this repo, a file for a `EagerRegistrationTest` class
 * that exists nowhere in the source tree — would otherwise certify a module whose
 * tests never ran. See finding F4 in `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
fun Project.registerTestSuiteGuard() {
    check(this == rootProject) {
        "registerTestSuiteGuard() must be called on the root project, not $path."
    }
    val guard = tasks.register(TEST_SUITE_GUARD_TASK) {
        group = "verification"
        description = "Fails if any runtime module has no executing tests in commonTest."
        // Never up to date: it is a sub-second filesystem scan, and a guard that can be
        // skipped as up-to-date reintroduces the "green build proving nothing" failure
        // it exists to prevent.
        outputs.upToDateWhen { false }
    }
    gradle.projectsEvaluated {
        val guarded = rootProject.subprojects
            .filter { it.plugins.hasPlugin(KOTLIN_MULTIPLATFORM_ID) }
            .filterNot { it.path in EXEMPT_PATHS }
            .map { GuardedModule(it.path, it.file(COMMON_TEST_SRC)) }
            .sortedBy { it.path }
        guard.configure {
            doLast { verifyTestSuites(guarded) }
        }
    }
}

private fun verifyTestSuites(guarded: List<GuardedModule>) {
    if (guarded.isEmpty()) {
        throw GradleException(
            "Empty-test-suite guard found no runtime modules to check. Either the " +
                "Kotlin Multiplatform plugin id changed or enumeration is broken — " +
                "either way the guard is no longer protecting anything.",
        )
    }
    val failures = guarded.mapNotNull { module ->
        val sources = module.commonTestDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        when {
            sources.isEmpty() -> "${module.path} — no .kt files under $COMMON_TEST_SRC"
            sources.none { TEST_ANNOTATION.containsMatchIn(it.readText()) } ->
                "${module.path} — ${sources.size} file(s) under $COMMON_TEST_SRC but no @Test function"
            else -> null
        }
    }
    if (failures.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("Empty test suite in ${failures.size} runtime module(s):")
                failures.forEach { appendLine("  $it") }
                appendLine()
                appendLine(
                    "Every runtime module must carry at least one executing test. A module " +
                        "with no test sources reports NO-SOURCE, so `allTests` passes while " +
                        "proving nothing — the exact gap docs/SDK_REVIEW.md raised.",
                )
                append("See docs/TESTING_01_FOUNDATION_AND_GUARD.md.")
            },
        )
    }
}
