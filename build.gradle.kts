// AGP 9 компилирует Kotlin сам, без плагина kotlin-android. Эта зависимость поднимает
// версию встроенного компилятора до той, что указана в каталоге версий, чтобы она
// совпадала с версией Compose-плагина.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
