import com.getlokalapp.paymentsdk.buildsrc.paymentSdkTestConventions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "com.getlokalapp.paymentsdk"

// Shared test fixtures for every module's `commonTest`: RecordingLogger (asserting the
// SDK's report-don't-throw diagnostics), runGatewayTest (Main dispatcher + logger
// lifecycle), and JSON/wire-key fixtures. The gateway contract suite lands here too —
// see docs/TESTING_03_CORE_RUNTIME_CONTRACT.md.
//
// Deliberately **no `maven-publish`**: this is build-time-only and must never reach a
// consumer's dependency graph. That is also why it carries `kotlin-test` as `api` in
// commonMain, which would be wrong for a shipped module.
//
// Must stay **cinterop-free**. Two gateway modules cannot link an iOS test binary
// because their bindings need vendor frameworks the SDK side never links
// (docs/TESTING_01_FOUNDATION_AND_GUARD.md, F1); if this module ever gained a vendor
// cinterop it would spread that problem to every module's tests at once.
//
// Fixtures live in **commonMain**, not commonTest: a dependency's test source set is not
// visible to consumers, so anything other modules must import has to be main code here.

paymentSdkTestConventions()

kotlin {
    android {
        namespace = "com.getlokalapp.paymentsdk.testkit"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    // Same targets as the gateway modules, so a consumer's commonTest resolves this on
    // every platform its own tests run on.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api throughout: every one of these appears in this module's own public
            // fixture signatures (LokalLogger, TestScope, JsonObject) or is needed by
            // consumers writing assertions against them.
            api(project(":shared"))
            api(libs.kotlin.test)
            api(libs.kotlinx.coroutines.test)
            api(libs.kotlinx.serialization.json)
        }
    }
}
