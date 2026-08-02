plugins {
    // Depuis AGP 9, le support Kotlin est intégré : le plugin
    // org.jetbrains.kotlin.android ne doit plus être appliqué.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.cairn"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.cairn"
        minSdk = 26
        targetSdk = 37
        // Pilotés par le tag Git lors d'une release (voir .github/workflows/release.yml).
        versionCode = (findProperty("cairnVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("cairnVersionName") as String?) ?: "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file(System.getenv("CAIRN_KEYSTORE") ?: "keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // Alimenté par le workflow GitHub Actions à partir des secrets du dépôt.
            // En local, si le keystore est absent, on retombe sur une build non signée.
            val storePath = System.getenv("CAIRN_KEYSTORE") ?: "keystore.jks"
            val store = rootProject.file(storePath)
            if (store.exists()) {
                storeFile = store
                storePassword = System.getenv("CAIRN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CAIRN_KEY_ALIAS") ?: "cairn"
                keyPassword = System.getenv("CAIRN_KEY_PASSWORD")
            }
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    // Android Lint : couvre les API dépréciées, les fuites, les ressources
    // inutilisées et les défauts de sécurité. Il tourne en CI sur chaque push.
    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        sarifReport = true
        htmlReport = true
        // Les chaînes de l'interface sont en français et l'application n'est pas
        // encore traduite : la localisation viendra, ce n'est pas un défaut.
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
