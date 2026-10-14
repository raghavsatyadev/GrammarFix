plugins { alias(libs.plugins.android.library) }

kotlin { jvmToolchain(21) }

// On-device llama.cpp inference. llama.cpp is a git submodule at src/main/cpp/llama.cpp,
// pinned to the release that kolama vendors; run `git submodule update --init` after cloning.
android {
  namespace = "com.raghavsatyadev.grammarfix.llama"
  compileSdk {
    version =
      release(libs.versions.compileSdk.get().toInt()) {
        minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
      }
  }
  ndkVersion = libs.versions.ndk.get()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    // Real devices only: x86_64 would double native build time for emulators we don't use.
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake {
        // A debug-built llama.cpp is unusably slow, so native code is always optimised.
        arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = libs.versions.cmake.get()
    }
  }
}
