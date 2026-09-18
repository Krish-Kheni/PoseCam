plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.posecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.posecam"
        // ARCore's own minimum; any phone that can run ARCore can run PoseCam.
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.arcore)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
}
