plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))

// Read version from version.properties
val versionPropsFile = file("${rootDir}/version.properties")
val versionLines = if (versionPropsFile.exists()) versionPropsFile.readLines() else emptyList()
val versionMap = versionLines.filter { it.contains("=") && !it.startsWith("#") }.associate {
    val (k, v) = it.split("=", limit = 2)
    k.trim() to v.trim()
}
val appVersionCode = versionMap["versionCode"]?.toInt() ?: 1
val appVersionName = versionMap["versionName"] ?: "1.0.0"

android {
    namespace = "com.prado.treeviewer"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.prado.treeviewer"
        minSdk = 26
        targetSdk = 33
        versionCode = appVersionCode
        versionName = appVersionName

        // Make version available to code via BuildConfig
        buildConfigField("String", "VERSION_DISPLAY", "\"v${appVersionName} (${appVersionCode})\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseStoreFile.orNull
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
