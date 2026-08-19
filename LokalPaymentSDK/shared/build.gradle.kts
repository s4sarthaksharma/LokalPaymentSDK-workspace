import com.getlokalapp.paymentsdk.buildsrc.paymentSdkTestConventions
import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// commonTest gets kotlin-test + kotlinx-coroutines-test from one shared place.
paymentSdkTestConventions()

// Bakes this build's version (root gradle.properties) into commonMain as the
// constant behind LokalPaymentSdk.VERSION, so the runtime-visible SDK version
// can never drift from the published artifact version.
val generatePaymentSdkVersion = registerModuleVersionTask(
    taskName = "generatePaymentSdkVersion",
    packageName = "com.getlokalapp.paymentsdk",
    constName = "PAYMENT_SDK_VERSION",
)

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    // Plain klib targets — no framework/pod output. Under SPM this module folds into the
    // consumer-assembled umbrella XCFramework (see docs/cocoapods-to-spm-migration-plan.md,
    // the umbrella-framework insight); it ships as a klib on Maven like every gateway.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generatePaymentSdkVersion)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        androidMain.dependencies {
            // api, not implementation: :shared's public GatewayInitializer
            // extends androidx.startup's Initializer, so the type is part of
            // :shared's API. Exposing it here means gateway modules inherit it
            // transitively — they subclass GatewayInitializer without declaring
            // androidx.startup themselves.
            api(libs.androidx.startup)
            // api for the same reason: OperationProxy takes the ComponentActivity it observes, so
            // androidx.activity is part of :shared's API and every proxy-Activity gateway inherits it
            // transitively rather than declaring it itself. ComponentActivity is what makes the
            // lifecycle observable per instance — see OperationProxy's kdoc for why that beats an
            // Application-level listener.
            api(libs.androidx.activity)
        }
    }
}
