rootProject.name = "arab-videos"

// يكتشف الوحدات تلقائياً — كل مجلد يحتوي build.gradle.kts يُضمَّن كوحدة.
// لإخفاء وحدة، أضف اسمها إلى قائمة disabled.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
