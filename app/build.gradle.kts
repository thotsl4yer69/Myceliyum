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
    // Google Cloud API key — used only for the place-search bar (Geocoding API);
    // the app falls back to the device geocoder if blank. Precedence:
    // local.properties > GOOGLE_API_KEY env var (CI) > the hardcoded default
    // below. RESTRICT this key in Cloud Console to the Geocoding API + this
    // app's package/SHA-1, since it ships inside the (decompilable) APK.
    buildConfigField(
      "String",
      "GOOGLE_API_KEY",
      "\"${localProps.getProperty("GOOGLE_API_KEY", System.getenv("GOOGLE_API_KEY") ?: "AIzaSyCHMNlANTRjpPCEJxqZ-W-LIRATIc6fVMY")}\""
    )
    // Earth Engine layers backend (Cloud Run). Blank = use free OSM canopy.
    // Injected at build time from the BACKEND_BASE_URL / BACKEND_TOKEN GitHub
    // Actions secrets (see android-ci.yml / android-release.yml) or
    // local.properties for local builds — NEVER hardcoded, since this is a
    // public repo and the token guards a billable endpoint.
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

    // Stable debug signing. The rolling debug APKs published to the "latest"
    // GitHub release (and offered by the in-app update check) MUST all be signed
    // with the same key — otherwise Android refuses to install a new build over
    // an existing one with a signature conflict
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE, surfaced to users as "couldn't be
    // installed due to a conflict"). The auto-generated ~/.android/debug.keystore
    // is unique per machine, so each CI runner would otherwise sign every build
    // with a different key and break updates. The committed debug.keystore.base64
    // is decoded to ${rootDir}/debug.keystore (CI does this; see android-ci.yml /
    // android-release.yml, and build.ps1 for local builds). When that file is
    // present we sign with it so every build shares one identity; otherwise we
    // fall back to the auto-generated key (fine for purely local installs that
    // never join the rolling-update channel).
    getByName("debug") {
      val stableDebugKeystore = file("${rootDir}/debug.keystore")
      if (stableDebugKeystore.exists()) {
        storeFile = stableDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
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
      // Signs with the stable committed debug keystore when it has been restored
      // to ${rootDir}/debug.keystore (see the debug signingConfig above) so that
      // rolling APK updates install cleanly over one another; falls back to
      // Android's auto-generated ~/.android/debug.keystore for local-only builds.
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
