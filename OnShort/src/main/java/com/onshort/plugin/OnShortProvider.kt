package com.onshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val ONS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// قواعد واجهات برمجة التطبيقات (نفسها للنسخة العربية)
private const val ONS_MAIN = "https://onshort.net/ar"
private const val ONS_API = "https://onshort.net/wp-json/onshort-theme/v1"
private const val ONS_PLAY_API = "https://onshort.net/wp-json/onshort-player/v1/episode"

// بطاقة مسلسل على الصفحة الرئيسية / البحث (يُحلَّل من HTML الخاص بـ listing)
private val cardRe = Regex(
    """<article class="series-card"[^>]*data-series-card="(\d+)"[^>]*>\s*<a class="series-card__link" href="([^"]+)"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"[^>]*>.*?<span class="episode-pill">(\d+)\s*<small""",
    RegexOption.DOT_MATCHES_ALL
)

// مفاتيح في صفحة التفاصيل — الموقع يستخدم بنية onshort-player:
//   data-post="{id}"  و  data-player-ticket="{ticket}"  لتشغيل الحلقة
private val detailPostRe = Regex("""data-post="(\d{3,12})"""")
private val detailTicketRe = Regex("""data-player-ticket="([^"]+)"""")
private val ogTitleRe = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
private val ogImageRe = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
private val ogDescRe = Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""")

/**
 * OnShort — WordPress REST API يجمّع عدة منصات (shortmax/netshort/reelshort/goodshort…).
 * النسخة العربية فقط.
 * التشغيل: يحمّل صفحة المسلسل -> يستخرج post id و player-ticket ->
 * يستدعي GET /wp-json/onshort-player/v1/episode?post={id}&episode={n}
 * برأسَي X-ONShort-Player و X-ONShort-Ticket ويعيد m3u8 (مقاطع TS نظيفة).
 */
class OnShortProvider : MainAPI() {
    override var name = "OnShort (عربي)"
    override var mainUrl = ONS_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "latest" to "أحدث المسلسلات",
    )

    private fun headers() = mapOf(
        "User-Agent" to ONS_UA,
        "Accept" to "application/json, text/plain, */*",
    )

    // ---------- الصفحة الرئيسية / العرض ----------
    private fun parseListingHtml(html: String, seen: MutableSet<String>): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (m in cardRe.findAll(html)) {
            val id = m.groupValues[1]
            val url = m.groupValues[2]
            val poster = m.groupValues[3]
            val title = m.groupValues[4]
            // العناوين قد تكون مفرّغة آمنة؛ ونحتاج عنواناً غير فارغ
            val safeTitle = title.replace(Regex("""\s+"""), " ").trim()
            if (safeTitle.isBlank() || !seen.add(url)) continue
            out.add(newTvSeriesSearchResponse(safeTitle, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // الصفحة الأولى تبدأ من 1 عند OnShort (مثل ما يفعله الموقع)
            val base = "$ONS_API/listing?page=$page&per_page=12&lang=ar"
            val resp = app.get(base, referer = mainUrl, headers = headers()).text
            // seen محلي لكل استدعاء حتى تظهر الصفحة في كل مرة يُعاد فتحها
            val items = parseListingHtml(resp, java.util.HashSet())
            if (items.isEmpty()) null
            else newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    // ---------- البحث ----------
    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val base = "$ONS_API/search?q=$q&limit=48&lang=ar"
            val text = app.get(base, referer = mainUrl, headers = headers()).text
            // الاستجابة JSON: {"results":[{id,title,base_title,url,cover,total,lang,format,platform}],...}
            val node = mapper.readTree(text)
            val arr = node.get("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            val seen = java.util.HashSet<String>()
            for (it in arr) {
                val title = it.get("title")?.asText()?.takeIf { x -> x.isNotBlank() }
                    ?: it.get("base_title")?.asText() ?: continue
                val url = it.get("url")?.asText() ?: continue
                val poster = it.get("cover")?.asText()
                val total = it.get("total")?.asInt() ?: 0
                if (!seen.add(url)) continue
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    val t = total
                    if (t > 1) this.episodes = t
                })
            }
            out
        } catch (e: Exception) { null }
    }

    // ---------- التفاصيل (load) ----------
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, referer = mainUrl, headers = headers()).text

            // معرّف المنشور وتذكرة الجلسة لاسترجاع الحلقة لاحقاً
            val postId = detailPostRe.find(res)?.groupValues?.get(1) ?: return null
            val ticket = detailTicketRe.find(res)?.groupValues?.get(1) ?: return null

            // عدد الحلقات
            val total = Regex("""(?:total"|Episodes")\s*:\s*(\d{1,5})""")
                .find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // العنوان: og:title ثم H1 احتياطياً
            val title = ogTitleRe.find(res)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(res)?.groupValues?.get(1)
                    ?.replace(Regex("""<[^>]+>"""), "")?.trim()
                ?: url.substringAfterLast('/').substringBefore('?').replace('-', ' ').trim()
                ?: "OnShort"

            val poster = ogImageRe.find(res)?.groupValues?.get(1)
            val plot = ogDescRe.find(res)?.groupValues?.get(1)

            val eps = mutableListOf<Episode>()
            // كل حلقة نمرر (postId | رقم الحلقة | ticket) لاسترجاع الرابط لاحقاً
            for (n in 1..total) {
                eps.add(newEpisode("$postId|$n|$ticket") {
                    this.episode = n
                    this.name = "الحلقة $n"
                })
            }
            if (eps.isEmpty()) return null
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    // ---------- روابط التشغيل (loadLinks) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            if (parts.size != 3) return false
            val postId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false
            var ticket = parts[2]
            if (ticket.isBlank()) return false

            var node = fetchEpisode(postId, ep, ticket) ?: return false
            // إن عادت الاستجابة بتذكرة جديدة نحدّثها لاستخدامها في إعادة المحاولة
            node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }?.let { ticket = it }

            if (node.has("error") || node.get("ok")?.asBoolean(false) == false) return false

            val emitted = java.util.HashSet<String>()

            // المصدر الأساسي (m3u8 نظيف بكل الحالات المعروفة)
            val main = node.get("url")?.asText()
            if (!main.isNullOrBlank() && emitted.add(main)) {
                val q = node.get("quality")?.asText() ?: "540p"
                callback(newExtractorLink(name, "${q}p", main, ExtractorLinkType.M3U8) {
                    this.quality = getQualityFromName(q)
                    this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                })
            }

            // المرشّحات الأخرى (candidates) كلها روابط hls
            val candidates = node.get("candidates")
            if (candidates != null && candidates.isArray) {
                for (c in candidates) {
                    val cu = c.get("url")?.asText() ?: continue
                    if (cu.isBlank() || !emitted.add(cu)) continue
                    val cq = (c.get("quality")?.asText() ?: "540") + "p"
                    callback(newExtractorLink(name, cq, cu, ExtractorLinkType.M3U8) {
                        this.quality = getQualityFromName(cq)
                        this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                    })
                }
            }

            // الترجمات (لكل حلقة)
            val subs = node.get("subtitles")
            if (subs != null && subs.isArray) {
                for (s in subs) {
                    val su = s.get("url")?.asText() ?: continue
                    if (su.isBlank()) continue
                    val lang = s.get("lang")?.asText() ?: s.get("label")?.asText() ?: "ar"
                    try { subtitleCallback(newSubtitleFile(lang, su)) } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) { false }
    }

    // استرجاع الحلقة من واجهة onshort-player الجديدة، مع إعادة محاولة عند فشل
    // المصادقة باستخدام التذكرة الجديدة التي تعيدها الاستجابة (نفس سلوك مشغّل الموقع).
    private suspend fun fetchEpisode(
        postId: String,
        ep: Int,
        ticket: String
    ): com.fasterxml.jackson.databind.JsonNode? {
        var current = ticket
        for (attempt in 0..1) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            val resp = kotlin.runCatching {
                app.get(u, referer = mainUrl, headers = headers() + mapOf(
                    "X-ONShort-Player" to "1",
                    "X-ONShort-Ticket" to current,
                    "Cache-Control" to "no-cache, no-store",
                    "Pragma" to "no-cache",
                ))
            }.getOrNull() ?: return null
            val node = kotlin.runCatching { mapper.readTree(resp.text) }.getOrNull() ?: return null
            val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
            if (newTicket != null) current = newTicket
            val ok = node.get("ok")?.asBoolean(false) ?: true
            // فشلت المصادقة (ok=false) لكن توجد تذكرة جديدة — نعيد المحاولة بها مرة واحدة
            if (!ok && newTicket != null && attempt == 0) continue
            return node
        }
        return null
    }
}
