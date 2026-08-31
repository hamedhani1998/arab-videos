package com.onshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
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

// قواعد واجهات برمجة التطبيقات
private const val ONS_MAIN = "https://onshort.net/ar"
private const val ONS_API = "https://onshort.net/wp-json/onshort-theme/v1"
private const val ONS_PLAY_API = "https://onshort.net/wp-json/onshort-player/v1/episode"

// بطاقة مسلسل على الصفحة الرئيسية (يُحلَّل من HTML الخاص بـ listing)
private val cardRe = Regex(
    """<article class="series-card"[^>]*data-series-card="(\d+)"[^>]*>\s*<a class="series-card__link" href="([^"]+)"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"[^>]*>.*?<span class="episode-pill">(\d+)\s*<small""",
    RegexOption.DOT_MATCHES_ALL
)

// مفاتيح التذكرة في صفحة التفاصيل (أو في page?p={id})
private val detailPostRe = Regex("""data-post="(\d{3,12})"""")
private val detailTicketRe = Regex("""data-player-ticket="([^"]+)"""")

/**
 * OnShort — WordPress REST API يجمّع عدة منصات (shortmax/netshort/reelshort/goodshort…).
 * النسخة العربية فقط.
 *
 * الموقع بطيء ويقيّد المعدل بشدة، لذا:
 * - load(): يبني قائمة الحلقات فورًا من بيانات مضمّنة في رابط البطاقة (؟cs=)
 *   ولا يعتمد على جلب صفحة التفاصيل (بلا شبكة وبلا أخطاء).
 * - data الحلقة = "postId|رقم" (صيغة قياسية، بلا روابط طويلة تتلف في التطبيق).
 * - التذكرة تُجلب في الخلفية أثناء load() وتُحفظ، وتُستعاد عند التشغيل.
 */
class OnShortProvider : MainAPI() {
    override var name = "OnShort (عربي)"
    override var mainUrl = ONS_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // الواجهة الرئيسية: صف لكل منصة من المنصات التي يجمعها الموقع،
    // بحيث تظهر مسلسلات كل منصة مضمّنة في قسمها المخصص.
    // المفتاح = اسم المنصة كما يظهر في رابط /ar/platform/{slug}/.
    // كل المنصات المتوفرة في الموقع (تحقّقت كلها تُرجع بطاقات — أغسطس 2026):
    // shortmax/netshort/reelshort/dramawave/idrama/goodshort/dramabox/flextv/freereels
    private val platformRows = listOf(
        "shortmax" to "أحدث مسلسلات ShortMax",
        "netshort" to "أحدث مسلسلات NetShort",
        "reelshort" to "أحدث مسلسلات ReelShort",
        "dramawave" to "أحدث مسلسلات DramaWave",
        "idrama" to "أحدث مسلسلات iDrama",
        "goodshort" to "أحدث مسلسلات GoodShort",
        "dramabox" to "أحدث مسلسلات DramaBox",
        "flextv" to "أحدث مسلسلات FlexTV",
        "freereels" to "أحدث مسلسلات FreeReels",
    )

    override val mainPage = mainPageOf(
        *platformRows.map { (slug, label) -> slug to label }.toTypedArray()
    )

    // تذكرة جلستنا المؤقتة لكل مسلسل (مفتاح = postId)
    private class PlayCache(val postId: String, val ticket: String)
    @Volatile private var cached: PlayCache? = null
    // خيط الجلب الخلفي الحالي — حتى ينتظر loadLinks عليه بدلًا من إعادة جلب الصفحة البطيئة
    @Volatile private var prefetchThread: Thread? = null

    private fun headers() = mapOf(
        "User-Agent" to ONS_UA,
        "Accept" to "application/json, text/plain, */*",
    )

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

    // ---------- بيانات مضمّنة في رابط البطاقة (لجعل load() فوريًا) ----------
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

    // رابط صفحة التفاصيل من postId عبر ?p= (ووردبريس) — استخدم النطاق الجذر وليس /ar/
    // لأن ?p= في /ar/ لا يُرجع التذكرة بينما الجذر يرجعها (تحقّقت).
    private fun detailUrl(postId: String): String = "https://onshort.net/?p=$postId"

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
            val metaUrl = embedMeta(url, id, total, safeTitle, poster)
            out.add(newTvSeriesSearchResponse(safeTitle, metaUrl, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // request.data = مفتاح الصف = slug المنصة (shortmax/netshort/…)
            val slug = request.data
            val p = maxOf(page, 1)
            val url = if (p == 1) "$ONS_MAIN/platform/$slug/"
                else "$ONS_MAIN/platform/$slug/page/$p/"
            val resp = getWithRetry(url, mainUrl, headers()) ?: return null
            val items = parseListingHtml(resp, java.util.HashSet())
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
            if (meta != null) {
                // نبني الحلقات فورًا من البيانات المضمّنة، ونجلب التذكرة في الخلفية للتشغيل
                prefetchTicket(meta.id)
                return buildEpisodes(meta.id, meta.total, meta.title, meta.poster, stripMeta(url))
            }

            // احتياطي: رابط بدون بيانات (روابط قديمة/مشاركة) — نجلب صفحة التفاصيل
            val res = getWithRetry(stripMeta(url), mainUrl, headers()) ?: return null
            val postId = detailPostRe.find(res)?.groupValues?.get(1) ?: return null
            val ticket = detailTicketRe.find(res)?.groupValues?.get(1)
            if (ticket != null) cached = PlayCache(postId, ticket)
            val total = Regex("""(?:total"|Episodes")\s*:\s*(\d{1,5})""")
                .find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            buildEpisodes(postId, total, extractTitle(res, stripMeta(url)), extractPoster(res), stripMeta(url))
        } catch (e: Exception) { null }
    }

    // data الحلقة = "postId|رقم" (صيغة قياسية لا تتلف في التطبيق)
    private suspend fun buildEpisodes(
        postId: String,
        total: Int,
        title: String,
        poster: String?,
        url: String
    ): LoadResponse {
        val eps = mutableListOf<Episode>()
        for (n in 1..maxOf(total, 1)) {
            eps.add(newEpisode("$postId|$n") {
                this.episode = n
                this.name = "الحلقة $n"
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
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

    // نجلب التذكرة في الخلفية (Thread + HttpURLConnection; kotlinx غير متاح) لنحفظها مؤقتًا.
    // التذكرة خاصة بكل مسلسل وتأتي من الصفحة البطيئة ?p={postId} (~10s وتحت التقييد أبطأ).
    // التشغيل يحدث داخل نافذة زمنية قصيرة في التطبيق، لذا نعيد المحاولة في الخلفية حتى نضمن
    // وصولها قبل أن يضغط المستخدم على تشغيل (بدلًا من انتظارها في loadLinks وتجاوز المهلة → "لايوجد روابط").
    private fun prefetchTicket(postId: String) {
        if (cached?.postId == postId) return
        // إن كان جلب لهذا المسلسل قيد التنفيذ فلا نبدأ جلبًا آخر
        val running = prefetchThread
        if (running != null && running.isAlive) return
        val deadline = System.currentTimeMillis() + 30000
        val th = Thread {
            var attempt = 0
            while (cached?.postId != postId && System.currentTimeMillis() < deadline) {
                attempt++
                try {
                    val conn = java.net.URL(detailUrl(postId)).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", ONS_UA)
                    conn.setRequestProperty("Referer", mainUrl)
                    conn.connectTimeout = 15000
                    conn.readTimeout = 18000
                    val page = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val ticket = detailTicketRe.find(page)?.groupValues?.get(1)
                    if (ticket != null) cached = PlayCache(postId, ticket)
                    else break // الصفحة لم تتضمن تذكرة — لا جدوى من الإعادة
                } catch (_: Exception) {
                    // 502/504/مهلة — أعد المحاولة بفاصل قصير
                }
                if (cached?.postId != postId && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(1200L) } catch (_: InterruptedException) {}
                }
            }
        }
        prefetchThread = th
        th.start()
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
            if (parts.size != 2) return false
            val postId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false

            // التذكرة: من الحفظ المؤقت أو بجلب صفحة التفاصيل (?p={postId})
            val ticket = cached?.takeIf { it.postId == postId }?.ticket
                ?: fetchTicket(postId) ?: return false

            var node = fetchEpisode(postId, ep, ticket) ?: return false
            node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }?.let {
                cached = PlayCache(postId, it)
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

    // يجلب التذكرة من صفحة التفاصيل عبر ?p={postId}.
    // لو كان الجلب الخلفي قيد التنفيذ ننتظره حتى ثوانٍ (بدلًا من جلب صفحة بطيئة ثانية
    // قد تتجاوز مهلة loadLinks القصيرة في التطبيق → "لايوجد روابط" عند الضغط السريع).
    private fun fetchTicket(postId: String): String? {
        val waitUntil = System.currentTimeMillis() + 6000
        val running = prefetchThread
        if (running != null && running.isAlive) {
            while (running.isAlive && System.currentTimeMillis() < waitUntil) {
                cached?.let { if (it.postId == postId) return it.ticket }
                try { Thread.sleep(150L) } catch (_: InterruptedException) { break }
            }
            cached?.let { if (it.postId == postId) return it.ticket }
        }
        // الجلب الخلفي لم يُثمر — جلبه المباشر بمانع اتصال أصلي يحتمل 502/504 ويهضم الصفحة
        var attempt = 0
        while (attempt < 3) {
            try {
                val conn = java.net.URL(detailUrl(postId)).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ONS_UA)
                conn.setRequestProperty("Referer", mainUrl)
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val page = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                stream?.close()
                conn.disconnect()
                val ticket = detailTicketRe.find(page)?.groupValues?.get(1)
                if (ticket != null) {
                    cached = PlayCache(postId, ticket)
                    return ticket
                }
            } catch (_: Exception) {
                // 502/504/مهلة — أعد المحاولة
            }
            attempt++
            if (attempt < 3) try { Thread.sleep(700L * attempt) } catch (_: InterruptedException) {}
        }
        return null
    }

    // يقرأ جسم استجابة من شبكة أصلية ويعيده كـ JsonNode حتى لو كان HTTP 403
    // (التذكرة المنتهية تُرجع 403 مع جسم {ok:false,retryable:true,ticket:يديدة} —
    // يجب قراءة هذا الجسم لاستخراج التذكرة الجديدة بدلًا من اعتبارها فشلًا).
    private fun rawGetJson(url: String, referer: String, extraHeaders: Map<String, String>): JsonNode? {
        var attempt = 0
        while (attempt < 3) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ONS_UA)
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                conn.setRequestProperty("Pragma", "no-cache")
                for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                val code = conn.responseCode
                // اقرأ الجسم من تدفق النجاح أو تدفق الخطأ (403/5xx) على حد سواء
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                stream?.close()
                conn.disconnect()
                val node = mapper.readTree(body)
                if (node != null) return node
            } catch (_: Exception) {
                // 502/504/مهلة — أعد المحاولة
            }
            attempt++
            if (attempt < 3) try { Thread.sleep(900L * attempt) } catch (_: InterruptedException) {}
        }
        return null
    }

    private suspend fun fetchEpisode(
        postId: String,
        ep: Int,
        ticket: String
    ): JsonNode? {
        var current = ticket
        for (attempt in 0..2) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            val node = rawGetJson(u, mainUrl, mapOf(
                "X-ONShort-Ticket" to current,
                // مطلوب بشدة: بدونه يعيد الخادم 403/ok:false حتى مع تذكرة صالحة
                "X-ONShort-Player" to "1",
            )) ?: return null
            val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
            if (newTicket != null) current = newTicket
            val ok = node.get("ok")?.asBoolean(false) ?: true
            // تذكرة منتهية / ok=false: أعد المحاولة فورًا بالتذكرة الجديدة
            // التي أرجعها الخادم (من جسم 403 أو من ok=false) — لا حاجة لجلب الصفحة.
            if (!ok && newTicket != null && attempt < 2) continue
            return node
        }
        return null
    }
}
