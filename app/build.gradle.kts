import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import dev.amatv.build.MusicKitSongIdVisitorFactory
import dev.amatv.build.MusicKitStackMapFrames
import dev.amatv.build.VerifyMusicKitSdkTask
import java.util.Properties
import java.util.Base64
import java.time.Instant

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

fun localSetting(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: fallback

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val bundledDeveloperToken = localSetting("AM_DEVELOPER_TOKEN_FILE").takeIf(String::isNotBlank)
    ?.let { file(it).readText().trim() }
    ?: localSetting("AM_DEVELOPER_TOKEN")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.amatv"
    compileSdk = 36

    defaultConfig {
        // vmlx tv：独立包名，避免与官方 AM for TV 互相覆盖。
        applicationId = "dev.vmlx.tv"
        // P0: 最低版本降到 Android 8.0 (API 26)。MusicKit 官方示例要求 Lollipop(API 21)+，
        // 依赖库 Media3/Compose/tv-material 等 minSdk 均为 21，API 26 无阻塞。
        minSdk = 26
        targetSdk = 36
        // vmlx tv：独立版本线，从 0.1 起排；每次测试构建递增（versionCode 同步递增）。
        versionCode = 5
        versionName = "0.5"

        buildConfigField("String", "AUTH_BROWSER_ACTIVITY", "\"dev.amatv.integration.MusicKitWebAuthActivity\"")
        buildConfigField("boolean", "AUTH_BROWSER_IS_GECKO", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MUSICKIT_DEVELOPER_TOKEN",
            quotedBuildConfig(bundledDeveloperToken),
        )
        buildConfigField(
            "String",
            "MUSICKIT_TOKEN_ENDPOINT",
            quotedBuildConfig(localSetting("AM_TOKEN_ENDPOINT")),
        )
        buildConfigField(
            "String",
            "APPLE_MUSIC_STOREFRONT",
            quotedBuildConfig(localSetting("AM_STOREFRONT", "tw")),
        )
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_ID",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_ID")),
        )
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_SECRET",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_SECRET")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("standalone") {
            initWith(getByName("release"))
            // Same local signing identity lets the TV update without losing its account.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "MUSICKIT_TOKEN_ENDPOINT", "\"\"")
        }
        create("standaloneGecko") {
            initWith(getByName("standalone"))
            matchingFallbacks += listOf("standalone", "release")
            proguardFile("src/standaloneGecko/proguard-rules.pro")
            buildConfigField("String", "AUTH_BROWSER_ACTIVITY", "\"dev.amatv.integration.GeckoMusicKitAuthActivity\"")
            buildConfigField("boolean", "AUTH_BROWSER_IS_GECKO", "true")
            // The default supports both ARM architectures; a device-specific
            // APK can omit the other engine with -Pamatv.geckoAbi=<ABI>.
            val geckoAbi = providers.gradleProperty("amatv.geckoAbi").orNull
            require(geckoAbi == null || geckoAbi in listOf("armeabi-v7a", "arm64-v8a")) {
                "amatv.geckoAbi must be armeabi-v7a or arm64-v8a"
            }
            ndk.abiFilters += geckoAbi?.let(::listOf) ?: listOf("armeabi-v7a", "arm64-v8a")
        }
        create("benchmark") {
            initWith(getByName("standalone"))
            matchingFallbacks += listOf("standalone", "release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // quickjs-kt 用 Kotlin 2.4 编译，其 JVM 签名/ABI 兼容，
        // 跳过元数据版本闸门即可正常引用（veil.music 同款做法）。
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Preserve 64-bit Apple catalog song IDs in the legacy SDK's JSON metadata parser.
// The downloaded official AAR is validated and stays unmodified on disk.
val verifyMusicKitSdkCompatibility = tasks.register<VerifyMusicKitSdkTask>("verifyMusicKitSdkCompatibility") {
    sdkAars.from(fileTree("libs") { include("mediaplayback*.aar") })
}
tasks.named("preBuild").configure { dependsOn(verifyMusicKitSdkCompatibility) }

val musicKitFrameSdkAars = fileTree("libs") { include("mediaplayback*.aar", "musickitauth*.aar") }
val musicKitFrameRepairClasses = providers.provider {
    MusicKitStackMapFrames.missingFrameClasses(musicKitFrameSdkAars.files)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.name == "standaloneGecko") {
            // Direct APK distribution benefits from compressed browser binaries.
            // Android extracts the selected architecture once during installation.
            variant.packaging.jniLibs.useLegacyPackaging.set(true)
        }
        variant.instrumentation.transformClassesWith(
            MusicKitSongIdVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) { parameters ->
            parameters.frameRepairClasses.set(musicKitFrameRepairClasses)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES,
        )
    }
}

val verifyStandaloneCredential = tasks.register("verifyStandaloneCredential") {
    doLast {
        check(bundledDeveloperToken.isNotBlank()) {
            "Set AM_DEVELOPER_TOKEN_FILE to a signed developer token before building a standalone APK."
        }
        val parts = bundledDeveloperToken.split('.')
        check(parts.size == 3) { "Standalone developer token is not a JWT." }
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        check(header.contains("ES256")) { "Standalone developer token must use ES256." }
        val expiry = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        check(expiry > Instant.now().epochSecond + 86_400L) {
            "The standalone credential must remain valid for at least one day. Export a fresh token."
        }
        logger.lifecycle("Standalone developer credential expires ${Instant.ofEpochSecond(expiry)}")
    }
}
tasks.matching { it.name == "preStandaloneBuild" || it.name == "preStandaloneGeckoBuild" || it.name == "preBenchmarkBuild" }
    .configureEach { dependsOn(verifyStandaloneCredential) }

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // P2/LX: 落雪音源播放链路（移植自 veil.music）。
    // quickjs-kt 自带任务泵 + 中断超时 + suspend 绑定（taoweiji 版不跑 Promise 微任务，不可用）。
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v0.6: 资料库导入弹窗二维码（TV 显示，手机扫码打开局域网 Web 管理页）。
    implementation("com.google.zxing:core:3.5.3")
    // v0.8: FFmpeg 音频软解扩展（veil.music 同款做法）。jar 只含 FfmpegAudioRenderer 等
    // Java 类（media3 1.4.1 编译，与 1.11.x 二进制兼容）；原生 libffmpegJNI.so 在
    // src/main/jniLibs/armeabi-v7a 下。缺 .so 不会崩，软解静默回落系统解码。
    implementation(files("libs/media3-ffmpeg.jar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")

    // The last stable Gecko release compatible with this project's Kotlin 2.3.21
    // and compileSdk 36. It is packaged only in the alternate sign-in APK.
    add("standaloneGeckoImplementation", "org.mozilla.geckoview:geckoview:153.0.20260810162159") {
        // Bundling a browser must not replace the app's existing artwork/player
        // Media3 version. MusicKit remains the sole music playback engine.
        exclude(group = "androidx.media3")
    }
}
