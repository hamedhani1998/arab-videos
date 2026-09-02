package com.minutedrama.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * MinuteDrama — موقع دراما قصيرة (cdn3.minutedrama.com).
 *
 * بنية الموقع (القسم العربي /ar/):
 *  - الصفحة الرئيسية: أقسام (h2.md-section-title) وكل قسم فيه صف بطاقات
 *      a.md-card → img[data-src] poster (cover/{id}_ar.jpg) + img[alt] title (عربي)
 *  - البحث: /ar/search?keyword=... → a.drama-card-popular
 *  - التفاصيل: /ar/tv-desc/{slug}/{id} — العنوان العربي في h1، ومصفوفة JSON مدمجة بالحلقات
 *      episodeVideoUrl → mp4 مباشر (720p) من cdn3.minutedrama.com
 *      bkEpisodeVideoUrl → نسخة احتياطية من cdn4
 *      textTrackUrl → ترجمة VTT (ar/en)
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

    // أقسام الموقع العربي — من الصفحة الرئيسية العربية
    private val categories = listOf(
        "دراما إنجليزية",
        "انتقام",
        "حب",
        "ملياردير",
        "رئيس تنفيذي",
        "نساء مستقلات",
        "سيدة المجتمع",
        "نمو ذاتي",
        "هوية مخفية",
        "رومانسية تعاقدية",
        "حب بعد الزواج",
    )

    override val mainPage = mainPageOf(
        *categories.map { it to it }.toTypedArray()
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

    /** يحلل بطاقات صف واحد (قسم) من الصفحة الرئيسية العربية. */
    private fun parseRowCards(row: Element): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        row.select("a.md-card[href*=/tv-desc/]").forEach { a ->
            val href = a.attr("href").ifBlank { return@forEach }
            val img = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim().ifBlank { return@forEach }
            val poster = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
            results.add(newMovieSearchResponse(title, "$origin$href", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return results
    }

    /** يجلب الصفحة الرئيسية العربية مرة واحدة ويبني خريطة (اسم القسم ← بطاقاته بالعربي). */
    private suspend fun buildHomeSections(): Map<String, List<SearchResponse>> {
        val doc = app.get("$mainUrl/", referer = "$mainUrl/").document
        val map = mutableMapOf<String, MutableList<SearchResponse>>()
        doc.select("a.md-section-link").forEach { link ->
            val title = link.selectFirst(".md-section-title")?.text()?.trim() ?: return@forEach
            // صف البطاقات هو العنصر التالي للحاوية: <div class="md-section-header"> ... <div class="md-row">
            var container: Element? = link.parent()
            var row: Element? = null
            while (container != null) {
                row = container.nextElementSibling()?.takeIf { it.hasClass("md-row") }
                    ?: container.selectFirst(".md-row[data-md-row]")
                if (row != null) break
                container = container.parent()
            }
            val cards = parseRowCards(row ?: return@forEach)
            if (cards.isNotEmpty()) map.getOrPut(title) { mutableListOf() }.addAll(cards)
        }
        return map
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // الصفحة الرئيسية العربية: نعرض كل قسم بأسمائه العربية. (الصفحة 1 فقط تعكس الموقع)
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val sections = buildHomeSections()
            val items = sections[request.data] ?: emptyList()
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            // البحث يعمل عبر الوسم ?q= (وليس keyword= الذي يعيد قائمة ثابتة)
            // وترميز الاستعلام إلزامي للأسماء العربية
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val doc = app.get(
                "$mainUrl/search?q=$encoded",
                referer = "$mainUrl/"
            ).document
            // نتائج البحث في بطاقات عامة a[href*=/tv-desc/] — نستخرج الغلاف والاسم العربي منها
            val results = mutableListOf<SearchResponse>()
            val seen = mutableSetOf<String>()
            doc.select("a[href*=/tv-desc/]").forEach { a ->
                val href = a.attr("href").ifBlank { return@forEach }
                if (href in seen) return@forEach
                val img = a.selectFirst("img[alt]") ?: a.selectFirst("img") ?: return@forEach
                val title = img.attr("alt").trim().ifBlank { return@forEach }
                // لا نأخذ صور الافتراضي/الأيقونات الصغيرة، بل غلاف المسلسل
                val poster = img.attr("data-src").ifBlank { img.attr("src") }
                    .takeIf { it.isNotBlank() && !it.endsWith("default.png") }
                seen.add(href)
                results.add(newMovieSearchResponse(title, "$origin$href", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            results
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

            // العنوان — h1 في القسم العربي هو الاسم العربي (مثل "مؤامرة فينيكس")
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: jsonArr.firstOrNull()?.get("tvTitle")?.asText()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null

            // الصورة — الغلاف العربي cover/{id}_ar.jpg
            val tvIdFromJson = jsonArr.firstOrNull()?.get("tvId")?.asInt() ?: tvId
            val poster = (tvIdFromJson ?: tvId)?.let { "$origin/cover/${it}_ar.jpg" }

            // الحبكة/المقدمة — النص العربي من قسم "المقدمة" (data-full-text فيه النص الكامل)
            val plot = doc.selectFirst("span.description-text")
                ?.attr("data-full-text")?.trim()?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst(".description-text")?.text()?.trim()

            // الوسوم (التصنيفات) — مثل الموقع: أخلاقيات، مغامرة، هوية مخفية...
            val tags = doc.select("a.tv-tag").mapNotNull { it.text().trim().ifBlank { null } }

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
                this.tags = tags
            }
        } catch (e: Exception) { null }
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
