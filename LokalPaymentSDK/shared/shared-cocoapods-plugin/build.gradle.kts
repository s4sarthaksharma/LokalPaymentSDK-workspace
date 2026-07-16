plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// The generic, gateway-agnostic host plugin. Unlike the per-gateway
// `*-cocoapods-host` plugins (each of which bakes in one vendor pod name +
// version and edits the host podspec), this one names nothing: it discovers
// every `com.getlokalapp.paymentsdk:*` dependency the host declares and pulls
// any that ship an `iossrc` classifier. One plugin covers all present and
// future iOS modules, so it lives under :shared rather than any one gateway.
// It has no dependency on the :shared library — the nesting is organisational.
gradlePlugin {
    plugins {
        create("sharedCocoapods") {
            id = "com.getlokalapp.paymentsdk.shared-cocoapods"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.SharedCocoapodsPlugin"
        }
    }
}
