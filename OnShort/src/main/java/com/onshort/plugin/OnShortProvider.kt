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

    // تشخيص: سجّل إلى logcat (android.util.Log متاح دائمًا على Android).
    private fun logD(msg: String) { try { android.util.Log.d("OnShort", msg) } catch (_: Exception) {} }
    private fun logE(msg: String) { try { android.util.Log.e("OnShort", msg) } catch (_: Exception) {} }

    // الواجهة الرئيسية: صف لكل منصة من المنصات التي يجمعها الموقع،
    // بحيث تظهر مسلسلات كل منصة مضمّنة في قسمها المخصص.
    // المفتاح = اسم المنصة كما يظهر في رابط /ar/platform/{slug}/.
    // كل المنصات المتوفرة في الموقع (الـ 16 slugs كلها تُرجع بطاقات من /ar/platform/{slug}/ —
    // تحقّقت أغسطس 2026). ملاحظة: حتى لو توفّرت بطاقات منصة، قد لا يُشغّلها مشغّلُ الموقع نفسُه
    // (يرجع "Provider is not handled by REST bridge" مثل NetShort/StoryReel/DramaBite/VibeShort) —
    // هذا قيدٌ من جهة السيرفر، وليس خطأً في الإضافة.
    private val platformRows = listOf(
        "shortmax" to "أحدث مسلسلات ShortMax",
        "netshort" to "أحدث مسلسلات NetShort",
        "reelshort" to "أحدث مسلسلات ReelShort",
        "dramabox" to "أحدث مسلسلات DramaBox",
        "goodshort" to "أحدث مسلسلات GoodShort",
        "idrama" to "أحدث مسلسلات iDrama",
        "dramawave" to "أحدث مسلسلات DramaWave",
        "flextv" to "أحدث مسلسلات FlexTV",
        "freereels" to "أحدث مسلسلات FreeReels",
        "dramabite" to "أحدث مسلسلات DramaBite",
        "microdrama" to "أحدث مسلسلات MicroDrama",
        "moborels" to "أحدث مسلسلات MoBorels",
        "shortswave" to "أحدث مسلسلات ShortsWave",
        "stardusttv" to "أحدث مسلسلات StardustTV",
        "storyreel" to "أحدث مسلسلات StoryReel",
        "vibeshort-goodbos" to "أحدث مسلسلات VibeShort",
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
            logD("OnShort.loadLinks data='$data'")
            val parts = data.split("|")
            if (parts.size != 2) { logD("OnShort.loadLinks bad data size=${parts.size}"); return false }
            val postId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false

            // التذكرة: إن كانت محفوظة نستخدمها، وإلا نبدأ بدون تذكرة —
            // fetchEpisode يبني التذكرة بنفسه من استجابة التشغيل (بدون صفحة ?p= البطيئة)
            // حتى لا تتجاوز مهلة loadLinks القصيرة في التطبيق → "لايوجد روابط".
            val cachedTicket = cached?.takeIf { it.postId == postId }?.ticket
            logD("OnShort.loadLinks post=$postId ep=$ep cachedTicket=${cachedTicket?.isNotBlank() == true}")

            val node = fetchEpisode(postId, ep, cachedTicket) ?: run { logD("OnShort.loadLinks fetchEpisode null -> false"); return false }

            if (node.has("error") || node.get("ok")?.asBoolean(false) == false) { logD("OnShort.loadLinks ok=false -> false"); return false }

            // الصوت (مهم): HLS الخاص بـ OnShort يستخدم صوتًا مستقلاً (independent audio) —
            // الفيديو في نسخ منفصلة والصوت في مجموعة #EXT-X-MEDIA:TYPE=AUDIO منفصلة.
            // لذلك نُسلّم master URL كاملًا للمشغّل فيُحلّ مجموعةَ الصوت تلقائيًا (صوتٌ مُختار
            // افتراضيًا + قابل للتبديل + جودة متكيّفة). لا نُخرج candidates[] إطلاقًا:
            // فبعضها (مثل microdrama / reeltv) نسخُ فيديو منفصلة (MP4 بدون مسار صوت) —
            // تشغيلُها يؤدي إلى صمتٍ تام (لا يوجد صوت). master فقط هو المضمون السليم صوتيًا.
            val main = node.get("url")?.asText()
            if (!main.isNullOrBlank()) {
                // يُفضَّل تركه متكيّفًا (لا نُثبّت جودة) حتى يختار المشغّل الجودة الأنسب
                val q = node.get("quality")?.asText() ?: "auto"
                callback(newExtractorLink(name, "${q} · Auto", main, ExtractorLinkType.M3U8) {
                    this.quality = getQualityFromName(q.takeIf { it.isNotBlank() } ?: "1080")
                    this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                })
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
                // حرج: نمنع ضغط gzip. Android's HttpURLConnection يطلب gzip تلقائيًا ويفكّه
                // في inputStream (200) لكنه لا يفكّه في errorStream (403). وخادم OnShort يُرجع
                // جسم 403 (الذي يحمل التذكرة الجديدة) مضغوطًا gzip → بدون هذا السطر يقرأ الكود
                // ثنائيات مضغوطة ويفشل تحليل JSON → fetchEpisode يرجع null → "لم يعثر على روابط".
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                conn.setRequestProperty("Pragma", "no-cache")
                for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                val code = conn.responseCode
                logD("OnShort.play http=$code (attempt=$attempt)")
                // اقرأ الجسم الخام من تدفق النجاح أو الخطأ (403/5xx) على حد سواء
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                stream?.close()
                conn.disconnect()
                // حرج: خادم OnShort يُرجع جسم 403 (الذي يحمل التذكرة الجديدة) مضغوطًا gzip.
                // Android قد لا يفكّ errorStream تلقائيًا، فنفكّه يدويًا إن بدأ بالـ magic (1f 8b).
                val raw = if (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b) {
                    try {
                        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val node = try { mapper.readTree(raw) } catch (e: Exception) {
                    logE("OnShort.rawGetJson parse failed: ${e.message} | body=${raw.take(80)}")
                    null
                }
                if (node != null) { logD("OnShort.rawGetJson ok, has_ticket=${node["ticket"]?.isTextual == true}"); return node }
            } catch (e: Exception) {
                // 502/504/مهلة — أعد المحاولة
                logE("OnShort.rawGetJson exception (attempt=$attempt): ${e.message}")
            }
            attempt++
            if (attempt < 3) try { Thread.sleep(900L * attempt) } catch (_: InterruptedException) {}
        }
        logE("OnShort.rawGetJson returning null after retries")
        return null
    }

    private suspend fun fetchEpisode(
        postId: String,
        ep: Int,
        ticket: String?
    ): JsonNode? {
        var current = ticket
        for (attempt in 0..3) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            // إن لم تكن لدينا تذكرة نرسل بدونها: الخادم يرد 403 مع تذكرة صالحة في الجسم
            // نبدأ منها (boootstrap سريع — لا حاجة لصفحة ?p= البطيئة التي كانت تسبب المهلة).
            val hdrs = mutableMapOf("X-ONShort-Player" to "1") // مطلوب بشدة
            if (!current.isNullOrBlank()) hdrs["X-ONShort-Ticket"] = current
            val node = rawGetJson(u, mainUrl, hdrs) ?: run {
                logD("OnShort.fetchEpisode null at attempt=$attempt")
                return null
            }
            val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
            if (newTicket != null) current = newTicket
            val ok = node.get("ok")?.asBoolean(false) ?: true
            logD("OnShort.fetchEpisode attempt=$attempt ok=$ok newTicket=${newTicket != null} url=${node.get("url")?.asText().orEmpty().take(60)}")
            if (!ok) {
                // تذكرة غير صالحة/منتهية: أعد المحاولة فورًا بالتذكرة الجديدة من الجسم
                if (newTicket != null && attempt < 3) continue
                return null
            }
            // نجاح: احفظ التذكرة الجديدة لبقية الحلقات (بلا جلب صفحة)
            if (newTicket != null) cached = PlayCache(postId, newTicket)
            return node
        }
        return null
    }
}
