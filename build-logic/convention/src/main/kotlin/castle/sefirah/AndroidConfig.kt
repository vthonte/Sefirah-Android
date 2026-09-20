package castle.sefirah

import org.gradle.api.JavaVersion as GradleJavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget


object AndroidConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 23

    val JavaVersion = GradleJavaVersion.VERSION_17
    val JvmTarget = KotlinJvmTarget.JVM_17
}

object AppConfig {
    const val DEFAULT_APP_NAME = "Sefirah AI"
    const val DEFAULT_APPLICATION_ID = "com.castle.sefirah.ai"
    const val DEFAULT_VERSION_NAME = "3.1.0"
    const val DEFAULT_VERSION_CODE = 35
}