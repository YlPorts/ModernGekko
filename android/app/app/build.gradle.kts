plugins {
    id("com.android.application")
}

val repositoryRoot = rootProject.projectDir.parentFile.parentFile

android {
    namespace = "org.moderngekko.android"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.moderngekko.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMODERNGEKKO_ROOT=${repositoryRoot.absolutePath}",
                    "-DCMAKE_PROJECT_INCLUDE=${repositoryRoot.absolutePath}/android/native/dolphin_inject.cmake",
                    "-DUSE_SYSTEM_LIBS=OFF",
                    "-DENABLE_QT=OFF",
                    "-DENABLE_TESTS=OFF",
                    "-DENABLE_ANALYTICS=OFF",
                    "-DENABLE_AUTOUPDATE=OFF",
                    "-DENABLE_LLVM=OFF",
                    "-DENCODE_FRAMEDUMPS=OFF",
                    "-DUSE_RETRO_ACHIEVEMENTS=OFF",
                    "-DUSE_DISCORD_PRESENCE=OFF",
                    "-DUSE_MGBA=OFF",
                    "-DUSE_UPNP=OFF"
                )
                targets += listOf("moderngekko_android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../../vendor/dolphin/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(file("../../../vendor/dolphin/Data"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
}
