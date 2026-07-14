plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
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
