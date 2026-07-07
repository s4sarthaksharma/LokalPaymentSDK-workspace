rootProject.name = "LokalPaymentSDKDemo"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // LokalPaymentSDK's `shared` and `razorpay-checkout` modules are
        // consumed from here — publish them with
        // `./gradlew :shared:publishToMavenLocal :razorpay-checkout:publishToMavenLocal`
        // in the LokalPaymentSDK project after any change to the library.
        mavenLocal()
    }
}

include(":composeApp")
include(":androidApp")
