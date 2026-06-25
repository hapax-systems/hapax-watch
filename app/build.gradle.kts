plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.hapax.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hapax.watch"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        disable += setOf(
            "AutoboxingStateCreation",
            "FrequentlyChangingValue",
            "NullSafeMutableLiveData",
            "RememberInComposition",
        )
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.compose)

    // Wear Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    // Health Services (for Sprint 2)
    implementation(libs.health.services)

    // Network
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.datastore.prefs)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)

    // Tiles (for Sprint 4)
    implementation(libs.tiles)
    implementation(libs.tiles.material)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material)
    implementation(libs.protolayout.expression)

    // Test
    testImplementation(libs.junit.jupiter)
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core-ktx:1.15.0")
        force("androidx.core:core:1.15.0")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
