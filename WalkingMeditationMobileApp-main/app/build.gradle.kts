plugins {
    alias(libs.plugins.android.application)
}

val serverBaseUrl = providers.gradleProperty("SERVER_BASE_URL")
    .orElse("http://10.0.2.2:8000")
val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse("")
val serverAccessToken = providers.gradleProperty("SERVER_ACCESS_TOKEN")
    .orElse("")

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.walking_meditation"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.walking_meditation"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SERVER_BASE_URL", buildConfigString(serverBaseUrl.get()))
        buildConfigField("String", "SERVER_ACCESS_TOKEN", buildConfigString(serverAccessToken.get()))
        resValue("string", "google_maps_key", mapsApiKey.get())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.places)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.activity)
    implementation(libs.recyclerview)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
