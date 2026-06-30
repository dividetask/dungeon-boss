plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dungeonboss"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dungeonboss"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A committed, stable signing key so every build (on any machine) is signed
    // with the same certificate. Without this, each build environment generates
    // a fresh debug keystore, changing the app's signature — which makes Google
    // Play Protect treat each install as an unknown new developer and block it.
    // This is a low-sensitivity key for a prototype, not a production secret.
    signingConfigs {
        create("shared") {
            storeFile = file("dungeonboss.keystore")
            storePassword = "dungeonboss"
            keyAlias = "dungeonboss"
            keyPassword = "dungeonboss"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // The canonical card data is YAML; both clients parse the same file.
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// The canonical card data now lives as a split set of files under data/cards/
// (bosses.yaml, rooms.yaml, heroes.yaml, abilities.yaml). The Android client
// bundles its own merged copy at src/main/assets/cards.yaml (a single document
// with the same top-level keys), regenerated from data/cards/ and committed —
// so there is no longer a build-time copy step (the old one pointed at the
// removed data/cards.yaml and silently did nothing).
