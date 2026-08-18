package com.getlokalapp.paymentsdk.buildsrc

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** The KMP plugin id every runtime module applies; identifies a "runtime module". */
internal const val KOTLIN_MULTIPLATFORM_ID = "org.jetbrains.kotlin.multiplatform"

/**
 * Configuration the Kotlin Multiplatform plugin creates for the `commonTest`
 * source set. Used instead of the typed `KotlinMultiplatformExtension` so this
 * file — and `buildSrc` as a whole — stays on the plain Gradle API and never
 * needs the Kotlin Gradle Plugin on its own compile classpath.
 */
private const val COMMON_TEST_IMPLEMENTATION = "commonTestImplementation"

/** The shared test-fixture module, which every runtime module's `commonTest` gets. */
private const val TEST_KIT_PATH = ":test-kit"

/**
 * Kotlin/Native test tasks disabled by [skipIosTestBinaries]. Both the link step
 * and the run step are listed: leaving the link enabled would still fail the build.
 * `iosArm64` has no run task (device tests need a device), only a link task.
 */
private val IOS_TEST_TASKS = listOf(
    "linkDebugTestIosX64",
    "linkDebugTestIosArm64",
    "linkDebugTestIosSimulatorArm64",
    "iosX64Test",
    "iosSimulatorArm64Test",
)

/**
 * Opts this module out of building and running Kotlin/Native iOS **test binaries**,
 * leaving its `commonTest` suite to run on `androidHostTest` only. [reason] is
 * required so every call site documents itself.
 *
 * Generating cinterop bindings needs only the vendor's *interface*
 * (headers/modulemap) — but **linking a test executable** pulls in the whole
 * module, including `iosMain`, and so needs the vendor's real symbols on the
 * linker path. This was invisible while the modules had no test sources, because
 * the link task reported `NO-SOURCE`.
 *
 * Applies to exactly two modules, both verified empirically — **do not assume any
 * module with a vendor SDK needs it**:
 *
 * - `:gateways:razorpay-checkout` — auto-linking cannot find `Razorpay.xcframework`
 *   at link time (its search path is passed to cinterop only).
 * - `:gateways:native-iap` — no Swift binary is built at all by design.
 *
 * `:gateways:juspay` binds to vendored xcframeworks too (HyperSDK/HyperCore/
 * Airborne) and links its test binary **fine**, so it deliberately does not call
 * this. Why Razorpay auto-links and HyperSDK does not is unresolved; see
 * `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 *
 * Everything else — `:shared`, `:webview`, `:upi-intent`, `:web-checkout`,
 * `:razorpay-customui`, `:juspay` — runs the full matrix including
 * `iosSimulatorArm64Test`, so cross-platform parity coverage (most importantly
 * `:webview`'s URL/origin parsing) is unaffected.
 *
 * Making the two excluded modules link is possible — framework search path plus
 * `-rpath` in the test binary's `linkerOpts` for Razorpay, or compiling
 * `:native-iap`'s Swift bridge into a static library — but is real work with a real
 * chance of runtime dynamic-loading problems, so it is tracked as an open decision
 * rather than done here.
 *
 * Uses `afterEvaluate` with an eager lookup deliberately: a lazy
 * `tasks.matching { }.configureEach { }` does not stick, because the Kotlin
 * Multiplatform plugin configures these link tasks afterwards and the `enabled`
 * flag is lost.
 */
fun Project.skipIosTestBinaries(reason: String) {
    require(reason.isNotBlank()) { "skipIosTestBinaries() requires a reason for ${project.path}." }
    afterEvaluate {
        IOS_TEST_TASKS.forEach { name -> tasks.findByName(name)?.enabled = false }
    }
}

/**
 * Everything a runtime module needs for its `commonTest` suite, in one call:
 *
 * 1. the `kotlin-test` and `kotlinx-coroutines-test` dependencies;
 * 2. the shared `:test-kit` fixtures (skipped for `:test-kit` itself);
 * 3. a `check` dependency on the root empty-test-suite guard;
 * 4. console logging of failed tests, with the assertion message.
 *
 * Exists so the plumbing is declared once rather than hand-copied per module. That
 * copying had already failed twice before this was introduced — `:gateways:juspay`
 * carried no `commonTest` block at all, so the module could not host a test, and
 * `:webview` was missing `kotlinx-coroutines-test`. A new gateway inherits all of it by
 * copying the reference module's build file, with nothing further to remember; see
 * `docs/adding-a-new-gateway.md` §4 Step 2 and `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 *
 * Call it once from a runtime module's `build.gradle.kts`. Ordering relative to the
 * `kotlin { }` block does not matter: the work is deferred until the multiplatform plugin
 * has been applied and has created the configuration.
 */
fun Project.paymentSdkTestConventions() {
    plugins.withId(KOTLIN_MULTIPLATFORM_ID) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf("kotlin-test", "kotlinx-coroutines-test").forEach { alias ->
            val dependency = libs.findLibrary(alias).orElseThrow {
                IllegalStateException(
                    "Version catalog 'libs' has no library '$alias'; " +
                        "paymentSdkTestConventions() cannot wire ${project.path}'s tests.",
                )
            }
            dependencies.addProvider(COMMON_TEST_IMPLEMENTATION, dependency)
        }
        // Shared fixtures (RecordingLogger, runGatewayTest, JSON/wire-key helpers, and the
        // gateway contract suite). Skipped for :test-kit itself, which cannot depend on
        // itself — that is why the module calls this convention safely rather than needing
        // its own hand-written test dependencies.
        if (path != TEST_KIT_PATH) {
            dependencies.add(COMMON_TEST_IMPLEMENTATION, project(TEST_KIT_PATH))
        }
        // Make `check` in this module trigger the root empty-test-suite guard. The guard
        // itself enumerates *every* KMP subproject, so a module that forgets to call this
        // convention is still validated as soon as any other module's `check` runs — the
        // wiring only decides when the guard fires, never what it covers. Referenced by
        // task path so it does not matter whether the root project has been evaluated yet.
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(":$TEST_SUITE_GUARD_TASK")
        }

        // Print the assertion message and stack trace for failing tests on the console.
        // Gradle otherwise reports only "AssertionError at Foo.kt:8" and hides the message
        // in the HTML report, which wastes the effort of writing a precise one (the wire-key
        // and non-fatal-code assertions in :test-kit exist to say exactly what diverged).
        // AbstractTestTask covers both the JVM `Test` task and Kotlin/Native's test tasks.
        tasks.withType<AbstractTestTask>().configureEach {
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
                showStackTraces = true
            }
        }
    }
}
