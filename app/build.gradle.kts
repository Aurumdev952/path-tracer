plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "rw.aurum.pathtracer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "rw.aurum.pathtracer"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.arcore)
    implementation(libs.play.services.auth)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
