import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * versionCode 与版本号对齐：`major*10000 + minor*100 + patch`，每段各占两位十进制
 * （所以 minor / patch 必须 ≤ 99，否则会挤进高一段的位置）。
 *
 * 这么写的好处是「版本号的先后顺序」永远等于「数字的大小顺序」，Android 判断
 * 升级 / 降级不会出错，而且从 versionCode 能直接读出是哪个版本。
 *
 * 写成函数而不是一行字面量，是为了避开 lint 的 `AccidentalOctal`——
 * 表达式以 `0 *` 开头时它会把那个 0 当成八进制前缀。
 */
fun tallyVersionCode(major: Int, minor: Int, patch: Int): Int = major * 10000 + minor * 100 + patch

/**
 * 统一签名：debug 与 release 共用一把 keystore，两台机器、两台机器之间覆盖安装不会再撞签名。
 *
 * 那把钥匙（`keystore/tally-unified.jks`）**随仓库一起提交**，用的是 Android 调试钥匙那一套公开常量
 * （store/key 口令 `android`，别名 `androiddebugkey`）——克隆下来不用任何配置就能 `assembleDebug`。
 * 它是测试钥匙，不是秘密：谁都能用同一把钥匙签出同签名的包，所以**真要正式发版请换成自己的钥匙**，
 * 换法是在仓库根的 `local.properties` 里覆盖下面这四项（`local.properties` 不进 git）。
 */
val tallyLocalProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun tallySigningProp(key: String, fallback: String): String =
    tallyLocalProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: fallback

fun tallyKeystoreFile(path: String): File = file(path).apply {
    if (!exists()) error(
        "找不到签名文件 $path：仓库自带的是 tally-app/keystore/tally-unified.jks；" +
            "若你在 local.properties 里覆盖了 tally.keystore，请确认该路径相对 tally-app/ 存在",
    )
}

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.guohangbk.tally"
    compileSdk = 37
    compileSdkMinor = 0

    signingConfigs {
        create("unified") {
            // 相对 tally-app 模块目录，两台机器放在同一路径下即可复用。
            storeFile = tallyKeystoreFile(tallySigningProp("tally.keystore", "keystore/tally-unified.jks"))
            storePassword = tallySigningProp("tally.storePassword", "android")
            keyAlias = tallySigningProp("tally.keyAlias", "androiddebugkey")
            keyPassword = tallySigningProp("tally.keyPassword", "android")
        }
    }

    defaultConfig {
        applicationId = "io.github.guohangbk.tally"
        minSdk = 28
        targetSdk = 37
        // 0.1.2 → 102。已发布的 0.1.0 是 versionCode 1、0.1.1 是 101
        // （0.1.0 当年没走公式，属历史事实，不再改）。
        versionCode = tallyVersionCode(major = 0, minor = 1, patch = 2)
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 库瘦身：ML Kit 等依赖默认带多套 ABI 的 .so，真机只保留 arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("unified")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("unified")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // M6-00：小莉自写薄客户端的协议层与端侧 OCR，不引入任何 AI 框架。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 纯 JVM 单测：JUnit 5 + org.json 的可运行实现。
    // 断言只用 kotlin.test，不引 mock 框架——数据层测试靠内存 fixture，不靠打桩。
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
    }
}

