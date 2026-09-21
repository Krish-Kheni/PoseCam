import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing. keystore.properties and the keystore itself are outside version control;
// without them `assembleRelease` still builds, but unsigned and uninstallable.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.posecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.posecam"
        // ARCore's own minimum; any phone that can run ARCore can run PoseCam.
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true    // Android 7-8 sideloads verify the v1 signature
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off deliberately: ARCore and the Camera2 metadata paths use
            // reflection, and a field tool is not worth a silent stripping bug.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
