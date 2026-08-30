package com.reelshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class ReelShortProvider : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = "https://www.reelshort.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث المسلسلات",
    )

    private fun extractNextData(html: String): JsonNode? {
        val regex = Regex("""<script id="__NEXT_DATA__" type="application/json">\s*([\s\S]*?)\s*</script>""")
        val m = regex.find(html) ?: return null
        return try { mapper.readTree(m.groupValues[1]) } catch (e: Exception) { null }
    }

    private fun textOrNull(node: JsonNode?, vararg paths: String): String? {
        var n: JsonNode? = node ?: return null
        for (p in paths) {
            val next = n?.get(p) ?: return null
            n = if (next.isNumber) next else next
        }
        return if (n != null && n.isTextual) n.asText() else null
    }

    private fun isVttFamily(videoPic: String?): Boolean =
        videoPic != null && videoPic.contains("/vtt-m3u8/")

    private fun vttMasterUrl(videoPic: String?): String? {
        if (!isVttFamily(videoPic)) return null
        return videoPic!!.replaceAfterLast("/", "h264/h264.m3u8")
    }

    private fun extractTrailerM3u8(html: String): String? {
        val regex = Regex("""\"contentUrl\"\s*:\s*\"(https://[^\"]+\.m3u8[^\"]*)\"""")
        return regex.find(html)?.groupValues?.get(1)
    }

    private data class Book(
        val id: String,
        val title: String,
        val cover: String?,
        val lang: String?,
        val chapterCount: Int?,
    )

    private data class Episode(
        val serialNumber: Int,
        val videoPic: String?,
    )

    private fun parseBooksList(node: JsonNode?): List<Book> {
        if (node == null) return emptyList()
        val list: JsonNode? = if (node.isArray) node
            else node.get("books")?.takeIf { it.isArray }
            ?: node.get("list")?.takeIf { it.isArray }
        if (list == null) return emptyList()
        val out = mutableListOf<Book>()
        for (item in list) {
            val id = textOrNull(item, "book_id") ?: continue
            val title = textOrNull(item, "book_title") ?: continue
            val cover = textOrNull(item, "book_pic")
            val lang = textOrNull(item, "lang")
            val cc = item.get("chapter_count")
            val chapterCount = if (cc != null && cc.isNumber) cc.asInt() else null
            out.add(Book(id, title, cover, lang, chapterCount))
        }
        return out
    }

    private fun parseEpisodes(node: JsonNode?): List<Episode> {
        if (node == null) return emptyList()
        val ob = node.get("online_base")
        val cl = node.get("chapter_list")
        val arr: JsonNode? = if (ob != null && ob.isArray) ob else if (cl != null && cl.isArray) cl else null
            ?: return emptyList()
        val out = mutableListOf<Episode>()
        val seen = HashSet<Int>()
        for (e in arr!!) {
            val snNode = e.get("serial_number")
            if (snNode == null || !snNode.isNumber) continue
            val sn = snNode.asInt()
            if (sn < 1) continue
            if (!seen.add(sn)) continue
            val videoPic = textOrNull(e, "video_pic")
            out.add(Episode(sn, videoPic))
        }
        return out.sortedBy { it.serialNumber }
    }

    private val langNames = mapOf(
        "ar" to "العربية", "de" to "الألمانية", "en" to "الإنجليزية",
        "es" to "الإسبانية", "fr" to "الفرنسية", "in" to "الإندونيسية",
        "it" to "الإيطالية", "ja" to "اليابانية", "pl" to "البولندية",
        "pt" to "البرتغالية", "ro" to "الرومانية", "ru" to "الروسية",
        "th" to "التايلاندية", "tr" to "التركية",
    )

    private suspend fun loadNextData(path: String): JsonNode? = try {
        extractNextData(app.get("$mainUrl$path", headers = mapOf("User-Agent" to UA), referer = mainUrl).text)
    } catch (e: Exception) { null }

    // بناء رابط المسلسل بالشكل الجديد /ar/movie/{slug}-{id} — الموقع أصبح يرفض الرابط بدون slug (301)
    private fun moviePath(title: String, id: String): String {
        val slug = java.net.URLEncoder.encode(title.trim().replace(" ", "-"), "UTF-8")
            .replace("+", "%20")
        return "/ar/movie/$slug-$id"
    }

    private fun bookToSearch(b: Book): SearchResponse =
        newTvSeriesSearchResponse(b.title, "$mainUrl${moviePath(b.title, b.id)}", TvType.TvSeries) {
            this.posterUrl = b.cover
        }

    // استخراج الكتب من HTML مباشرة
    private fun parseBooksFromHtml(html: String): List<SearchResponse> {
        val seen = HashSet<String>()
        val results = mutableListOf<SearchResponse>()
        val regex = Regex("""/ar/movie/([^"'\\]+)""")
        for (m in regex.findAll(html)) {
            val bookId = m.groupValues[1].substringBefore("?").substringBefore("#")
            if (!bookId.all { it.isLetterOrDigit() }) continue
            if (!seen.add(bookId)) continue
            val bookUrl = "$mainUrl/ar/movie/$bookId"
            results.add(newTvSeriesSearchResponse(bookId, bookUrl, TvType.TvSeries))
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = when (request.data) {
            "ar" -> "/ar"
            else -> "/${request.data}"
        }
        val html = try { app.get("$mainUrl$path", headers = mapOf("User-Agent" to UA), referer = mainUrl).text } catch (e: Exception) { "" }

        val all = mutableListOf<SearchResponse>()

        // محاولة 1: __NEXT_DATA__ - البحث عن books في كل الـ fallback
        val root = loadNextData(path)
        if (root != null) {
            val fallback = root.get("props")?.get("pageProps")?.get("fallback")
            if (fallback != null) {
                // البحث في كل المفاتيح
                for (key in fallback.fieldNames()) {
                    val valNode = fallback.get(key)
                    val books = parseBooksList(valNode)
                    if (books.isNotEmpty()) {
                        val seen = HashSet<String>()
                        for (b in books) {
                            if (seen.add(b.id)) all.add(bookToSearch(b))
                        }
                        if (all.isNotEmpty()) break
                    }
                    // بحث في bookShelfList
                    val shelves = valNode?.get("bookShelfList")
                    if (shelves != null && shelves.isArray) {
                        val seen = HashSet<String>()
                        for (shelf in shelves) {
                            for (b in parseBooksList(shelf)) {
                                if (seen.add(b.id)) all.add(bookToSearch(b))
                            }
                        }
                        if (all.isNotEmpty()) break
                    }
                }
            }
        }

        // محاولة 2: HTML
        if (all.isEmpty()) {
            val seen = HashSet<String>()
            for (item in parseBooksFromHtml(html)) {
                if (seen.add(item.url)) all.add(item)
            }
        }

        return if (all.isEmpty()) null else newHomePageResponse(request.name, all)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val searchPath = "/ar/search?keywords=$q&type=movies"
        val html = try { app.get("$mainUrl$searchPath", headers = mapOf("User-Agent" to UA), referer = mainUrl).text } catch (e: Exception) { "" }

        // محاولة 1: __NEXT_DATA__
        val root = loadNextData(searchPath)
        if (root != null) {
            val data = root.get("props")?.get("pageProps")
            if (data != null) {
                val books = parseBooksList(data.get("books"))
                if (books.isNotEmpty()) {
                    val searchLower = query.trim().lowercase()
                    return books.filter { it.title.lowercase().contains(searchLower) }.map { bookToSearch(it) }
                }
            }
        }

        // محاولة 2: HTML
        val htmlBooks = parseBooksFromHtml(html)
        if (htmlBooks.isNotEmpty()) {
            val searchLower = query.trim().lowercase()
            return htmlBooks.filter { it.name.lowercase().contains(searchLower) }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // المسار الكامل بعد /ar/movie/ - قد يكون مجرد id أو {slug}-{id}
        val rawPath = url.substringAfter("/ar/movie/").substringBefore("?").substringBefore("#")
        val pathSegs = rawPath.split("-")
        val bookId = pathSegs.lastOrNull()?.takeIf { it.all(Char::isLetterOrDigit) && it.length >= 8 }
            ?: when {
                url.contains("/ar/episodes/") -> {
                    val path = url.substringAfter("/ar/episodes/").substringBefore("?").substringBefore("#")
                    val parts = path.split("-")
                    if (parts.size >= 4) parts[3] else return null
                }
                else -> url.substringAfterLast("/").substringBefore("?")
            }
        // استخدم المسار الموجود في الرابط مباشرة (يتضمن slug — الموقع يرفض الرابط بدون slug)
        val moviePath = if (rawPath.isNotBlank()) rawPath else bookId
        val res = try {
            app.get("$mainUrl/ar/movie/$moviePath", headers = mapOf("User-Agent" to UA), referer = mainUrl).text
        } catch (e: Exception) { "" }
        val root = loadNextData("/ar/movie/$moviePath") ?: return null
        val data = root.get("props")?.get("pageProps")?.get("data") ?: return null
        val title = textOrNull(data, "book_title") ?: return null
        val cover = textOrNull(data, "book_pic")
        val desc = textOrNull(data, "special_desc") ?: textOrNull(data, "book_sub_title")
        val lang = textOrNull(data, "lang")
        val originalLang = textOrNull(data, "original_lang")
        val isDub = (data.get("is_dub")?.let { if (it.isNumber) it.asInt() == 1 else false }) == true
        val episodes = parseEpisodes(data)

        val plot = buildString {
            if (!desc.isNullOrBlank()) append(desc)
            if (!lang.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append("اللغة: ").append(langNames[lang] ?: lang)
            }
            if (!originalLang.isNullOrBlank() && originalLang != lang) {
                if (isNotEmpty()) append(" • ")
                append("الأصلية: ").append(langNames[originalLang] ?: originalLang)
            }
            if (isDub) {
                if (isNotEmpty()) append(" • ")
                append("مدبلج")
            }
        }.ifBlank { null }

        // حالة 1: توجد حلقات VTT قابلة للتشغيل
        val vttEpisodes = episodes.filter { isVttFamily(it.videoPic) }
        if (vttEpisodes.isNotEmpty()) {
            val eps = vttEpisodes.map { e ->
                val data0 = "${e.videoPic ?: ""}||${e.serialNumber}"
                newEpisode(data0) {
                    episode = e.serialNumber
                    name = "الحلقة ${e.serialNumber}"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = cover
                this.plot = plot
            }
        }

        // حالة 2: حلقات بدون VTT - نبحث عن رابط تشغيل (المقدمة trailer) في صفحة الفيلم
        if (episodes.isNotEmpty()) {
            val trailer = extractTrailerM3u8(res)
            val eps = episodes.map { e ->
                // data: videoPic||serialNumber||trailer — للعروض القديمة نمرر المقدمة كحتوي قابل للتشغيل
                val data0 = "${e.videoPic ?: ""}||${e.serialNumber}||${trailer ?: ""}"
                newEpisode(data0) {
                    episode = e.serialNumber
                    name = "الحلقة ${e.serialNumber}"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = cover
                this.plot = plot
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "videoPic||serialNumber" أو "videoPic||serialNumber||trailer"
        val parts = data.split("||")
        if (parts.size < 2) return false
        val videoPic = parts[0]
        val serialNumber = parts[1]
        val trailer = if (parts.size >= 3) parts[2] else ""

        // حالة العروض القديمة: لا يوجد مقطع قابل للتشغيل لكل حلقة، نعرض المقدمة
        if (trailer.isNotBlank() && !isVttFamily(videoPic)) {
            callback(
                newExtractorLink(name, "ReelShort $serialNumber (مقدمة)", trailer, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("1080p")
                }
            )
            return true
        }

        if (videoPic.isNotBlank() && isVttFamily(videoPic)) {
            val master = vttMasterUrl(videoPic) ?: return false
            return try {
                val masterText = app.get(master, headers = mapOf("User-Agent" to UA), referer = mainUrl).text
                callback(newExtractorLink(name, "ReelShort $serialNumber", master, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("1080p")
                })
                val subRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^>]*NAME="([^"]+)"[^>]*URI="([^"]+)"""")
                for (m in subRegex.findAll(masterText)) {
                    val lang = m.groupValues[1]
                    val uri = m.groupValues[2]
                    val subUrl = if (uri.startsWith("http")) uri else master.replaceAfterLast("/", uri)
                    val cleanLang = when (lang) {
                        "Arabic", "MSA" -> "ar"
                        else -> lang.lowercase().take(2)
                    }
                    try { subtitleCallback(newSubtitleFile(cleanLang, subUrl)) } catch (_: Exception) {}
                }
                true
            } catch (e: Exception) { false }
        }
        return false
    }
}
