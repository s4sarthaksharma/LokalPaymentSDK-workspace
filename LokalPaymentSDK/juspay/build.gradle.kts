import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.native.cocoapods")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"
version = "0.0.1"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.juspay"
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

    cocoapods {
        version = "0.0.1"
        summary = "Lokal Payment SDK - Juspay HyperCheckout"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "Juspay"

        framework {
            baseName = "Juspay"
            isStatic = true
        }

        // D9: compiling this module's iOS targets requires
        // SKIP_HYPERSDK_VALIDATION=true set in the environment — the pod's own
        // "Validate Mandatory Files" script phase expects merchant assets from a
        // client-specific MerchantConfig.txt + Fuse.rb post_install step that
        // only the host's real Podfile runs. See docs/juspay-integration-plan.md.
        pod("HyperSDK") {
            version = "2.2.8.1"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // compileOnly (D4): host applies the hypersdk plugin which supplies
            // these at runtime; consumers who don't use Juspay must not get them
            // on their classpath. Confirmed real, public artifacts (R2).
            compileOnly(libs.juspay.hypersdk)
            compileOnly(libs.juspay.hyperinteg)
            implementation(libs.androidx.fragment)
        }
    }
}
