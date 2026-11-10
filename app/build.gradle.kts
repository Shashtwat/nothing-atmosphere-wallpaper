import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.shashtwat.atmosphere"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shashtwat.atmosphere"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        setProperty("archivesBaseName", "NothingAtmosphere-$versionName")
    }

    // Release key lives outside the repo. Point ATMOSPHERE_SIGNING at a properties file with
    // storeFile / storePassword / keyAlias / keyPassword; without it, release builds use the debug key.
    val signingFile = (System.getenv("ATMOSPHERE_SIGNING")
        ?: "${System.getProperty("user.home")}/.android-keystores/nothing-atmosphere.properties").let(::file)
    val releaseSigning = if (signingFile.exists()) {
        val p = Properties().apply { signingFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
}
