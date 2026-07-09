plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.juspay.hypersdk)
}

// D4/D9/R4: applied here, not :composeApp — hypersdk.plugin needs a
// conventional AGP application module's `implementation` configuration (see
// composeApp/build.gradle.kts's comment for why it fails there). Borrowing
// matrimony-kmp's real, already-registered clientId/sdkVersion so this demo
// actually builds and exercises the real Juspay flow — swap for this host's
// own clientId once one is issued.
hyperSdkPlugin {
    clientId = "lokalmatrimony"
    sdkVersion = "2.2.8-rc.01"
}

android {
    namespace = "com.getlokalapp.paymentsdk.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.getlokalapp.paymentsdk"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Application shell: hosts the shared UI from :composeApp via its own
    // launcher MainActivity. activity-compose provides setContent and pulls the
    // Compose runtime needed to call App().
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
