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

    // Doit précéder buildTypes : le DSL Kotlin s'exécute dans l'ordre, et
    // buildTypes référence cette configuration.
    //
    // La signature n'est activée que si le keystore ET ses mots de passe sont
    // présents — c'est le cas en CI, via les secrets du dépôt. En local on
    // produit un APK non signé plutôt que d'échouer : personne n'a besoin de la
    // clé de publication pour compiler et auditer le projet.
    val keystoreFile = rootProject.file(System.getenv("CAIRN_KEYSTORE") ?: "keystore.jks")
    val keystorePassword: String? = System.getenv("CAIRN_KEYSTORE_PASSWORD")
    val canSign = keystoreFile.exists() && !keystorePassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (canSign) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = System.getenv("CAIRN_KEY_ALIAS") ?: "cairn"
                keyPassword = System.getenv("CAIRN_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("release")
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

    sourceSets["main"].java.directories.add("src/main/kotlin")

    // Android Lint : couvre les API dépréciées, les fuites, les ressources
    // inutilisées et les défauts de sécurité. Il tourne en CI sur chaque push.
    // Les rapports SARIF et HTML sont générés systématiquement depuis AGP 9,
    // il n'y a plus à les demander.
    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        warningsAsErrors = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * La garantie centrale du projet, vérifiée sur l'artefact final.
 *
 * Une dépendance peut ajouter une permission par fusion de manifeste sans que
 * personne ne l'ait écrite — c'est arrivé avec Glance, qui dépend de
 * WorkManager et apportait ACCESS_NETWORK_STATE. Relire le manifeste source ne
 * suffit donc pas : il faut interroger l'APK compilé.
 *
 * Cette tâche est branchée sur chaque assemblage. Une build qui produit un APK
 * capable d'ouvrir une socket échoue, en local comme en intégration continue.
 */
val verifyNoNetworkPermission = tasks.register("verifyNoNetworkPermission") {
    group = "verification"
    description = "Échoue si l'APK déclare une permission réseau."

    val apkDirs = listOf(
        layout.buildDirectory.dir("outputs/apk/debug"),
        layout.buildDirectory.dir("outputs/apk/release"),
    )
    // Résolu à la configuration : la tâche ne capture qu'une chaîne, ce qui la
    // laisse compatible avec le cache de configuration de Gradle.
    val sdkPath: String = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.removePrefix("sdk.dir=")
            .orEmpty()

    doLast {
        val aapt2 = File(sdkPath).resolve("build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { dir -> dir.resolve("aapt2").takeIf { it.exists() } }
            ?: error("aapt2 introuvable dans le SDK Android.")

        val apks = apkDirs.flatMap { d -> d.get().asFile.listFiles()?.toList().orEmpty() }
            .filter { it.name.endsWith(".apk") }
        if (apks.isEmpty()) return@doLast

        val forbidden = Regex("android\\.permission\\.(INTERNET|ACCESS_NETWORK_STATE)")
        apks.forEach { apk ->
            val out = providers.exec {
                commandLine(aapt2.absolutePath, "dump", "permissions", apk.absolutePath)
            }.standardOutput.asText.get()
            val hit = forbidden.find(out)
            if (hit != null) {
                error(
                    "${apk.name} déclare ${hit.value}. C'est la garantie centrale de Cairn : " +
                        "aucune build ne doit pouvoir ouvrir une socket. " +
                        "Cherchez la dépendance fautive dans le rapport de fusion de manifeste " +
                        "(build/outputs/logs/) et retirez la permission avec tools:node=\"remove\"."
                )
            }
        }
        logger.lifecycle("Aucune permission réseau dans ${apks.size} APK vérifié(s).")
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy(verifyNoNetworkPermission)
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
