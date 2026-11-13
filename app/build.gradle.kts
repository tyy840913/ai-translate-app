plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tyy.aitranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tyy.aitranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 44
        versionName = "1.3.40"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.p12")
            storeType = "PKCS12"
            storePassword = "aitranslate2026"
            keyAlias = "aitranslate"
            keyPassword = "aitranslate2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
