plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
