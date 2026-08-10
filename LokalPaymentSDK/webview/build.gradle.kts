import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk.webview"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    // Plain klib targets — no framework/pod output; folds into the consumer umbrella
    // XCFramework under SPM (see docs/cocoapods-to-spm-migration-plan.md).
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: this module hands host/consumer code JSON via :shared's
            // lenientJson and presents onto :shared's hostcontext utilities
            // (ActivityTracker / topmostViewController) — same as every gateway.
            api(project(":shared"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            // Window-insets / edge-to-edge handling for the proxy Activity's
            // WebView (ViewCompat/WindowCompat/WindowInsetsCompat). Views, no Compose.
            implementation(libs.androidx.core)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.webkit)
        }
    }
}
