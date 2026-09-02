package com.minutedrama.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * MinuteDrama — موقع دراما قصيرة (cdn3.minutedrama.com).
 *
 * بنية الموقع:
 *  - القوائم: a.md-card → img[data-src] poster + img[alt] title
 *  - البحث: /ar/search?keyword=... → a.drama-card-popular
 *  - التفاصيل: /ar/tv-desc/{slug}/{id} — يحتوي مصفوفة JSON مدمجة بالحلقات
 *      episodeVideoUrl → mp4 مباشر (720p) من cdn3.minutedrama.com
 *      bkEpisodeVideoUrl → نسخة احتياطية من cdn4
 *      textTrackUrl → ترجمة VTT (ar/en)
 *  - التصنيفات: POST /en/categoryTvs/{id}/pageNum/{page} → JSON tvs[]
 *      (API التصنيفات تعمل فقط مع /en/)
 *
 * القاعدة الأساسية = القسم العربي: https://minutedrama.com/ar
 */
class MinuteDramaProvider : MainAPI() {
    override var name = "MinuteDrama"
    override var mainUrl = "https://minutedrama.com/ar" // القاعدة: القسم العربي
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val origin = "https://minutedrama.com"
    private val apiLoc = "en" // API التصنيفات يعمل مع /en/ فقط

    // أقسام الموقع العربي — من الصفحة الرئيسية العربية
    private val categories = listOf(
        "96" to "دراما إنجليزية",
        "73" to "انتقام",
        "98" to "حب",
        "1" to "ملياردير",
        "26" to "رئيس تنفيذي",
        "33" to "نساء مستقلات",
        "35" to "سيدة المجتمع",
        "59" to "نمو ذاتي",
        "70" to "هوية مخفية",
        "75" to "رومانسية تعاقدية",
        "77" to "حب بعد الزواج",
    )

    override val mainPage = mainPageOf(
        *categories.map { (id, label) -> id to label }.toTypedArray()
    )

    /** يحلل بطاقات المسلسلات من الصفحة. */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // البطاقات الرئيسية: a.md-card
        doc.select("a.md-card[href*=/tv-desc/]").forEach { a ->
            val href = a.attr("href").ifBlank { return@forEach }
            if (href in seen) return@forEach
            seen.add(href)
            val img = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim().ifBlank { return@forEach }
            val poster = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
            results.add(newMovieSearchResponse(title, "$origin$href", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        // نتائج البحث: a.drama-card-popular
        doc.select("a.drama-card-popular[href*=/tv-desc/]").forEach { a ->
            val href = a.attr("href").ifBlank { return@forEach }
            if (href in seen) return@forEach
            seen.add(href)
            val title = a.selectFirst(".drama-title-popular")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach
            val poster = a.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.ifBlank { null }
            results.add(newMovieSearchResponse(title, "$origin$href", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        // البانر: a[href*=/tv-desc/] + h2.md-banner-title
        doc.select("a[href*=/tv-desc/] h2.md-banner-title").forEach { h2 ->
            val a = h2.parent() ?: return@forEach
            val parent = a.parent() ?: return@forEach
            val link = if (a.tagName() == "a") a else parent.selectFirst("a[href*=/tv-desc/]")
                ?: return@forEach
            val href = link.attr("href").ifBlank { return@forEach }
            if (href in seen) return@forEach
            seen.add(href)
            val title = h2.text().trim().ifBlank { return@forEach }
            val poster = parent.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.ifBlank { null }
            results.add(newMovieSearchResponse(title, "$origin$href", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        return results
    }

    /** يستخرج مصفوفة الحلقات JSON المدمجة في صفحة التفاصيل. */
    private fun extractEpisodeJson(pageSource: String): List<JsonNode>? {
        return try {
            val idx = pageSource.indexOf("\"episodeVideoUrl\"")
            if (idx < 0) return null
            val start = pageSource.lastIndexOf('[', idx)
            val end = pageSource.indexOf(']', idx)
            if (start < 0 || end < 0) return null
            mapper.readTree(pageSource.substring(start, end + 1)).toList()
        } catch (_: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val catId = request.data
            val json = app.post(
                "$origin/$apiLoc/categoryTvs/$catId/pageNum/$page",
                referer = "$mainUrl/",
                headers = mapOf("Content-Type" to "application/json")
            ).text
            val tree = mapper.readTree(json)
            val tvs = tree.get("dataResult")?.get("tvs") ?: return null
            val items = tvs.mapNotNull { tv ->
                val id = tv.get("id")?.asInt() ?: return@mapNotNull null
                val title = tv.get("title")?.asText() ?: return@mapNotNull null
                val cover = tv.get("coverUrl")?.asText()
                val slug = title.lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "")
                    .replace(Regex("\\s+"), "-")
                    .replace(Regex("-+"), "-").trim('-')
                newMovieSearchResponse(title, "$origin/ar/tv-desc/$slug/$id", TvType.TvSeries) {
                    this.posterUrl = cover
                }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get(
                "$mainUrl/search?keyword=${query.trim().replace(" ", "+")}",
                referer = "$mainUrl/"
            ).document
            parseCards(doc)
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = "$mainUrl/").document
            val pageSource = doc.html()

            // استخراج معرف المسلسل من URL (آخر أرقام بعد آخر /)
            val tvId = url.substringAfterLast("/").substringBefore("?").toIntOrNull()

            // استخراج مصفوفة الحلقات
            val jsonArr = extractEpisodeJson(pageSource)
            if (jsonArr.isNullOrEmpty()) return null

            // العنوان
            val title = jsonArr.firstOrNull()?.get("tvTitle")?.asText()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null

            // الصورة
            val tvIdFromJson = jsonArr.firstOrNull()?.get("tvId")?.asInt() ?: tvId
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: tvIdFromJson?.let { "$origin/cover/${it}_en.jpg" }

            // الوصف
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            // إنشاء الحلقات
            val episodes = jsonArr.mapNotNull { ep ->
                val epNum = ep.get("episodeNum")?.asInt()
                    ?: return@mapNotNull null
                newEpisode("$mainUrl/ep|$tvId|$epNum") {
                    this.episode = epNum
                    this.name = "الحلقة $epNum"
                }
            }.sortedBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|", limit = 3)
            if (parts.size < 3) return false
            val tvId = parts[1].trim().toIntOrNull() ?: return false
            val epNum = parts[2].trim().toIntOrNull() ?: return false

            // نجلب صفحة التفاصيل من جديد للحصول على روابط طازجة (التوكن ينتهي)
            val doc = app.get("$mainUrl/tv-desc/x/$tvId", referer = "$mainUrl/").document
            val jsonArr = extractEpisodeJson(doc.html()) ?: return false

            val ep = jsonArr.find { it.get("episodeNum")?.asInt() == epNum } ?: return false

            val videoUrl = ep.get("episodeVideoUrl")?.asText()
            val backupUrl = ep.get("bkEpisodeVideoUrl")?.asText()
            val subtitleUrlAr = ep.get("textTrackUrl")?.asText() // رابط عربي من الصفحة

            // مصدر الرئيسي — MP4 مباشر
            videoUrl?.let {
                callback(newExtractorLink(name, "الحلقة $epNum", it, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = getQualityFromName("720p")
                })
            }

            // مصدر احتياطي — CDN4
            backupUrl?.let {
                callback(newExtractorLink(name, "الحلقة $epNum (احتياطي)", it, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = getQualityFromName("720p")
                })
            }

            // ترجمة عربية — من رابط VTT العربي
            try { subtitleUrlAr?.let { subtitleCallback(newSubtitleFile("العربية", it)) } } catch (_: Exception) {}

            // ترجمة إنجليزية — نستبدل ar بـ en في الرابط (إن تغيّر الرابط فعلًا)
            val subtitleUrlEn = subtitleUrlAr?.replace("/ar/", "/en/")?.replace("_ar.vtt", "_en.vtt")
            if (subtitleUrlEn != null && subtitleUrlEn != subtitleUrlAr) {
                try { subtitleCallback(newSubtitleFile("English", subtitleUrlEn)) } catch (_: Exception) {}
            }

            true
        } catch (e: Exception) { false }
    }
}
