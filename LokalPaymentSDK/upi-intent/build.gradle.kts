import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Generic UPI-intent gateway. Unlike the Razorpay/Juspay modules it wraps no
// vendor SDK: the backend hands back a ready-to-launch `upi://…` deep link in
// gateway_config (a one-time `upi://pay` or an AutoPay `upi://mandate` — this
// module never inspects which; the URL is opaque) and the module just launches
// it. Android launches it with startActivityForResult via an internal proxy
// Activity and reads the returned UPI response as an unverified hint; iOS has
// no intent-result callback, so it opens the URL and immediately reports
// Pending. Either way the authoritative outcome comes from the host's own
// backend status check — this module only ever emits PaymentResult.Pending
// (handed off to a UPI app) or Failure (couldn't hand off), never a trusted
// Success. This is the first gateway to use PaymentResult.Pending.
//
// Multiplatform (real Android + real iOS), so no cocoapods block and no vendor
// dependency on either side — the launch primitives are the platform's own
// (Android Intent / iOS UIApplication.openURL).

val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.upiintent",
)

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.upiintent"
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
                api(project(":shared"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // Draggable UPI-app-chooser BottomSheetDialog. Android-only; Views, not
            // Compose (which stays banned in the SDK). implementation, not api —
            // fully encapsulated behind the internal proxy Activity.
            implementation(libs.material)
        }
    }
}
