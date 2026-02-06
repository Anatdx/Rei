@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val androidCompileSdkVersion: Int = rootProject.extra["androidCompileSdkVersion"] as Int
val androidMinSdkVersion: Int = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion: Int = rootProject.extra["androidTargetSdkVersion"] as Int

android {
    namespace = "com.anatdx.rei"
    compileSdk = androidCompileSdkVersion

    defaultConfig {
        applicationId = "com.anatdx.rei"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        val code = (rootProject.findProperty("reiVersionCode") as String?)?.toIntOrNull() ?: 10000
        versionCode = code
        versionName = "1.0.${(code - 10000).coerceAtLeast(0)}"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val reiKeystore = System.getenv("REI_KEYSTORE") ?: System.getenv("RELEASE_STORE_FILE")
    val reiAlias = System.getenv("REI_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
    val reiStorePass = System.getenv("REI_KEYSTORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
    val reiKeyPass = System.getenv("REI_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
    val hasSigning = reiKeystore != null && reiAlias != null && reiStorePass != null && reiKeyPass != null

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(reiKeystore!!)
                storePassword = reiStorePass
                keyAlias = reiAlias
                keyPassword = reiKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures { buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.libsu)
    implementation(project(":murasaki-api:api"))
    implementation(project(":murasaki-api:provider"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------- 唯一 native 后端：reid（apd+ksud 合一，安装时硬链接为 reid/apd/ksud）----------
val outJniDir = layout.buildDirectory.dir("generated/jniLibs/arm64-v8a").get().asFile
val buildDir = layout.buildDirectory.get().asFile

// reid 单二进制构建（daemon/reid → libreid.so），内含 apd 与 ksud 逻辑，按 argv[0] 分发
val reidSrcDir = rootProject.file("daemon/reid")
val reidBuildDir = File(buildDir, "reid-build")
val reidOutSo = File(outJniDir, "libreid.so")

tasks.register("buildReidArm64") {
    outputs.file(reidOutSo)
    doLast {
        val ndkDir = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
            ?: throw GradleException("ANDROID_NDK_HOME required to build reid")
        outJniDir.mkdirs()
        reidBuildDir.mkdirs()
        val toolchain = "$ndkDir/build/cmake/android.toolchain.cmake"
        val configure = ProcessBuilder(
            "cmake", "-S", reidSrcDir.absolutePath, "-B", reidBuildDir.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-29",
            "-DCMAKE_BUILD_TYPE=Release"
        ).directory(rootProject.projectDir).redirectErrorStream(true).start()
        val out1 = configure.inputStream.bufferedReader().readText()
        if (configure.waitFor() != 0) throw GradleException("reid cmake configure failed: $out1")
        val build = ProcessBuilder("cmake", "--build", reidBuildDir.absolutePath, "--parallel")
            .directory(rootProject.projectDir).redirectErrorStream(true).start()
        val out2 = build.inputStream.bufferedReader().readText()
        if (build.waitFor() != 0) throw GradleException("reid build failed: $out2")
        val reidBin = File(reidBuildDir, "reid")
        if (!reidBin.exists()) throw GradleException("reid binary not found after build")
        reidBin.copyTo(reidOutSo, overwrite = true)
        println(" - Built reid -> ${reidOutSo.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(tasks.named("buildReidArm64"))
}

// 将 generated jniLibs（libreid.so）纳入 APK；merge 需要「根目录下为 ABI 子目录」的结构，故添加 arm64-v8a 的父目录
val outJniLibsRoot = outJniDir.parentFile
android.sourceSets.getByName("main").jniLibs.directories.add(outJniLibsRoot.absolutePath)
