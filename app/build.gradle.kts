plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    androidResources {
        generateLocaleConfig = true
    }
    namespace = "uz.tiyin"
    compileSdk = 36

    defaultConfig {
        applicationId = "uz.tiyin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Kept in ~/.gradle/gradle.properties so the key and its passwords never enter the repo or a worktree.
    val upload = listOf("tiyinUploadStoreFile", "tiyinUploadStorePassword", "tiyinUploadKeyAlias", "tiyinUploadKeyPassword")
        .map { providers.gradleProperty(it).orNull }
    if (upload.all { it != null }) {
        signingConfigs.create("upload") {
            storeFile = file(upload[0]!!)
            storePassword = upload[1]
            keyAlias = upload[2]
            keyPassword = upload[3]
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded personal app: the debug key lets a release build update the installed debug one in place.
            signingConfig = signingConfigs.getByName("debug")
        }
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime:2.9.1")
}

tasks.matching { it.name == "bundlePlay" || it.name == "assemblePlay" }.configureEach {
    doFirst {
        check(android.signingConfigs.findByName("upload") != null) {
            "Play builds need the upload key: set tiyinUploadStoreFile, tiyinUploadStorePassword, tiyinUploadKeyAlias and tiyinUploadKeyPassword in ~/.gradle/gradle.properties"
        }
    }
}
