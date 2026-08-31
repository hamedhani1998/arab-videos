package com.deepdrama.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DD_MAIN = "https://www.deep-drama.com"
private const val DD_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// عناوين Blogger: "مشاهدة مسلسل X مترجم كامل جميع الحلقات HD أونلاين"
private val titleCleanRe = Regex("""مشاهدة\s*مسلسل\s*(.*?)\s*مترجم.*""", RegexOption.DOT_MATCHES_ALL)
private val titleAllRe = Regex("""^\s*(?:مشاهدة\s*)?(?:مسلسل\s*)?(.+?)\s*$""")

private class DdEntry(
    val title: String?,
    val url: String?,
    val poster: String?,
)

/**
 * DeepDrama — موقع Blogger عربي، كل مشاركة = مسلسل كامل في فيديو واحد مدمج
 * بخوادم متعددة (Rumble / voe.sx / vidaraa.cc). Rumble هو الوحيد القابل للتشغيل
 * المباشر (HLS بجميع الجودات)، لذا نجعل منه الخادم الأساسي ونستخدم loadExtractor
 * احتياطياً للبقية.
 */
class DeepDramaProvider : MainAPI() {
    override var name = "Deep Drama"
    override var mainUrl = DD_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "latest" to "أحدث المسلسلات",
    )

    private fun headers() = mapOf("User-Agent" to DD_UA)

    private fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val clean = titleCleanRe.find(title)?.groupValues?.get(1)?.trim()
            ?: titleAllRe.find(title)?.groupValues?.get(1)?.trim()
            ?: title.trim()
        if (clean.isBlank()) return null
        return clean
    }

    // تحليل الصفحة الرئيسية (القائمة الافتراضية) - غير مستخدم فعلياً، نستخدم الـ feed
    private fun parseFeedEntries(text: String): List<DdEntry> {
        val out = mutableListOf<DdEntry>()
        // الاستجابة JSON من Blogger: feed.entry[]
        if (!text.trim().startsWith("{")) return out
        val root = mapper.readTree(text).get("feed") ?: return out
        val entries = root.get("entry") ?: return out
        for (e in entries) {
            val title = e.get("title")?.get("\$t")?.asText()
            var url: String? = null
            val links = e.get("link")
            if (links != null && links.isArray) {
                for (l in links) {
                    if (l.get("rel")?.asText() == "alternate") { url = l.get("href")?.asText(); break }
                }
            }
            // الصورة: أول رابط صورة داخل content
            var poster: String? = null
            val content = e.get("content")?.get("\$t")?.asText()
            if (!content.isNullOrBlank()) {
                val m = Regex("""https://[^\s"'<>]+\.(?:jpg|jpeg|png|webp)[^\s"'<>]*""").find(content)
                poster = m?.value
            }
            out.add(DdEntry(cleanTitle(title), url, poster))
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // Blogger feed يدعم الترحيل عبر start-index
            val start = ((page - 1) * 12) + 1
            val base = "$DD_MAIN/feeds/posts/default?alt=json&max-results=12&start-index=$start"
            val text = app.get(base, headers = headers()).text
            val items = parseFeedEntries(text)
            if (items.isEmpty()) null
            else {
                val list = items.mapNotNull { e ->
                    val title = e.title ?: return@mapNotNull null
                    val url = e.url ?: return@mapNotNull null
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = e.poster
                    }
                }
                newHomePageResponse(request.name, list)
            }
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val base = "$DD_MAIN/feeds/posts/default?alt=json&q=$q&max-results=12"
            val text = app.get(base, headers = headers()).text
            val items = parseFeedEntries(text)
            val searchLower = query.trim().lowercase()
            items.mapNotNull { e ->
                val title = e.title ?: return@mapNotNull null
                val url = e.url ?: return@mapNotNull null
                if (!title.lowercase().contains(searchLower) && !url.lowercase().contains(searchLower)) return@mapNotNull null
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = e.poster
                }
            }
        } catch (e: Exception) { null }
    }

    // أزرار الخوادم في صفحة المسلسل
    private val serverBtnRe = Regex("""xr-server-btn[^>]*data-src="([^"]+)"""")

    private fun serverButtons(html: String): List<String> {
        val out = mutableListOf<String>()
        for (m in serverBtnRe.findAll(html)) {
            val url = m.groupValues[1].trim()
            if (url.isNotBlank()) out.add(url)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = headers()).document

            // العنوان: og:title ثم <title> (jsoup يفك تشفير الكيانات HTML تلقائياً)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { cleanTitle(it) }
                ?: cleanTitle(doc.title()).orEmpty()
                .ifBlank { "Deep Drama" }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

            // الخوادم داخل صفحة المسلسل
            val servers = serverButtons(doc.html())
            if (servers.isEmpty()) return null

            // كل مسلسل = حلقة واحدة (الفيديو الكامل المدمج)
            // نفضّل Rumble لأنه قابل للتشغيل المباشر
            val rumble = servers.firstOrNull { it.contains("rumble.com") }
            val primary = rumble ?: servers.first()
            val episode = newEpisode(primary) {
                name = "الحلقة الكاملة"
                episode = 1
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
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
            var url0 = data
            if (url0.isBlank()) return false

            // Rumble: نستخرج رابط HLS الرئيسي (يشمل جميع الجودات) ومقطع mp4 مباشر
            if (url0.contains("rumble.com")) {
                val embedUrl = url0.substringBefore("?")
                val html = app.get(embedUrl, headers = headers()).text
                val cleaned = html.replace("\\/", "/")
                // master playlist الرئيسي
                val hls = Regex("""https://rumble\.com/[^"'\s<>]*?/playlist\.m3u8""").find(cleaned)?.value
                // مقطع mp4 مباشر
                val vid = Regex("""https://hugh\.cdn\.rumble\.cloud[^"'\s<>]*?\.mp4""").find(cleaned)?.value
                // ملف الصوت المستقل
                val aud = Regex("""https://hugh\.cdn\.rumble\.cloud[^"'\s<>]*?\.aac""").find(cleaned)?.value

                if (!hls.isNullOrBlank()) {
                    callback(newExtractorLink(name, "Rumble HLS (Full)", hls, ExtractorLinkType.M3U8) {
                        this.quality = getQualityFromName("1080p")
                        this.headers = headers()
                    })
                } else if (vid != null) {
                    // HLS قد لا يتوفر في بعض الحالات؛ نعتمد على mp4
                    callback(newExtractorLink(name, "Rumble MP4", vid, ExtractorLinkType.VIDEO) {
                        this.quality = getQualityFromName("1080p")
                        this.headers = headers()
                    })
                } else if (aud != null) {
                    callback(newExtractorLink(name, "Audio AAC", aud, ExtractorLinkType.VIDEO) {
                        this.quality = getQualityFromName("720p")
                        this.headers = headers()
                    })
                }
                return true
            }

            // مضيفات أخرى (voe/vidaraa...) - نجرب مستخرج CloudStream إن وُجد
            loadExtractor(url0, mainUrl, subtitleCallback, callback)
        } catch (e: Exception) { false }
    }
}
