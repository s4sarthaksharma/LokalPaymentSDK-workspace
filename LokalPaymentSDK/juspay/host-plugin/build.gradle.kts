plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

gradlePlugin {
    plugins {
        create("juspayHost") {
            id = "com.getlokalapp.paymentsdk.juspay-host"
            implementationClass = "com.getlokalapp.paymentsdk.juspay.host.JuspayHostPlugin"
        }
    }
}

dependencies {
    implementation("in.juspay:hypersdk.plugin:2.0.6")
}
