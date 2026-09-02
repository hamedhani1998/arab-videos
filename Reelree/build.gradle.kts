plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reelree.plugin"
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
    description = "إضافة Reelree - مسلسلات قصيرة مترجمة ومدبلجة"
    authors = listOf("hamedhani1998")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "ar"
}
