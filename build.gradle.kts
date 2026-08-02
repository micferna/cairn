plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

// Analyse statique : code mort, doublons, complexité, constructions à risque.
// Configurée à la racine pour couvrir tous les modules présents et à venir.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt.yml"))
    source.setFrom(files("app/src/main/kotlin"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
}
