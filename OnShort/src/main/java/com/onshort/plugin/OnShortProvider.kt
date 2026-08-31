package com.onshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val ONS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// قواعد واجهات برمجة التطبيقات (نفسها للنسخة العربية)
private const val ONS_MAIN = "https://onshort.net/ar"
private const val ONS_API = "https://onshort.net/wp-json/onshort-theme/v1"
private const val ONS_PLAY_API = "https://onshort.net/wp-json/onshort-player/v1/episode"

// بطاقة مسلسل على الصفحة الرئيسية (يُحلَّل من HTML الخاص بـ listing)
private val cardRe = Regex(
    """<article class="series-card"[^>]*data-series-card="(\d+)"[^>]*>\s*<a class="series-card__link" href="([^"]+)"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"[^>]*>.*?<span class="episode-pill">(\d+)\s*<small""",
    RegexOption.DOT_MATCHES_ALL
)

// مفاتيح في صفحة التفاصيل — الموقع يستخدم بنية onshort-player:
//   data-post="{id}"  و  data-player-ticket="{ticket}"  لتشغيل الحلقة
private val detailPostRe = Regex("""data-post="(\d{3,12})"""")
private val detailTicketRe = Regex("""data-player-ticket="([^"]+)"""")

/**
 * OnShort — WordPress REST API يجمّع عدة منصات (shortmax/netshort/reelshort/goodshort…).
 * النسخة العربية فقط.
 *
 * الموقع بطيء جداً ويقيّد المعدل (صفحة التفاصيل ~115KB وتستغرق 10-17 ثانية)، لذلك:
 * - load() يبني قائمة الحلقات فوراً من بيانات مضمّنة في رابط البطاقة (؟cs=)
 *   ولا يعتمد على جلب صفحة التفاصيل، فلا تظهر أخطاء ولا يتأخر فتح التفاصيل.
 * - التذكرة (ticket) الخاصة بالتشغيل تُجلب كسولًا عند أول تشغيل وتُحفظ مؤقتًا.
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

    // تذكرة جلستنا المؤقتة لكل مسلسل (نعيد استخدامها لتشغيل عدة حلقات دون إعادة جلب)
    private class PlayCache(val ref: String, val postId: String, val ticket: String)
    @Volatile private var cached: PlayCache? = null

    private fun headers() = mapOf(
        "User-Agent" to ONS_UA,
        "Accept" to "application/json, text/plain, */*",
    )

    // الموقع بطيء/يقيّد المعدل؛ نعيد المحاولة مع توقّف قصير متزايد.
    private suspend fun getWithRetry(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = mapOf(),
        attempts: Int = 3
    ): String? {
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, referer = referer, headers = headers).text
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) {
                // 504/502/مهلة — أعد المحاولة
            }
            if (i < attempts - 1) Thread.sleep(1200L * (i + 1))
        }
        return null
    }

    // ---------- بيانات مضمّنة في رابط البطاقة (بديل جلب صفحة التفاصيل) ----------
    // النموذج: {realUrl}?cs={id}|{total}|{title}|{poster}
    private fun embedMeta(realUrl: String, id: String, total: Int, title: String, poster: String?): String {
        return "$realUrl?cs=${URLEncoder.encode(id, "UTF-8")}|${URLEncoder.encode(total.toString(), "UTF-8")}|" +
            "${URLEncoder.encode(title, "UTF-8")}|${URLEncoder.encode(poster ?: "", "UTF-8")}"
    }

    private data class Meta(val id: String, val total: Int, val title: String, val poster: String?)

    private fun parseMeta(url: String): Meta? {
        val i = url.indexOf("?cs=")
        if (i < 0) return null
        val raw = url.substring(i + 4).split("|")
        if (raw.size < 2) return null
        val id = URLDecoder.decode(raw[0], "UTF-8")
        val total = URLDecoder.decode(raw[1], "UTF-8").toIntOrNull() ?: 1
        val title = if (raw.size > 2) URLDecoder.decode(raw[2], "UTF-8") else ""
        val poster = if (raw.size > 3 && raw[3].isNotEmpty()) URLDecoder.decode(raw[3], "UTF-8") else null
        return Meta(id, total, title, poster)
    }

    private fun stripMeta(url: String): String {
        val i = url.indexOf("?cs=")
        return if (i >= 0) url.substring(0, i) else url
    }

    // ---------- الصفحة الرئيسية / العرض ----------
    private fun parseListingHtml(html: String, seen: MutableSet<String>): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (m in cardRe.findAll(html)) {
            val id = m.groupValues[1]
            val url = m.groupValues[2]
            val poster = m.groupValues[3]
            val title = m.groupValues[4]
            val total = m.groupValues[5].toIntOrNull() ?: 1
            val safeTitle = title.replace(Regex("""\s+"""), " ").trim()
            if (safeTitle.isBlank() || !seen.add(url)) continue
            // نضمّن بيانات التفاصيل في الرابط لتُبنى قائمة الحلقات فورًا دون جلب صفة التفاصيل
            val metaUrl = embedMeta(url, id, total, safeTitle, poster)
            out.add(newTvSeriesSearchResponse(safeTitle, metaUrl, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val base = "$ONS_API/listing?page=${maxOf(page, 1)}&per_page=12&lang=ar"
            val resp = getWithRetry(base, mainUrl, headers()) ?: return null
            val node = mapper.readTree(resp)
            val html = node.get("html")?.asText() ?: return null
            val items = parseListingHtml(html, java.util.HashSet())
            if (items.isEmpty()) null
            else newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    // ---------- البحث ----------
    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val base = "$ONS_API/search?q=$q&limit=48&lang=ar"
            val text = getWithRetry(base, mainUrl, headers()) ?: return null
            val node = mapper.readTree(text)
            val arr = node.get("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            val seen = java.util.HashSet<String>()
            for (it in arr) {
                val title = it.get("title")?.asText()?.takeIf { x -> x.isNotBlank() }
                    ?: it.get("base_title")?.asText() ?: continue
                val url = it.get("url")?.asText() ?: continue
                val poster = it.get("cover")?.asText()
                val id = it.get("id")?.asText() ?: continue
                val total = it.get("total")?.asInt() ?: 1
                if (!seen.add(url)) continue
                val metaUrl = embedMeta(url, id, total, title, poster)
                out.add(newTvSeriesSearchResponse(title, metaUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    if (total > 1) this.episodes = total
                })
            }
            out
        } catch (e: Exception) { null }
    }

    // ---------- التفاصيل (load) ----------
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val meta = parseMeta(url)
            // إن وُجدت بيانات مضمّنة نبني الحلقات فورًا (سريع، بلا شبكة، بلا أخطاء)
            // وفي الخلفية نجلب التذكرة (ticket) لنحفظها مؤقتًا حتى يعمل التشغيل فورًا.
            if (meta != null) {
                prefetchTicket(url)
                return buildFromMeta(meta, url)
            }

            // احتياطي: رابط بدون بيانات (مقابل روابط قديمة أو مشاركة) — نجلب صفحة التفاصيل
            val res = getWithRetry(stripMeta(url), mainUrl, headers()) ?: return null
            val postId = detailPostRe.find(res)?.groupValues?.get(1) ?: return null
            val ticket = detailTicketRe.find(res)?.groupValues?.get(1) ?: return null
            val total = Regex("""(?:total"|Episodes")\s*:\s*(\d{1,5})""")
                .find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            buildEpisodes(postId, total, ticket, url, extractTitle(res, url), extractPoster(res))
        } catch (e: Exception) { null }
    }

    private suspend fun buildFromMeta(meta: Meta, url: String): LoadResponse {
        // التذكرة تُجلب لاحقًا عند التشغيل؛ لذا نقسّم البيانات إلى (رقم الحلقة | رابط التفاصيل)
        return buildEpisodes(meta.id, meta.total, null, url, meta.title, meta.poster)
    }

    private suspend fun buildEpisodes(
        postId: String,
        total: Int,
        ticket: String?,
        url: String,
        title: String,
        poster: String?
    ): LoadResponse {
        val eps = mutableListOf<Episode>()
        // data = (رقم الحلقة | رابط التفاصيل) حتى نتمكن من جلب تذكرة جديدة عند أول تشغيل
        val detailRef = if (ticket != null) "$postId|$ticket" else stripMeta(url)
        for (n in 1..maxOf(total, 1)) {
            eps.add(newEpisode("$n|$detailRef") {
                this.episode = n
                this.name = "الحلقة $n"
            })
        }
        return newTvSeriesLoadResponse(title, stripMeta(url), TvType.TvSeries, eps) {
            this.posterUrl = poster
        }
    }

    private fun extractTitle(res: String, url: String): String {
        return Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
            .find(res)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(res)?.groupValues?.get(1)
                ?.replace(Regex("""<[^>]+>"""), "")?.trim()
            ?: url.substringAfterLast('/').substringBefore('?').replace('-', ' ').trim()
            ?: "OnShort"
    }

    private fun extractPoster(res: String): String? {
        return Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""").find(res)?.groupValues?.get(1)
    }

    // ---------- روابط التشغيل (loadLinks) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|", limit = 2)
            if (parts.size != 2) return false
            val ep = parts[0].toIntOrNull() ?: return false
            val detailRef = parts[1]

            // استخرج بيانات التشغيل (postId + ticket) — من الحفظ المؤقت أو بجلب صفحة التفاصيل
            val (postId, ticket) = resolvePlay(detailRef) ?: return false

            var node = fetchEpisode(postId, ep, ticket) ?: return false
            node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }?.let {
                cached = PlayCache(detailRef, postId, it)
            }

            if (node.has("error") || node.get("ok")?.asBoolean(false) == false) return false

            val emitted = java.util.HashSet<String>()

            val main = node.get("url")?.asText()
            if (!main.isNullOrBlank() && emitted.add(main)) {
                val q = node.get("quality")?.asText() ?: "540"
                callback(newExtractorLink(name, "${q}p", main, ExtractorLinkType.M3U8) {
                    this.quality = getQualityFromName(q)
                    this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                })
            }

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

    // نجلب التذكرة في الخلفية (Thread عادي + HttpURLConnection لأن kotlinx.coroutines غير متاحة)
    // فنحفظها مؤقتًا قبل أن يضغط المستخدم على التشغيل؛ وإن فشل هذا فلا يتأثر فتح التفاصيل.
    private fun prefetchTicket(url: String) {
        val base = stripMeta(url)
        if (cached?.ref == base) return
        Thread {
            try {
                val conn = java.net.URL(base).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ONS_UA)
                conn.setRequestProperty("Referer", mainUrl)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                conn.connectTimeout = 20000
                conn.readTimeout = 25000
                val page = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val postId = detailPostRe.find(page)?.groupValues?.get(1)
                val ticket = detailTicketRe.find(page)?.groupValues?.get(1)
                if (postId != null && ticket != null) cached = PlayCache(base, postId, ticket)
            } catch (_: Exception) {
                // فشل الجلب الخلفي — سيتعامل معه loadLinks عند الحاجة
            }
        }.start()
    }

    // يحلّ (postId، ticket) من المرجع: إما "postId|ticket" أو رابط صفحة التفاصيل.
    private suspend fun resolvePlay(detailRef: String): Pair<String, String>? {
        // الحالة 1: التذكرة محفوظة مباشرة في reference (postId|ticket) — حالة التفاصيل القديمة
        val idx = detailRef.indexOf('|')
        if (idx > 0) {
            val a = detailRef.substring(0, idx)
            val b = detailRef.substring(idx + 1)
            if (a.isNotEmpty() && a.all { it.isDigit() } && b.isNotBlank()) {
                cached = PlayCache(detailRef, a, b)
                return a to b
            }
        }
        // الحالة 2: reference = رابط صفحة التفاصيل — نستخدم المحفوظ أو نجلب صفحة لاستخراج التذكرة
        cached?.let { c -> if (c.ref == detailRef) return c.postId to c.ticket }
        val page = getWithRetry(stripMeta(detailRef), mainUrl, headers()) ?: return null
        val postId = detailPostRe.find(page)?.groupValues?.get(1) ?: return null
        val ticket = detailTicketRe.find(page)?.groupValues?.get(1) ?: return null
        cached = PlayCache(detailRef, postId, ticket)
        return postId to ticket
    }

    private suspend fun fetchEpisode(
        postId: String,
        ep: Int,
        ticket: String
    ): com.fasterxml.jackson.databind.JsonNode? {
        var current = ticket
        for (attempt in 0..1) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            val text = getWithRetry(u, mainUrl, headers() + mapOf(
                "X-ONShort-Player" to "1",
                "X-ONShort-Ticket" to current,
                "Cache-Control" to "no-cache, no-store",
                "Pragma" to "no-cache",
            ), attempts = 3) ?: return null
            val node = kotlin.runCatching { mapper.readTree(text) }.getOrNull() ?: return null
            val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
            if (newTicket != null) current = newTicket
            val ok = node.get("ok")?.asBoolean(false) ?: true
            if (!ok && newTicket != null && attempt == 0) continue
            return node
        }
        return null
    }
}
