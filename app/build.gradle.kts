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

    // Optional build-time values are read from local.properties / environment
    // only. Never place a real credential in source code. Also remember that
    // BuildConfig values are recoverable from a distributed APK: they are
    // configuration, not a secure secret store.
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
      localPropsFile.inputStream().use { localProps.load(it) }
    }

    // Optional Google API key. Any key distributed in an Android APK is
    // extractable and must be protected with provider-supported restrictions,
    // quotas and API scoping. Blank keeps the Google geocoding path disabled.
    val googleApiKey =
      localProps.getProperty("GOOGLE_API_KEY")
        ?: System.getenv("GOOGLE_API_KEY")
        ?: ""
    buildConfigField(
      "String",
      "GOOGLE_API_KEY",
      "\"${googleApiKey}\""
    )

    // Optional private-development environmental backend. BACKEND_TOKEN is a
    // lightweight abuse-deterrence value only: if compiled into an APK it is
    // extractable and MUST NOT be treated as production authentication. Public
    // CI/release workflows intentionally leave these blank and use the app's
    // free/keyless fallback until a client-safe auth design exists.
    val backendBaseUrl =
      localProps.getProperty("BACKEND_BASE_URL")
        ?: System.getenv("BACKEND_BASE_URL")
        ?: ""
    val backendToken =
      localProps.getProperty("BACKEND_TOKEN")
        ?: System.getenv("BACKEND_TOKEN")
        ?: ""

    buildConfigField(
      "String",
      "BACKEND_BASE_URL",
      "\"${backendBaseUrl}\""
    )
    buildConfigField(
      "String",
      "BACKEND_TOKEN",
      "\"${backendToken}\""
    )
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }

    // A stable debug signing key may be restored locally/within CI when needed.
    // The key itself must never be committed to this public repository.
    getByName("debug") {
      val stableDebugKeystore = file("${rootDir}/debug.keystore")
      if (stableDebugKeystore.exists()) {
        storeFile = stableDebugKeystore
        storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Release signing is configured only when CI/local environment supplies the
      // required private credentials. The release workflow validates them first.
      if (System.getenv("STORE_PASSWORD") != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debug")
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
