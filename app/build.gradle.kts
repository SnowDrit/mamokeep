import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
}

val releaseSigningProperties = Properties().apply {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) localProperties.inputStream().use { load(it) }
}

android {
    namespace = "io.github.snowdrit.mamokeep"
    // Компилируемся против новейшего SDK, потому что этого требуют текущие AndroidX.
    // targetSdk держим на 36 до разбора поведенческих изменений Android 37 —
    // это независимые настройки.
    compileSdk = 37

    // The in-app language picker must work offline with every bundled language.
    bundle {
        language { enableSplit = false }
    }

    // Local device QA uses the same optimized arm64 APK on the phone and emulator.
    // Keep the universal artifact for other architectures and leave normal builds unchanged.
    splits {
        abi {
            isEnable = providers.gradleProperty("localAbiApks").orNull == "true"
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    defaultConfig {
        applicationId = "io.github.snowdrit.mamokeep"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Нужен тестам миграций: они инструментальные, потому что `MigrationTestHelper`
        // от Room иначе не заводится (см. docs/STATUS.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseSigningProperties.getProperty("release.storeFile")?.let {
                rootProject.file(it)
            }
            storeType = "PKCS12"
            storePassword = releaseSigningProperties.getProperty("release.storePassword")
            keyAlias = releaseSigningProperties.getProperty("release.keyAlias")
            // The PKCS12 key created for both stores uses the keystore password.
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 включён: он режет и ускоряет загрузку классов, и до публикации
            // всё равно нужен. Замер 2026-08-22 на живом Realme — в docs/STATUS.md.
            isMinifyEnabled = true
            isShrinkResources = true
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
        aidl = true
        compose = true
        // Нужен BuildConfig.DEBUG: пульс ежесуточной проверки шлётся только в отладочной
        // сборке — в релизе ежедневное «я жив» было бы издевательством.
        buildConfig = true
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val missingProperties = listOf("release.storeFile", "release.storePassword", "release.keyAlias")
        .filter { releaseSigningProperties.getProperty(it).isNullOrEmpty() }
    val keyFile = android.signingConfigs.getByName("release").storeFile
    doFirst {
        check(missingProperties.isEmpty()) {
            "Release signing is not configured: ${missingProperties.joinToString()}. " +
                "Run tools/configure_release_signing.py; see BUILD_FROM_SOURCE.md."
        }
        check(keyFile?.isFile == true) { "Release signing keystore does not exist." }
    }
}

// Схема БД в репозитории: миграции без неё писать вслепую.
//
// Через плагин Room, а не через `ksp arg("room.schemaLocation", ...)`. Разница не
// косметическая: аргумент процессора пишет файл мимо объявленных выходов Gradle, поэтому
// при попадании `kspDebugKotlin` в кеш сборки схема новой версии **не появляется вовсе** —
// на диске остаётся вчерашняя. Поймано тестом `выгруженная_схема_не_отстаёт_от_версии_базы`
// 2026-08-22: версия базы 11, а свежайший файл в `app/schemas` — 10.
room {
    schemaDirectory("$projectDir/schemas")
}
// Своей задачей `copyRoomSchemasToAndroidTestAssets` плагин заодно кладёт схемы в ассеты
// тестового APK — `MigrationTestHelper` читает их именно оттуда. Руками это прописывать
// не нужно, и не нужно связывать задачи между собой: плагин объявляет схемы своим
// выходом, а не пишет их мимо Gradle.

// Одного плагина мало: его задача копирования успевает отработать **до** того, как KSP
// выгрузит схему новой версии. Первый прогон после подъёма версии тогда падает не с тем
// сообщением — вместо «миграции с 7 на 11 нет» получаем «файл схемы не найден», и следующий
// заход полчаса ищет несуществующую поломку сборки. Проверено в обе стороны 2026-08-22.
tasks.matching { it.name.startsWith("copyRoomSchemasToAndroidTestAssets") }.configureEach {
    dependsOn("kspDebugKotlin")
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    // Миграции проверяются только инструментально: у android-варианта
    // `MigrationTestHelper` каждый конструктор требует `Instrumentation`, а схемы читает
    // из ассетов тестового APK. Разбор попытки обойтись юнит-тестами — в docs/STATUS.md.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
