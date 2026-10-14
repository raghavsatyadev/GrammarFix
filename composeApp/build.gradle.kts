import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.multiplatform.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.androidx.room3)
}

// The exported schema is committed: it is what makes a later migration reviewable.
room3 { schemaDirectory("$projectDir/schemas") }

kotlin {
  android {
    namespace = libs.versions.sharedAndroidId.get()
    compileSdk {
      version =
        release(libs.versions.compileSdk.get().toInt()) {
          minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    androidResources.enable = true
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
  }

  jvm()

  iosArm64 {
    binaries.framework {
      baseName = "SCUSLib"
      isStatic = true
    }
  }

  iosSimulatorArm64 {
    binaries.framework {
      baseName = "SCUSLib"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.bundles.compose.mp)
      implementation(libs.bundles.koin.mp)
      implementation(libs.bundles.lifecycle.mp)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.logging)
      implementation(libs.kotlinx.serialization.json)
      // Room 3 is KMP-native: one driver, every target.
      api(libs.androidx.room3.runtime)
      api(libs.androidx.sqlite.bundled)
    }

    androidMain.dependencies {
      implementation(projects.llamaAndroid)
      implementation(libs.compose.ui.preview)
      implementation(libs.compose.activity)
      implementation(libs.ktor.client.okhttp)
    }

    val jvmMain by getting {
      dependencies {
        implementation(libs.ktor.client.okhttp)
      }
    }

    val iosMain by creating {
      dependencies {
        implementation(libs.ktor.client.darwin)
      }
    }
  }

  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
}

dependencies {
  androidRuntimeClasspath(libs.compose.ui.tooling)

  // Room's compiler runs per target, not once for common code.
  listOf("kspAndroid", "kspJvm", "kspIosArm64", "kspIosSimulatorArm64").forEach {
    add(it, libs.androidx.room3.compiler)
  }
}
