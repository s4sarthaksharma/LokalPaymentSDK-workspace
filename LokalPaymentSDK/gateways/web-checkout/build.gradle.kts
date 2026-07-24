import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Hosted web-checkout gateway. Wraps no vendor SDK and needs no platform code of
// its own — it runs the backend-built hosted gateway page inside the :webview
// module and maps the page's reported events to PaymentResult. All logic lives
// in commonMain; the only per-platform files are the zero-host-code registration
// hooks (App Startup on Android, @EagerInitialization on iOS). No cocoapods
// block: its Kotlin/Native code links into the host's umbrella framework, same
// as :upi-intent.

val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.webcheckout",
)

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk.webcheckout"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateModuleVersion)
            dependencies {
                // api: this module's public surface is a PaymentGatewayHandler
                // (a :shared type). :webview is used only internally to run the
                // page, so it's an implementation detail.
                api(project(":shared"))
                implementation(project(":webview"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
