import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.screenshot)
}

kotlin { jvmToolchain(21) }

// Release signing, from the untracked secret.properties at the repo root. Without it the release
// build is left unsigned rather than failing.
val signingProps =
  Properties().apply {
    rootProject.file("secret.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
  }

android {
  experimentalProperties["android.experimental.enableScreenshotTest"] = true
  namespace = libs.versions.nameSpace.get()
  compileSdk {
    version =
      release(libs.versions.compileSdk.get().toInt()) {
        minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
      }
  }
  buildToolsVersion = libs.versions.buildTools.get()

  defaultConfig {
    applicationId = libs.versions.appIdProd.get()
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = libs.versions.versionCode.get().toInt()
    versionName = libs.versions.versionName.get()

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    resValue("string", "app_name", libs.versions.releaseAppName.get())
  }
  buildFeatures {
    resValues = true
    // MainActivity reads BuildConfig.DEBUG to gate developer-only UI (the Playground tab).
    buildConfig = true
  }
  signingConfigs {
    create("release") {
      signingProps.getProperty("storeFile")?.let { storeFile = file(it) }
      storePassword = signingProps.getProperty("storePassword")
      keyAlias = signingProps.getProperty("keyAlias")
      keyPassword = signingProps.getProperty("keyPassword")
    }
  }
  buildTypes {
    getByName("release") {
      if (signingProps.getProperty("storeFile") != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // llama.cpp dlopen()s its CPU backend variants by path, so they must exist on disk.
    jniLibs { useLegacyPackaging = true }
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
}

dependencies {
  implementation(projects.composeApp)
  implementation(libs.bundles.android)
  implementation(libs.bundles.compose.mp)
  implementation(libs.bundles.koin.android)
  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.leakcanary.android)

  // Testing
  testImplementation(libs.junit)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.sqlite.bundled.jvm)
  testImplementation(libs.compose.ui.test.junit4)

  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)

  screenshotTestImplementation(libs.screenshot.validation.api)
  screenshotTestImplementation(libs.compose.ui.tooling)
}
