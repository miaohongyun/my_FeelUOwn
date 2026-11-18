plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.feature.cast"
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
            api(project(":core:model"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val castRequiredFiles = listOf(
    "feature/cast/build.gradle.kts",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/CastContracts.kt",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/CastPorts.kt",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/CastController.kt",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/DlnaProtocol.kt",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/SsdpProtocol.kt",
    "feature/cast/src/commonMain/kotlin/org/feeluown/mobile/DeviceDescriptionParser.kt",
    "feature/cast/src/commonTest/kotlin/org/feeluown/mobile/DlnaProtocolTest.kt",
    "feature/cast/src/commonTest/kotlin/org/feeluown/mobile/SsdpProtocolTest.kt",
    "feature/cast/src/commonTest/kotlin/org/feeluown/mobile/DeviceDescriptionParserTest.kt",
    "feature/cast/src/commonTest/kotlin/org/feeluown/mobile/CastControllerTest.kt",
)

/**
 * The cast feature owns the DLNA conversation only. It must not reach back into the app shell,
 * the shared composition layer or concrete provider types; the app binds those ports instead.
 */
val castForbiddenSymbols = listOf(
    "AppUiGraph",
    "AppSettingsRepository",
    "AppNavigator",
    "ProviderMusicRepository",
    "DownloadActionPort",
    "PlaybackPayload",
    "AudioQualityPolicy",
)

tasks.register("checkCastFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies or concrete application types in the cast feature."

    inputs.files(castRequiredFiles.map(rootProject::file))
    inputs.file(rootProject.file("feature/cast/build.gradle.kts"))
    inputs.dir(rootProject.file("feature/cast/src/commonMain/kotlin"))

    doLast {
        val missing = castRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Cast feature physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val buildFile = rootProject.file("feature/cast/build.gradle.kts")
        if (Regex("""project\(\s*[\"']?:shared[\"']?\s*\)""").containsMatchIn(buildFile.readText())) {
            throw GradleException(":feature:cast must not depend on :shared; bind application types in the shared composition layer.")
        }
        listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":provider:runtime\")",
            "project(\":provider:netease\")",
            "project(\":provider:qqmusic\")",
            "project(\":provider:bilibili\")",
            "project(\":provider:ytmusic\")",
            "project(\":persistence:",
            "project(\":feature:",
        ).forEach { dependency ->
            if (dependency in buildFile.readText()) {
                throw GradleException(":feature:cast must not depend upward/across on $dependency")
            }
        }

        val sourceRoot = rootProject.file("feature/cast/src/commonMain/kotlin")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    castForbiddenSymbols.firstOrNull { symbol ->
                        Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(line)
                    }?.let { symbol ->
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($symbol)"
                    }
                }.asSequence()
            }
            .toList()
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(":feature:cast leaked application/shared dependencies:")
                    violations.forEach { appendLine(" - $it") }
                },
            )
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkCastFeatureBoundaries")
}
