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
        versionCode = 1
        versionName = "0.1.0"
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
    testImplementation(libs.junit)
}
