import javax.inject.Inject
import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    id("kotlin-parcelize")
}

/**
 * Copies a single file from the project directory into a generated assets directory.
 *
 * A plain [Copy] task cannot be wired with
 * `variant.sources.assets.addGeneratedSourceDirectory`, which requires the task to expose
 * its output as a [DirectoryProperty].
 */
abstract class CopyAssetFile : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun copyFile() {
        fileSystemOperations.copy {
            from(sourceFile)
            into(outputDirectory)
        }
    }
}

// Release signing credentials come from the KEY_* environment variables, and from nowhere else
// (see AGENTS.md > 签名与发布). Refusing to read a keystore.properties file is the point: no
// plaintext password can be picked up off the working tree on any machine, this one or CI, and
// nothing here is an undeclared build input. providers.environmentVariable() is used instead of
// System.getenv() so the values stay configuration-cache inputs.
val signingValue: (String) -> String? = { envName ->
    providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
}

// null when KEY_STORE_LOCATION is unset, which is what leaves `hasReleaseSigningConfig` false
// and yields an unsigned release APK instead of a failed build.
val releaseSigningStoreFile = signingValue("KEY_STORE_LOCATION")?.let { rootProject.file(it) }
val releaseSigningStorePassword = signingValue("KEY_STORE_PASSWORD")
val releaseSigningKeyAlias = signingValue("KEY_ALIAS")
val releaseSigningKeyPassword = signingValue("KEY_PASSWORD")
val disableReleaseSigning = providers.gradleProperty("pixelplayer.disableReleaseSigning")
    .getOrElse("false")
    .toBoolean()
val hasReleaseSigningConfig = !disableReleaseSigning &&
    releaseSigningStoreFile?.isFile == true &&
    releaseSigningStorePassword != null &&
    releaseSigningKeyAlias != null &&
    releaseSigningKeyPassword != null

val enableAbiSplits = providers.gradleProperty("pixelplayer.enableAbiSplits")
    .getOrElse("true")
    .toBoolean()

val enableComposeCompilerReports = providers.gradleProperty("pixelplayer.enableComposeCompilerReports")
    .getOrElse("false")
    .toBoolean()

// The in-app changelog reads CHANGELOG.md at runtime, so the repository file is the single
// source of truth instead of a hardcoded list of localized entries.
val generatedChangelogAssets = layout.buildDirectory.dir("generated/assets/changelog")
val copyChangelog = tasks.register<CopyAssetFile>("copyChangelog") {
    sourceFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    outputDirectory.set(generatedChangelogAssets)
}

@Suppress("DEPRECATION")
android {
    namespace = "com.lostf1sh.pixelplayeross"
    compileSdk = 37

    sourceSets {
        getByName("androidTest") {
            assets.directories.add(file("$projectDir/schemas").path)
        }
    }

    androidResources {
        // Keep only the languages we actually ship. Without this the app picks up
        // every locale our AndroidX dependencies carry, which bloats the APK and
        // makes the system language picker offer languages we have no strings for.
        localeFilters.addAll(
            listOf(
                "en", "ar", "de", "es", "fr", "in",
                "it", "ko", "nb", "ru", "tr", "zh-rCN"
            )
        )
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                // ~13 MB compressed / 33 MB raw. Downloaded on demand by
                // JapaneseDictionaryManager and loaded through ExternalDictionaryTokenizer.
                "com/atilika/kuromoji/ipadic/*.bin"
            )
            pickFirsts += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt"
            )
        }
    }

    defaultConfig {
        applicationId = "com.lostf1sh.pixelplayeross"
        minSdk = 30
        targetSdk = 37
        versionCode = (project.findProperty("APP_VERSION_CODE") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("APP_VERSION_NAME") as? String) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = checkNotNull(releaseSigningStoreFile)
                storePassword = checkNotNull(releaseSigningStorePassword)
                keyAlias = checkNotNull(releaseSigningKeyAlias)
                keyPassword = checkNotNull(releaseSigningKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // Separate application id so debug and release can be installed side by side.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // No R8 here. `release` is the default build (see AGENTS.md), so `debug` is only
            // built on demand to chase a problem that resists diagnosis - and for that job an
            // unminified build is strictly better: every stack frame maps to a source line, and
            // nothing the instrumentation reaches for has been rewritten or deleted. Measured
            // cost, arm64-v8a / 0.4.1: 152.1 MB unminified vs 69.2 MB with R8 (release: 37.5 MB).
            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        checkReleaseBuilds = false
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            if (enableAbiSplits) {
                include("arm64-v8a")
                isUniversalApk = false
            }
        }
    }

    bundle {
        abi.enableSplit = true
        density.enableSplit = true
        language.enableSplit = true
    }
}

androidComponents {
    val appVersionName = providers.gradleProperty("APP_VERSION_NAME").getOrElse("0.0.0")
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            output.outputFileName.set("pixelplayeross-${abi}-${appVersionName}-${variant.buildType}.apk")
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyChangelog,
            CopyAssetFile::outputDirectory,
        )
    }
}

composeCompiler {
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")

        if (enableComposeCompilerReports) {
            val buildDir = project.layout.buildDirectory.get().asFile.absolutePath
            freeCompilerArgs.addAll(
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$buildDir/compose_compiler_reports",
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$buildDir/compose_compiler_metrics"
            )
        }

        freeCompilerArgs.addAll(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=${project.rootDir.absolutePath}/app/compose_stability.conf"
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lifecycleprocess)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.paging.common)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.ffmpeg)
    implementation(libs.androidx.media3.exoplayer.midi)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media)
    implementation(libs.coil.compose)
    implementation(libs.taglib)
    implementation(libs.jaudiotagger)
    implementation(libs.vorbisjava.core)
    implementation(libs.wavy.slider)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(libs.timber)
    implementation(libs.smooth.corner.rect.android.compose)
    implementation(libs.reorderables)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.pinyin4j.core)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.accompanist.permissions)
    implementation(libs.capturable) {
        exclude(group = "androidx.compose.animation")
        exclude(group = "androidx.compose.foundation")
        exclude(group = "androidx.compose.material")
        exclude(group = "androidx.compose.runtime")
        exclude(group = "androidx.compose.ui")
    }

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.worktesting)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.uiautomator)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    // `ui-test-manifest` contributes the `androidx.activity.ComponentActivity` that
    // `createComposeRule()` launches. The instrumented tests run on `debug`, so this variant needs
    // it - without it every Compose test dies with "Unable to resolve activity".
    debugImplementation(libs.androidx.ui.test.manifest)

    constraints {
        implementation(libs.netty.common)
        implementation(libs.netty.handler)
        implementation(libs.netty.codec.http)
        implementation(libs.netty.codec.http2)
        implementation(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bcpkix)
        implementation(libs.commons.lang3)
        implementation(libs.jdom2)
        implementation(libs.jose4j)
        implementation(libs.apache.httpclient)
        // `glance-appwidget` -> `compose-remote` asks for `graphics-path:1.1.0-rc01`. Gradle only
        // picks the highest *request* in the graph and never promotes a release candidate to the
        // released version on its own, so pin the stable line here.
        implementation(libs.androidx.graphics.path)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
