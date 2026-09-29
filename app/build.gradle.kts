import java.util.Properties
import org.lineageos.generatebp.GenerateBpPlugin
import org.lineageos.generatebp.GenerateBpPluginExtension
import org.lineageos.generatebp.models.Module

plugins {
    id("com.android.application")
    id("kotlin-android")
}

apply {
    plugin<GenerateBpPlugin>()
}

buildscript {
    repositories {
        maven("https://raw.githubusercontent.com/lineage-next/gradle-generatebp/v1.2/.m2")
    }

    dependencies {
        classpath("org.lineageos:gradle-generatebp:+")
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "org.evolution.updater"
    // SDK 36 + android.jar da merge @hide (xem skill updater-gradle-debug)
    compileSdk = 36

    defaultConfig {
        applicationId = "org.evolution.updater"
        minSdk = 32
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"
    }

    signingConfigs {
        // Ky platform/test-keys — remount/push system_ext (giong GameSpace)
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
        create("release") {
            (keystoreProperties["keyAlias"] as String?)?.let { keyAlias = it }
            (keystoreProperties["keyPassword"] as String?)?.let { keyPassword = it }
            (keystoreProperties["storeFile"] as String?)?.let { storeFile = file(it) }
            (keystoreProperties["storePassword"] as String?)?.let { storePassword = it }
        }
    }

    buildTypes {
        getByName("release") {
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("platform")
            }
        }
        getByName("debug") {
            // Cung package system — KHONG them .dev (de push delen system_ext Updater)
            isDebuggable = true
            applicationIdSuffix = null
            versionNameSuffix = "-gradle"
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // symbols.xml nam o res-soong/ (chi Soong); Gradle chi dung src/main/res
}

dependencies {
    // Optional @hide jars; uu tien SDK da merge framework (local.properties -> .gradle-sdk)
    compileOnly(fileTree(mapOf("dir" to "../system_libs", "include" to listOf("*.jar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
}

configure<GenerateBpPluginExtension> {
    targetSdk.set(android.defaultConfig.targetSdk!!)
    availableInAOSP.set { module: Module ->
        when {
            module.group.startsWith("androidx") -> true
            module.group.startsWith("org.jetbrains") -> true
            module.group == "com.google.android.material" -> true
            module.group == "com.google.errorprone" -> true
            module.group == "com.google.guava" -> true
            module.group == "junit" -> true
            else -> false
        }
    }
}
