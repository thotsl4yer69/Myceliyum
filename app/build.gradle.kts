import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.myceliummapper.vcfqka"
    minSdk = 24
    targetSdk = 35
    // CI sets BUILD_NUMBER (= GitHub run number) so every published APK has a
    // unique, increasing versionCode; local builds fall back to 1.
    versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
    versionName = "7.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Optional build-time keys are read from local.properties / env (never
    // committed). The Anthropic key is deliberately NOT compiled in — it is
    // supplied by the user at runtime in Settings and stored only on-device,
    // so the published APK never ships an API key.
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
      localPropsFile.inputStream().use { localProps.load(it) }
    }
    // Google Cloud API key (Geocoding / Elevation / Vision). Read from
    // local.properties or the GOOGLE_API_KEY env var (CI) — never committed.
    buildConfigField(
      "String",
      "GOOGLE_API_KEY",
      "\"${localProps.getProperty("GOOGLE_API_KEY", System.getenv("GOOGLE_API_KEY") ?: "")}\""
    )
    // Earth Engine layers backend (Cloud Run). Blank = use free OSM canopy.
    buildConfigField(
      "String",
      "BACKEND_BASE_URL",
      "\"${localProps.getProperty("BACKEND_BASE_URL", System.getenv("BACKEND_BASE_URL") ?: "")}\""
    )
    buildConfigField(
      "String",
      "BACKEND_TOKEN",
      "\"${localProps.getProperty("BACKEND_TOKEN", System.getenv("BACKEND_TOKEN") ?: "")}\""
    )
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Release signing only kicks in when the keystore + env vars are provided.
      if (System.getenv("STORE_PASSWORD") != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      // Uses the standard auto-generated Android debug keystore
      // (~/.android/debug.keystore), so no checked-in keystore is required.
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.osmdroid.android)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
