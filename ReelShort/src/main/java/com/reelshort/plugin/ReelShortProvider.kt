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

    private fun isVodFamily(videoPic: String?): Boolean =
        videoPic != null && videoPic.contains("/Snapshots/")

    // استخراج رابط الإعلان/المعاينة (m3u8) من contentUrl في JSON-LD schema.org
    private fun extractTrailerM3u8(html: String): String? {
        val regex = Regex("""\"contentUrl\"\s*:\s*\"(https://[^\"]+\.m3u8[^\"]*)\"""")
        return regex.find(html)?.groupValues?.get(1)
    }

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

    private data class Book(
        val id: String,
        val title: String,
        val cover: String?,
        val lang: String?,
        val chapterCount: Int?,
    )

    private data class Episode(
        val serialNumber: Int,
        val chapterId: String?,
        val videoPic: String?,
        val duration: Int?,
    )

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
            val chapterId = textOrNull(e, "chapter_id")
            val videoPic = textOrNull(e, "video_pic")
            val durNode = e.get("duration")
            val duration = if (durNode != null && durNode.isNumber) durNode.asInt() else null
            out.add(Episode(sn, chapterId, videoPic, duration))
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

    private fun bookToSearch(b: Book): SearchResponse =
        newTvSeriesSearchResponse(b.title, "$mainUrl/ar/movie/${b.id}", TvType.TvSeries) {
            this.posterUrl = b.cover
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = when (request.data) {
            "ar" -> "/ar"
            else -> "/${request.data}"
        }
        val root = loadNextData(path) ?: return null
        val fallback = root.get("props")?.get("pageProps")?.get("fallback")
        val webInfo: JsonNode? = if (fallback != null) {
            val fields = fallback.fieldNames()
            var found: JsonNode? = null
            while (fields.hasNext()) {
                val name = fields.next()
                if (name.contains("webInfo")) { found = fallback.get(name); break }
            }
            found
        } else null
        val shelves = webInfo?.get("bookShelfList")
        if (shelves == null || !shelves.isArray) return null
        val all = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (shelf in shelves) {
            for (b in parseBooksList(shelf.get("books"))) {
                if (seen.add(b.id)) all.add(bookToSearch(b))
            }
        }
        return if (all.isEmpty()) null else newHomePageResponse(request.name, all)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val root = loadNextData("/ar/search?keywords=$q&type=movies") ?: return null
        val data = root.get("props")?.get("pageProps") ?: return null
        return parseBooksList(data.get("books")).map { bookToSearch(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val bookId = url.substringAfterLast("/").substringBefore("?")
        val res = try {
            app.get("$mainUrl/ar/movie/$bookId", headers = mapOf("User-Agent" to UA), referer = mainUrl).text
        } catch (e: Exception) { "" }
        val root = loadNextData("/ar/movie/$bookId") ?: return null
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

        // حالة 1: توجد حلقات قابلة للتشغيل (VTT) — رجّع سلسلة
        val playable = episodes.filter { isVttFamily(it.videoPic) }
        if (playable.isNotEmpty()) {
            val eps = playable.map { e ->
                val data0 = "${e.chapterId ?: ""}|${e.videoPic ?: ""}"
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

        // حالة 2: لا توجد VTT، لكن توجد حلقات /Snapshots/ — رجّع كسلسلة بكل الحلقات
        // (التشغيل سيُظهر الإعلان/المعاينة فقط — لا توجد روابط m3u8 لكل حلقة)
        if (episodes.isNotEmpty()) {
            val trailer = extractTrailerM3u8(res)
            val eps = episodes.map { e ->
                val data0 = if (trailer != null) "trailer|$trailer" else ""
                newEpisode(data0) {
                    episode = e.serialNumber
                    name = "الحلقة ${e.serialNumber} (إعلان)"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = cover
                this.plot = (plot?.let { "$it • " } ?: "") + "إعلان/معاينة فقط — روابط التشغيل الكاملة غير متاحة"
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
        val parts = data.split("|")
        if (parts.size < 2) return false
        val chapterId = parts[0]
        val videoPic = parts[1]
        // 0) رابط الإعلان/المعاينة (محتوى قديم /Snapshots/)
        if (chapterId == "trailer" && videoPic.isNotBlank()) {
            callback(newExtractorLink(name, "ReelShort (إعلان)", videoPic, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = getQualityFromName("720p")
            })
            return true
        }
        // 1) المسار الرئيسي: عائلة VTT (يمكن استخراج كل الحلقات)
        if (videoPic.isNotBlank() && isVttFamily(videoPic)) {
            val master = vttMasterUrl(videoPic) ?: return false
            return try {
                val masterText = app.get(master, headers = mapOf("User-Agent" to UA), referer = mainUrl).text
                callback(newExtractorLink(name, "ReelShort 1080p", master, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("1080p")
                })
                for (lang in langNames.keys) {
                    val subUrl = master.replaceAfterLast("/", "subtitle/$lang.m3u8")
                    callback(newExtractorLink(
                        source = name,
                        name = "ReelShort $lang",
                        url = subUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        referer = mainUrl
                    })
                }
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
            } catch (e: Exception) {
                false
            }
        }
        // 2) المسار البديل: محتوى قديم بنمط /Snapshots/ (نُمرّر الإعلان/المعاينة فقط)
        return false
    }
}
