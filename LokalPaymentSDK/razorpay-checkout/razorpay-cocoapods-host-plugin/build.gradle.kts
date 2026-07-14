import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// Bakes gradle/libs.versions.toml's razorpay-pod-ios entry into a
// VENDOR_SDK_VERSION constant this plugin pins the host's podspec to — the same
// catalog entry :razorpay-checkout links its cinterop bindings against, so the
// linked pod can't drift from the bindings the host consumes.
val generatePodVersion = registerVendorVersionTask(
    taskName = "generatePodVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay.host",
    vendorSdkVersion = libs.versions.razorpay.pod.ios.get(),
    asActual = false,
)

// Shared podspec-editing helper + generated pod-version constant, both compiled
// into this plugin jar (kept out of a published artifact so it stays self-contained).
sourceSets.main {
    kotlin.srcDir(rootProject.file("cocoapods-host-plugin-common/src/main/kotlin"))
    kotlin.srcDir(generatePodVersion)
}

// A host applies this to its Compose/KMP module (the one that owns the iOS
// `cocoapods {}` block and produces the umbrella framework). It appends
// `spec.dependency 'razorpay-pod'` to that module's generated podspec so the
// vendor pod is pulled transitively from the CocoaPods trunk — the host never
// names razorpay-pod in its Podfile. Deliberately does NOT add a `pod(...)`
// cinterop to the host module: the Kotlin bindings already ride in via the
// published :razorpay-checkout klib (Maven), so all the host needs is the pod
// linked at the app target.
gradlePlugin {
    plugins {
        create("razorpayCocoapodsHost") {
            id = "com.getlokalapp.paymentsdk.razorpay-cocoapods-host"
            implementationClass =
                "com.getlokalapp.paymentsdk.razorpay.host.RazorpayCocoapodsHostPlugin"
        }
    }
}
