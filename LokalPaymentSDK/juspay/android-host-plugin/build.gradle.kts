plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// A host applies this on its `com.android.application` module only. It's the Android
// half of Juspay's host wiring (applies hypersdk.plugin + forwards the client id); the
// iOS half is the JuspayHostContributor dispatched by the `lokal-payment` umbrella.
gradlePlugin {
    plugins {
        create("juspayAndroidHost") {
            id = "com.getlokalapp.paymentsdk.juspay-android-host"
            implementationClass = "com.getlokalapp.paymentsdk.juspay.host.JuspayAndroidHostPlugin"
        }
    }
}

dependencies {
    implementation("in.juspay:hypersdk.plugin:2.0.6")
}
