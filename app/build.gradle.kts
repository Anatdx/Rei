import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.anatdx.rei"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.anatdx.rei"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // We currently only ship arm64 native tools.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath =
                providers.gradleProperty("REI_KEYSTORE").orNull
                    ?: System.getenv("REI_KEYSTORE")
                    ?: "/Volumes/Workspace/keys/Rei.jks"
            val keyAliasValue =
                providers.gradleProperty("REI_KEY_ALIAS").orNull
                    ?: System.getenv("REI_KEY_ALIAS")
                    ?: "Rei"
            val storePasswordValue =
                providers.gradleProperty("REI_KEYSTORE_PASSWORD").orNull
                    ?: System.getenv("REI_KEYSTORE_PASSWORD")
            val keyPasswordValue =
                providers.gradleProperty("REI_KEY_PASSWORD").orNull
                    ?: System.getenv("REI_KEY_PASSWORD")

            storeFile = file(keystorePath)
            storePassword = storePasswordValue ?: ""
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // Include generated native outputs (built by Gradle tasks below).
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(File(buildDir, "generated/jniLibs"))
        }
    }
}

// Build `reid` / `ksuinit` from CMake and package as jniLibs.
// This avoids committing prebuilt binaries and prevents "empty lib" on clean environments / CI.
val androidComponents = extensions.getByType(AndroidComponentsExtension::class.java)
abstract class BuildReiNativeArm64Task : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val abi = "arm64-v8a"
        val minApi = 26

        val reidSrc = project.rootProject.layout.projectDirectory.dir("daemon/reid").asFile
        val ksuinitSrc = project.rootProject.layout.projectDirectory.dir("daemon/ksuinit").asFile
        val kernelsuJniSrc = project.rootProject.layout.projectDirectory.dir("daemon/kernelsu_jni").asFile

        val outDir = project.layout.buildDirectory.dir("generated/jniLibs/$abi").get().asFile
        val outReid = File(outDir, "libreid.so")
        val outReinit = File(outDir, "libreinit.so")
        val outKernelsu = File(outDir, "libkernelsu.so")

        val skip = System.getenv("REI_SKIP_NATIVE") == "1"
        if (skip) return

        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        fun resolveNdkDir(): File {
            // 1) Env vars from shell (fish/zsh). AGP itself doesn't reliably read these.
            listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK").forEach { key ->
                val v = System.getenv(key)?.takeIf { it.isNotBlank() } ?: return@forEach
                val f = File(v)
                if (f.exists()) return f
            }

            // 2) local.properties (Android Studio default): ndk.dir=/.../sdk/ndk/<ver>
            val lp = project.rootProject.file("local.properties")
            if (lp.exists()) {
                runCatching {
                    val props = Properties()
                    lp.inputStream().use { props.load(it) }
                    val ndkDir = props.getProperty("ndk.dir")?.takeIf { it.isNotBlank() }
                    if (ndkDir != null) {
                        val f = File(ndkDir)
                        if (f.exists()) return f
                    }
                }
            }

            // 3) AGP SDK components (requires SDK setup discoverable by Gradle)
            return runCatching { androidComponents.sdkComponents.ndkDirectory.get().asFile }.getOrElse {
                throw GradleException(
                    "NDK not found by Gradle. Please ensure NDK is installed AND discoverable.\n" +
                        "Tried env: ANDROID_NDK_HOME/ROOT/ANDROID_NDK, local.properties: ndk.dir, and AGP sdkComponents."
                )
            }
        }

        val ndkDir = resolveNdkDir()
        if (!ndkDir.exists()) {
            throw GradleException("Android NDK not found. Please install NDK via SDK Manager.")
        }
        val toolchain = File(ndkDir, "build/cmake/android.toolchain.cmake")
        if (!toolchain.exists()) {
            throw GradleException(
                "NDK toolchain not found: ${toolchain.absolutePath}\n" +
                    "Resolved NDK dir: ${ndkDir.absolutePath}"
            )
        }

        fun findCmake(): String {
            System.getenv("CMAKE")?.takeIf { it.isNotBlank() }?.let { return it }

            val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
            val cmakeRoot = File(sdkDir, "cmake")
            if (cmakeRoot.exists()) {
                val candidates = cmakeRoot
                    .listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    .orEmpty()
                for (c in candidates) {
                    val bin = File(c, "bin/cmake")
                    if (bin.exists()) return bin.absolutePath
                }
            }
            return "cmake"
        }

        val cmake = findCmake()
        val jobs = (Runtime.getRuntime().availableProcessors().coerceAtLeast(2)).toString()

        fun buildOne(srcDir: File, buildDir: File, target: String, output: File) {
            buildDir.mkdirs()
            outDir.mkdirs()

            execOps.exec {
                commandLine(
                    cmake,
                    "-S", srcDir.absolutePath,
                    "-B", buildDir.absolutePath,
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-$minApi",
                    "-DANDROID_NDK=${ndkDir.absolutePath}",
                    "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                )
            }
            execOps.exec {
                commandLine(
                    cmake,
                    "--build", buildDir.absolutePath,
                    "--target", target,
                    "--", "-j", jobs,
                )
            }

            val built = listOf(
                File(buildDir, target),
                File(buildDir, "bin/$target"),
                File(buildDir, "lib$target.so"),
                File(buildDir, "lib${target}.so"),
            ).firstOrNull { it.exists() }
                ?: throw GradleException("Built output not found for $target in ${buildDir.absolutePath}")

            built.copyTo(output, overwrite = true)
        }

        buildOne(
            srcDir = reidSrc,
            buildDir = project.layout.buildDirectory.dir("native/reid/$abi").get().asFile,
            target = "reid",
            output = outReid,
        )
        buildOne(
            srcDir = ksuinitSrc,
            buildDir = project.layout.buildDirectory.dir("native/ksuinit/$abi").get().asFile,
            target = "reinit",
            output = outReinit,
        )
        buildOne(
            srcDir = kernelsuJniSrc,
            buildDir = project.layout.buildDirectory.dir("native/kernelsu_jni/$abi").get().asFile,
            target = "kernelsu",
            output = outKernelsu,
        )

        if (outReid.length() <= 0L || outReinit.length() <= 0L || outKernelsu.length() <= 0L) {
            throw GradleException(
                "Native binaries are empty after build (libreid.so=${outReid.length()} bytes, libreinit.so=${outReinit.length()} bytes, libkernelsu.so=${outKernelsu.length()} bytes)"
            )
        }
    }
}

val buildNativeArm64 = tasks.register<BuildReiNativeArm64Task>("buildReiNativeArm64") {
    val abi = "arm64-v8a"
    val reidSrc = rootProject.layout.projectDirectory.dir("daemon/reid").asFile
    val ksuinitSrc = rootProject.layout.projectDirectory.dir("daemon/ksuinit").asFile
    val kernelsuJniSrc = rootProject.layout.projectDirectory.dir("daemon/kernelsu_jni").asFile
    val outDir = layout.buildDirectory.dir("generated/jniLibs/$abi").get().asFile
    outputs.file(File(outDir, "libreid.so"))
    outputs.file(File(outDir, "libreinit.so"))
    outputs.file(File(outDir, "libkernelsu.so"))
    inputs.dir(reidSrc)
    inputs.dir(ksuinitSrc)
    inputs.dir(kernelsuJniSrc)
}

tasks.named("preBuild").configure {
    dependsOn(buildNativeArm64)
}

fun verifyApkHasNative(apk: File, abi: String = "arm64-v8a") {
    if (!apk.exists()) throw GradleException("APK not found: ${apk.absolutePath}")
    ZipFile(apk).use { z ->
        val want = listOf(
            "lib/$abi/libreid.so",
            "lib/$abi/libreinit.so",
            "lib/$abi/libkernelsu.so",
        )
        val names = z.entries().asSequence().map { it.name }.toSet()
        val missing = want.filterNot { names.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native libs missing in APK: ${apk.absolutePath}")
                    appendLine("Missing:")
                    missing.forEach { appendLine("  - $it") }
                    appendLine("Tip: check if REI_SKIP_NATIVE=1 is set, and ensure NDK+CMake are installed.")
                }
            )
        }
    }
}

tasks.register("verifyDebugApkHasNative") {
    dependsOn("assembleDebug")
    doLast {
        val apk = File(project.buildDir, "outputs/apk/debug/app-debug.apk")
        verifyApkHasNative(apk)
    }
}

tasks.register("verifyReleaseApkHasNative") {
    dependsOn("assembleRelease")
    doLast {
        val apk = File(project.buildDir, "outputs/apk/release/app-release.apk")
        verifyApkHasNative(apk)
    }
}

gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (!wantsRelease) return@whenReady

    val storePasswordValue =
        providers.gradleProperty("REI_KEYSTORE_PASSWORD").orNull ?: System.getenv("REI_KEYSTORE_PASSWORD")
    val keyPasswordValue =
        providers.gradleProperty("REI_KEY_PASSWORD").orNull ?: System.getenv("REI_KEY_PASSWORD")

    if (storePasswordValue.isNullOrBlank() || keyPasswordValue.isNullOrBlank()) {
        throw GradleException("Release 签名需要提供 REI_KEYSTORE_PASSWORD 与 REI_KEY_PASSWORD（通过环境变量或 Gradle 属性）。")
    }
}

dependencies {
    implementation(project(":murasaki-api:api"))
    implementation(project(":murasaki-api:provider"))

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}