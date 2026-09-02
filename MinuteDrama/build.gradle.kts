plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minutedrama.plugin"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

version = 1

cloudstream {
    description = "إضافة MinuteDrama - مسلسلات دراما قصيرة إنجليزية"
    authors = listOf("hamedhani1998")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "en"
}
