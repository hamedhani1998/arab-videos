package com.reelree.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Reelree — موقع دراما قصيرة عمودية (مترجمة/مدبلجة).
 *
 * بنية الموقع (فحص فعلي 2026-09):
 *  - القوائم: article.rr-card > a.rr-card-link + img + h3
 *  - التفاصيل: عنصر .rr-player يحمل سمات data-*:
 *      data-title, data-poster, data-episodes, data-series, data-source, data-orientation
 *      data-media  → قالب الحلقات: يُستبدل %EP% برقم الحلقة (m3u8 أو mp4)
 *      data-rr-server2 → JSON { code, direct, embed, offsets[] } = "السيرفر الكامل"
 *          ملف merged واحد يضم كل الحلقات، بجودات متعددة (+ ترجمات/أصوات إن وُجدت في master).
 *          direct = https://reelree.com/api/v2/{code}.m3u8  ← master متعدد الجودات
 *
 * loadLinks يقدّم سيرفرين لكل حلقة:
 *  1) "الحلقات"        ← عبر data-media / %EP% (الحلقة المستقلة)
 *  2) "السيرفر الكامل" ← عبر data-rr-server2.direct (الملف المدمج بكل الجودات/الترجمات/الأصوات)
 */
class ReelreeProvider : MainAPI() {
    override var name = "Reelree"
    override var mainUrl = "https://reelree.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "explore/" to "أحدث المسلسلات",
        "explore/?sort=trending" to "الأكثر مشاهدة",
        "tag/metarjam-arabi/" to "مترجم عربي",
        "tag/mudabalaj-arabi/" to "مدبلج عربي",
        "tag/lang-en/" to "بالإنجليزية",
    )

    private data class RrServer(
        val code: String,
        val direct: String?,
        val embed: String?,
        val offsets: List<Offset>,
    )

    private data class Offset(val ep: Int, val start: Double, val dur: Double)

    private fun parseCards(doc: Document, typ: TvType = TvType.TvSeries): List<SearchResponse> {
        return doc.select("article.rr-card").mapNotNull { card ->
            try {
                val a = card.selectFirst("a.rr-card-link") ?: card.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = card.selectFirst("h3")?.text()?.trim()
                    ?: a.attr("aria-label")?.trim()
                    ?: a.attr("title")?.trim()
                    ?: return@mapNotNull null
                val poster = card.selectFirst("img.rr-poster")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }?.ifBlank { null }

                newMovieSearchResponse(title, href, typ) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val base = request.data
            val url = when {
                base.contains("?") -> {
                    val (path, q) = base.split("?", limit = 2)
                    if (page > 1) "$mainUrl/$path/page/$page/?$q" else "$mainUrl/$path/?$q"
                }
                else -> if (page > 1) "$mainUrl/${base}page/$page/" else "$mainUrl/$base"
            }
            val doc = app.get(url, referer = mainUrl).document
            newHomePageResponse(request.name, parseCards(doc))
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
            parseCards(doc)
        } catch (e: Exception) { null }
    }

    /** يحلل data-rr-server2 (HTML-entities) إلى RrServer. */
    private fun parseRrServer(raw: String?): RrServer? {
        if (raw.isNullOrBlank()) return null
        return try {
            val text = raw.replace("&quot;", "\"").replace("\\/", "/")
            val node = mapper.readTree(text)
            val code = node.get("code")?.asText() ?: return null
            val direct = node.get("direct")?.asText()
            val embed = node.get("embed")?.asText()
            val offsets = mutableListOf<Offset>()
            val arr = node.get("offsets")
            if (arr != null && arr.isArray) {
                for (o in arr) {
                    val ep = o.get("ep")?.asInt() ?: continue
                    val start = o.get("start")?.asDouble() ?: 0.0
                    val dur = o.get("dur")?.asDouble() ?: 0.0
                    offsets.add(Offset(ep, start, dur))
                }
            }
            RrServer(code, direct, embed, offsets)
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val watch = doc.selectFirst("[data-media]")

            val title = watch?.attr("data-title")?.trim()
                ?.let { cleanTitle(it) }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
                ?: doc.selectFirst("h1, [data-title]")?.text()?.trim()
                ?: return null

            val poster = watch?.attr("data-poster")?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            val mediaTemplate = watch?.attr("data-media")?.trim()
            val episodes = watch?.attr("data-episodes")?.trim()?.toIntOrNull() ?: 1
            val dataSource = watch?.attr("data-source")?.trim().orEmpty()
            val rrServer = parseRrServer(watch?.attr("data-rr-server2"))

            // بيانات لكل حلقة: mediaTemplate | رقم الحلقة | master السيرفر الكامل (قد يكون فارغاً)
            val eps = (1..episodes).map { n ->
                val fullMaster = rrServer?.direct.orEmpty()
                val data = "$mediaTemplate|$n|$fullMaster"
                newEpisode(data) {
                    episode = n
                    name = "الحلقة $n"
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOf(dataSource).filter { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
    }

    /** يفحص master ويعرّف جوداته عبر nil #EXT-X-STREAM-INF، ويرجع قائمة (url, qualityLabel). */
    private fun extractVariants(masterText: String, baseUrl: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val lines = masterText.split("\n")
        val resRe = Regex("""RESOLUTION=(\d+x(\d+))""")
        val idxRe = Regex("""BANDWIDTH=(\d+)""")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(i + 1)?.trim() ?: run { i++; continue }
                val resMatch = resRe.find(line)
                var uri = next
                if (!uri.startsWith("http")) uri = baseUrl.substringBeforeLast("/") + "/" + uri
                val q = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: idxRe.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { bw ->
                        when {
                            bw >= 4000000 -> 1080
                            bw >= 2000000 -> 720
                            else -> 480
                        }
                    }
                    ?: 720
                out.add(uri to q)
                i++
            }
            i++
        }
        return out
    }

    /** يفحص master ويستخرج الترجمات (SUBTITLES) والأصوات (AUDIO) إذا وُجدت. */
    private class Track(val kind: String, val lang: String, val uri: String)

    private fun extractTracks(masterText: String, baseUrl: String): List<Track> {
        val out = mutableListOf<Track>()
        val re = Regex("""#EXT-X-MEDIA:TYPE=([A-Z]+)[^#]*?NAME="([^"]+)"[^#]*?URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(masterText)) {
            val kind = m.groupValues[1].uppercase()
            if (kind != "SUBTITLES" && kind != "AUDIO") continue
            val lang = m.groupValues[2]
            var uri = m.groupValues[3]
            if (!uri.startsWith("http")) uri = baseUrl.substringBeforeLast("/") + "/" + uri
            out.add(Track(kind, lang, uri))
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 3)
        val template = parts.getOrNull(0)?.trim() ?: return false
        val ep = parts.getOrNull(1)?.trim() ?: return false
        val fullMaster = parts.getOrNull(2)?.trim().orEmpty()
        if (template.isBlank() || !template.startsWith("http")) return false

        var found = false

        // ===== سيرفر 1: الحلقات — عبر data-media / %EP% =====
        // ملاحظة: روابط /api/dx/{id}/{n}.m3u8 رغم امتدادها .m3u8 تُرجع في الواقع ملف MP4
        // مباشر (Content-Type: video/mp4). لذلك نعاملها كـ VIDEO مباشر لا كـ HLS — فمشغّل
        // HLS يفشل عليها (Source error). نُرسلها مباشرة للمشغّل دون جلب (فهي ملف كبير).
        try {
            val epUrl = template.replace("%EP%", ep)
            if (epUrl.endsWith(".m3u8")) {
                // رغم الامتداد .m3u8 المحتوى mp4 مباشر → VIDEO
                callback(newExtractorLink(name, "الحلقات $ep", epUrl, ExtractorLinkType.VIDEO) {
                    referer = mainUrl
                    quality = getQualityFromName("720p")
                })
                found = true
            } else {
                // mp4 / رابط مباشر آخر
                callback(newExtractorLink(name, "الحلقات $ep", epUrl, ExtractorLinkType.VIDEO) {
                    referer = mainUrl
                    quality = getQualityFromName("720p")
                })
                found = true
            }
        } catch (e: Exception) { }

        // ===== سيرفر 2: السيرفر الكامل — ملف merged بجودات متعددة + ترجمات + أصوات =====
        // سلّم master مباشرة للمشغّل (ExoPlayer يفكّ كل الجودات تلقائيًا)، بدل جلبها
        // هنا — لأن جلب master الكامل قد يتجاوز المهلة (سيرفر v2 بطيء) فيفشل العرض.
        if (fullMaster.startsWith("http")) {
            callback(newExtractorLink(name, "السيرفر الكامل $ep", fullMaster, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = getQualityFromName("1080p")
            })
            found = true

            // الترجمات والأصوات من master السيرفر الكامل — نجلب master بمرونة (مهلات/أعد)
            // حتى لا نعتمد على رحلة واحدة قد تهلة (السيرفر بطيء). نستخرج tracks إن نجحنا،
            // وإن فشلنا نبقى على master المسلّم أصلًا (المشغّل قد يحلّها هو أيضًا).
            var masterText: String? = null
            for (i in 0 until 3) {
                masterText = runCatching {
                    app.get(fullMaster, referer = mainUrl, headers = mapOf("User-Agent" to UA)).text
                }.getOrNull()
                if (!masterText.isNullOrBlank()) break
                try { Thread.sleep(800L * (i + 1)) } catch (_: InterruptedException) {}
            }
            if (!masterText.isNullOrBlank() && masterText.startsWith("#EXT")) {
                for (t in extractTracks(masterText, fullMaster)) {
                    try {
                        if (t.kind == "SUBTITLES") {
                            subtitleCallback(newSubtitleFile(t.lang, t.uri))
                        } else if (t.kind == "AUDIO") {
                            callback(newExtractorLink(name, "صوت: ${t.lang}", t.uri, ExtractorLinkType.M3U8) {
                                referer = mainUrl
                                quality = getQualityFromName("720p")
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return found
    }

    private fun cleanTitle(t: String): String {
        var s = t
            .replace(Regex("""مشاهدة\s*"""), "")
            .replace(Regex("""\s*[-–—|:]\s*.*$"""), "")
            .replace(Regex("""\s*(كامل|جميع الحلقات|مسلسل|حلقات كاملة).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (s.length < 2) s = t.trim()
        return s
    }
}
