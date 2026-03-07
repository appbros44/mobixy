plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mobixy.proxy"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.mobixy.proxy"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_BACKEND_HOST", "\"api.apkroute.com\"")
            buildConfigField("String", "DEFAULT_ENROLL_TOKEN", "\"dev-enroll-token\"")
            buildConfigField("Boolean", "BACKEND_USE_TLS", "false")
            buildConfigField("int", "BACKEND_PORT", "8787")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_BACKEND_HOST", "\"api.apkroute.com\"")
            buildConfigField("String", "DEFAULT_ENROLL_TOKEN", "\"dev-enroll-token\"")
            buildConfigField("Boolean", "BACKEND_USE_TLS", "true")
            buildConfigField("int", "BACKEND_PORT", "443")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-messaging")
}