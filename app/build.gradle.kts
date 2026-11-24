import java.util.Properties

plugins {
    id("com.android.application")
}

// Optional: data of the fixed signing key (not included in the package).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasFixedKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.pvm.zombiemusiclibrary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pvm.zombiemusiclibrary"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "2.2"
    }

    // One fixed key for every build, so a new version installs over the old one (same signature).
    // The key and its passwords are NOT part of this package: they are read from keystore.properties
    // (see keystore.properties.example). Without that file the build is signed with the normal debug key.
    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = if (hasFixedKey) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasFixedKey) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// No external libraries: the app only uses what Android already includes.
