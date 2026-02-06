// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Provide default Android configs for library subprojects (e.g. murasaki-api) to avoid
// "compileSdkVersion is not specified" when they rely on the root to set it.
val androidCompileSdkVersion = 36
val androidMinSdkVersion = 24
val androidTargetSdkVersion = 36
rootProject.extra["androidCompileSdkVersion"] = androidCompileSdkVersion
rootProject.extra["androidMinSdkVersion"] = androidMinSdkVersion
rootProject.extra["androidTargetSdkVersion"] = androidTargetSdkVersion

// Rei 唯一后端：reid = apd + ksud 合一（单二进制，按 argv[0] 分发；安装时硬链接为 reid/apd/ksud）

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk = androidCompileSdkVersion
            defaultConfig {
                minSdk = androidMinSdkVersion
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = androidCompileSdkVersion
            defaultConfig {
                minSdk = androidMinSdkVersion
                targetSdk = androidTargetSdkVersion
            }
        }
    }
}