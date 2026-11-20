plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// 阶段 G2：MusicFree 插件协议适配层（独立模块，不修改 LX Runtime 核心协议）。
// 依赖 provider:runtime 拿内部音乐模型；依赖 provider:lxruntime 复用候选级网络授权
// （CandidateNetworkPolicy / LxUrlSafety），不自行实现或放宽 SSRF 策略。
kotlin {
    android {
        namespace = "org.feeluown.mobile.provider.musicfree"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":provider:runtime"))
            implementation(project(":provider:lxruntime"))
            implementation(project(":provider:sourceupdate"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
