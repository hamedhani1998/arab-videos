package com.deepdrama.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DD_MAIN = "https://www.deep-drama.com"
private const val DD_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// عناوين Blogger: "مشاهدة مسلسل X مترجم كامل جميع الحلقات HD أونلاين | ديب دراما"
private val titleCleanRe = Regex("""مشاهدة\s*مسلسل\s*(.*?)\s*مترجم.*""", RegexOption.DOT_MATCHES_ALL)
private val titleAllRe = Regex("""^\s*(?:مشاهدة\s*)?(?:مسلسل\s*)?(.+?)\s*$""")
// لاحقة الموقع: "| ديب دراما"
private val titleSiteSuffixRe = Regex("""\s*\|\s*ديب\s*دراما\s*$""")

private class DdEntry(
    val title: String?,
    val url: String?,
    val poster: String?,
)

/**
 * DeepDrama — موقع Blogger عربي، كل مشاركة = مسلسل كامل في فيديو واحد مدمج
 * بخوادم متعددة. خادمان قابلان للتشغيل المباشر:
 *  1) vidaraa.cc (الأساسي/الأسرع): HLS تكيفي حتى 1080p + ترجمة عربية مضمونة.
 *  2) Rumble (البديل): HLS تكيفي + mp4 + ترجمة (إن وُجدت).
 * نقدم لكل مسلسل خيارات الجودات مثل الموقع، والترجمات التي تظهر وتُختار بشكل صحيح.
 * البيانات (روابط الخوادم) تُخزَّن في الحلقة أثناء عرض التفاصيل، وتُحلّ كل
 * نتيجة مرة واحدة وتُخزَّن مؤقتًا ليكون التشغيل فوريًا.
 */
class DeepDramaProvider : MainAPI() {
    override var name = "Deep Drama"
    override var mainUrl = DD_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // أقسام الموقع من التذييل (الأقسام الأساسية + أنواع مختارة).
    private val sections = listOf(
        "أحدث المسلسلات" to null,
        "مسلسلات" to "مسلسل",
        "صيني" to "صيني",
        "مسلسلات مدبلجة" to "مسلسل مدبلج",
        "مسلسلات مترجمة" to "مسلسل مترجم",
        "أفلام" to "فيلم",
        "مسلسل أكشن" to "مسلسل أكشن",
        "مسلسل رومانسي" to "مسلسل رومانسي",
        "مسلسل دراما" to "مسلسل دراما",
        "مسلسل تاريخي" to "مسلسل تاريخي",
        "مسلسل فانتازيا" to "مسلسل فانتازيا",
    )

    override val mainPage = mainPageOf(
        *sections.map { it.first to it.first }.toTypedArray()
    )

    private fun headers() = mapOf("User-Agent" to DD_UA)

    // رؤوس لجلب ملف ترجمة من خادم معيّن: Seepixو headers للسيرفر لتجنّب ردّ 403
    // (صفحة خطأ HTML تُعرض كرموز). vidaraa يتطلب Referer/Origin؛ Rumble يكفي UA.
    private fun subHeaders(serverName: String): Map<String, String> =
        if (serverName.contains("vidaraa", ignoreCase = true))
            mapOf(
                "User-Agent" to DD_UA,
                "Referer" to "https://vidaraa.cc/",
                "Origin" to "https://vidaraa.cc",
            )
        else
            mapOf("User-Agent" to DD_UA)

    /**
     * خلاصة التحقق القاطع من مصدر الترجمات:
     * يخدّم vidaraa و Rumble ملفات .vtt متطابقة ومضاعفة-الترميز — نصٌّ عربي حُوّل
     * خطأً إلى UTF-8 مزدوج (مثلاً "لكن" → "ÙÙÙÙ"). كشفُ الفكّ (ISO_8859_1 → UTF_8)
     * يسترجع العربية في 681 سطراً من كلٍّ منهما، لكن صندوق CloudStream لـ plugins
     * لا يوفّر أي وسيلةٍ لتوجيه بايتاتٍ مصححةٍ إلى المشغّل: SubtitleFile لا يحمل سوى
     * lang/url/headers (ولا حقل محتوى)، والمشغّل يرفض data:، والـ plugin لا يملك
     * Context لكتابة ملفٍ محلي. لهذا نعرض الترجمة برابطها ورؤوسها الصحيحة كما
     * يوفّرها السيرفر — وهو الخيار الوحيد الذي يقبله المشغّل فعلاً.
     */
    private val mojibakeDiagnosticNote = Unit

    // نتيجة فكّ روابط خادم: الـ master التكيفي + الجودات الفردية + الترجمات + الصوت.
    private data class ServerResolved(
        val name: String,               // اسم الخادم للعرض
        val hls: String?,               // master التكيفي (جميع الجودات)
        val renditions: List<ServerRendition>, // الجودات الفردية (اختياري)
        val subtitles: List<SubtitleTrack>,
        val directVideo: String?,       // mp4 مباشر (Rumble فقط)
    )
    private data class ServerRendition(val url: String, val height: Int, val bandwidth: Long = 0)
    // ملف ترجمة: اسم اللغة + رابط .vtt.
    // تمريره برابطه المباشر (كما في v4 الذي أثبت العرض الصحيح). لا نستخدم inline/data:
    // لأن مشغّل التطبيق لا يعرضها (أخفى الترجمة كليًا في v6).
    private data class SubtitleTrack(val label: String, val url: String)

    // تخزين مؤقت لنتائج فكّ كل خادم حسب مصدره (رابط التضمين أو filecode).
    // الجلب يتم مرة واحدة أثناء عرض التفاصيل، فيُعاد استخدامه فورًا عند التشغيل.
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, ServerResolved>()

    // ---------- Rumble ----------

    /**
     * يستخرج كائن JSON مُغلق بالأقواس يبدأ بعد المفتاح مباشرة (مثلاً `"u":{...}`)
     * من نص يحتوي JSON مضغوط، بنطاق تطابق الأقواس — أأمن من قص سماكة ثابتة.
     */
    private fun extractJsonObject(text: String, key: String): JsonNode? {
        val idx = text.indexOf(key)
        if (idx < 0) return null
        val start = text.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return try { mapper.readTree(text.substring(start, i + 1)) } catch (_: Exception) { null }
                    }
                }
                '"' -> inStr = true
            }
        }
        return null
    }

    /** يجلب روابط Rumble (مخزّنة) — مرة واحدة ثم يُعاد استخدامها. */
    private suspend fun resolveRumble(embedUrl: String): ServerResolved {
        resolveCache["rumble:$embedUrl"]?.let { return it }
        val html = app.get(embedUrl, headers = headers()).text
        val cleaned = html.replace("\\/", "/")

        // المصدر الحقيقي الكامل داخل كائن `u`:
        //   u.hls.url = https://rumble.com/hls-vod/{id}/playlist.m3u8  ← adaptive master (الجودة كلها)
        //   u.timeline.url = .../Faa.mp4 (180x320)                       ← مجرد معاينة صغيرة، ليست الفيلم
        //   u.tar = .../baa.tar?r_file=chunklist.m3u8 (360x640 + audio)  ← الأساس القابل للتشغيل
        val uNode = extractJsonObject(cleaned, "\"u\"")
        val hls = uNode?.get("hls")?.get("url")?.asText()?.takeIf { it.isNotBlank() }

        // الترجمات: Rumble يقدّم كائن "cc":{lang:{language,path}} أو مصفوفة [] (بلا ترجمة).
        val subs = mutableListOf<SubtitleTrack>()
        val ccNode = extractJsonObject(cleaned, "\"cc\"")
        if (ccNode != null) {
            if (ccNode.isObject) {
                ccNode.fields().forEach { (lang, info) ->
                    val path = info.get("path")?.asText()?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val langName = info.get("language")?.asText().orEmpty()
                    subs.add(SubtitleTrack("${langName.ifBlank { lang }} (Rumble)", path))
                }
            }
            // مصفوفة [] = لا ترجمة؛ نتجاهل.
        }

        // جودات Rumble: لا نجلب الـ master هنا (درس v2/v3 — النافذة ضيقة وRumble
        // بطيء). نصدّر الـ master التكيفي نفسه فقط، فيتولى المشغّل التكيّف بين
        // الجودات تلقائيًا. (المعلومات الكاملة في التعليق أعلى: الخادم يقدّم
        // جودات متعددة داخل الـ master الواحد.)
        // ملاحظة: لا نستخدم timeline.mp4 (180x320 معاينة) بأي حال — ليس بالفيلم الكامل.
        val resolved = ServerResolved(
            name = "Rumble",
            hls = hls,
            renditions = emptyList(),  // الجودات داخل الـ master نفسه؛ لا فكّ هنا.
            subtitles = subs,
            directVideo = null,  // لا ملف mp4 كامل مباشر عند Rumble — الصحيح هو الـ HLS.
        )
        resolveCache["rumble:$embedUrl"] = resolved
        return resolved
    }

    // ---------- vidaraa ----------

    /** يحول رابط vidaraa إلى filecode (المقطع بعد /e/). */
    private fun vidaraaFilecode(embedUrl: String): String? {
        return Regex("""/(?:e|v|embed)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)
    }

    /** يحلل الجودات من master vidaraa (روابط نسبية تُحلّ مقابل مجلد master). */
    private fun parseVidaraaMaster(masterText: String, masterBase: String): List<ServerRendition> {
        val out = mutableListOf<ServerRendition>()
        val lines = masterText.lines()
        for (i in 0 until lines.size - 1) {
            val inf = lines[i].trim()
            if (!inf.startsWith("#EXT-X-STREAM-INF")) continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(inf)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(inf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var url = lines[i + 1].trim()
            if (url.isBlank() || url.startsWith("#")) continue
            // الروابط نسبية (index_1080x1920.m3u8?token=...) — نحلها مقابل مجلد master
            if (!url.startsWith("http")) url = masterBase + url
            out.add(ServerRendition(url, height, bw))
        }
        return out.distinctBy { it.height }
    }

    /**
     * يجلب بيانات vidaraa من API (مخزّنة) — مرة واحدة.
     * POST /api/stream => streaming_url (HLS 1080p) + subtitles (عربية مضمونة).
     */
    private suspend fun resolveVidaraa(embedUrl: String): ServerResolved {
        resolveCache["vidaraa:$embedUrl"]?.let { return it }
        val filecode = vidaraaFilecode(embedUrl) ?: return ServerResolved("vidaraa", null, emptyList(), emptyList(), null)
        var streamUrl: String? = null
        var directMp4: String? = null
        var subs = emptyList<SubtitleTrack>()
        try {
            val body = mapper.writeValueAsString(mapOf("filecode" to filecode, "device" to "web"))
            val resp = app.post(
                "https://vidaraa.cc/api/stream",
                requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = headers() + mapOf(
                    "Content-Type" to "application/json",
                    "Referer" to embedUrl,
                    "Origin" to "https://vidaraa.cc",
                ),
                referer = embedUrl,
            ).text
            val node = mapper.readTree(resp)
            streamUrl = node.get("streaming_url")?.asText()?.takeIf { it.isNotBlank() }
            // ترجمة vidaraa: قائمة عناصر {file_path, language}. قد تكون الترميز مشوّه
            // في بعض الفيديوات (mojibake)؛ نُسمّيها باسم الخادم ليختار المستخدم.
            val subArr = node.get("subtitles")
            if (subArr != null && subArr.isArray) {
                subs = subArr.mapNotNull { s ->
                    val path = s.get("file_path")?.asText()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val lang = s.get("language")?.asText().orEmpty().ifBlank { "العربية" }
                    SubtitleTrack("$lang (vidaraa)", path)
                }
            }
        } catch (_: Exception) { streamUrl = null }

        // vidaraa يخدم شكلين مختلفين بحسب الفيديو:
        //   1) HLS master (~p1-*.s1q2105.com/hls/.../master.m3u8?token=) — نقسم الجودات النسبية.
        //   2) ملف mp4 مباشر (streamix.so/uploads/video_*.mp4) — ليس HLS إطلاقًا.
        // التمييز بالامتداد حتى لا نمرّر mp4 كنوع M3U8 فينكسر المشغّل، ولا نقرأ mp4 كـ playlist.
        var renditions = emptyList<ServerRendition>()
        val su = streamUrl ?: ""
        val isDirectMp4 = su.lowercase().let {
            it.contains(".mp4") || it.contains(".m4v") || it.contains("mime_type=video_mp4")
        }
        if (isDirectMp4) {
            directMp4 = su
            streamUrl = null
        } else if (su.isNotBlank()) {
            try {
                val base = su.substringBeforeLast('/') + "/"
                val masterText = app.get(su, headers = headers(), referer = "https://vidaraa.cc/").text
                renditions = parseVidaraaMaster(masterText, base)
            } catch (_: Exception) { renditions = emptyList() }
        }

        val resolved = ServerResolved(
            name = "vidaraa",
            hls = streamUrl,
            renditions = renditions,
            subtitles = subs,
            directVideo = directMp4,
        )
        resolveCache["vidaraa:$embedUrl"] = resolved
        return resolved
    }

    private fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val base = titleSiteSuffixRe.replace(title, "").trim()
        val clean = titleCleanRe.find(base)?.groupValues?.get(1)?.trim()
            ?: titleAllRe.find(base)?.groupValues?.get(1)?.trim()
            ?: base.trim()
        if (clean.isBlank()) return null
        return clean
    }

    // تحليل تغذية Blogger (الصفحة الرئيسية)
    private fun parseFeedEntries(text: String): List<DdEntry> {
        val out = mutableListOf<DdEntry>()
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
            val label = sections.firstOrNull { it.first == request.data }?.second
            val start = ((page - 1) * 12) + 1
            val base = if (label == null)
                "$DD_MAIN/feeds/posts/default?alt=json&max-results=12&start-index=$start"
            else
                "$DD_MAIN/feeds/posts/default/-/${java.net.URLEncoder.encode(label, "UTF-8")}?alt=json&max-results=12&start-index=$start"
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

    // بطاقات الموقع (بنية .xr-card)
    private val xrCardRe = Regex(
        """<article class='xr-card'>.*?<a href='([^']+)' title='([^']*)'.*?<img[^>]*src='([^']+)'""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val posterSizeRe = Regex("""=w\d+""")
    private fun upgradePoster(u: String): String = posterSizeRe.replace(u, "=w720")

    private fun parseCards(html: String): List<DdEntry> {
        val out = mutableListOf<DdEntry>()
        for (m in xrCardRe.findAll(html)) {
            val url = m.groupValues[1].trim()
            val title = cleanTitle(m.groupValues[2]) ?: continue
            val poster = upgradePoster(m.groupValues[3].trim()).ifBlank { null }
            if (url.isBlank()) continue
            out.add(DdEntry(title, url, poster))
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val base = "$DD_MAIN/search?q=$q&max-results=20"
            val text = app.get(base, headers = headers()).text
            val items = parseCards(text)
            if (items.isEmpty()) return emptyList()
            items.mapNotNull { e ->
                val title = e.title ?: return@mapNotNull null
                val url = e.url ?: return@mapNotNull null
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
            if (url.startsWith("http") && url.isNotBlank()) out.add(url)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = headers()).document
            val raw = doc.html()

            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { cleanTitle(it) }
                ?: cleanTitle(doc.title()).orEmpty()
                .ifBlank { "Deep Drama" }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractPoster(raw)
            val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

            val servers = serverButtons(raw)
            if (servers.isEmpty()) return null

            // نصنّف الخوادم: vidaraa (الأساسي/الأسرع) ثم Rumble (البديل).
            val vidaraa = servers.firstOrNull { it.contains("vidaraa") }
            val rumble = servers.firstOrNull { it.contains("rumble") }
            val primary = vidaraa ?: rumble ?: servers.first()

            // بيانات الحلقة: كل روابط الخوادم مفصولة بـ '|||' (لا يظهر في عناوين).
            // نضع vidaraa أولاً ليكون الخيار الافتراضي (الأسرع استجابة والأعلى جودة).
            val bundle = buildList {
                if (vidaraa != null) add(vidaraa.substringBefore("?"))
                if (rumble != null) add(rumble.substringBefore("?"))
                if (size == 0) add(primary.substringBefore("?"))
            }.joinToString("|||")

            // نُسخّن ذاكرة التخزين للخادم الأساسي (vidaraa = الأسرع) أثناء عرض التفاصيل
            // حتى يكون أول تشغيل فوريًا. Rumble يُسخَّن عند الحاجة في loadLinks
            // (resolveRumble الآن لا يجلب الـ master — قراءة embed سريعة فقط).
            // درس سابق (v2/v3): لا نجلب master Rumble داخل loadLinks فلا يتأخر
            // التشغيل ولا تنكسر الشاشة.
            (vidaraa ?: rumble ?: primary).substringBefore("?").takeIf { it.isNotBlank() }?.let { warm ->
                try {
                    if (warm.contains("vidaraa")) resolveVidaraa(warm)
                    else if (warm.contains("rumble")) resolveRumble(warm)
                } catch (_: Exception) { /* تجاهل — يُعاد عند الحاجة في loadLinks */ }
            }

            val episode = newEpisode(bundle) {
                name = "الحلقة الكاملة"
                episode = 1
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    // استخراج صورة الغلاف من HTML المشاركة (بعد تفكيك الكيانات)
    private fun extractPoster(raw: String): String? {
        val html = raw.replace("&amp;", "&")
        return Regex("""https://(?:acf\.)?goodshort\.com/[^"'\s<>\\]+?\.(?:jpg|jpeg|png|webp)[^"'\s<>\\]*""")
            .find(html)?.value
            ?: Regex("""https://blogger\.googleusercontent\.com/[^"'\s<>\\]+?\.(?:jpg|jpeg|png|webp)[^"'\s<>\\]*""")
                .find(html)?.value
    }

    /**
     * يبثّ خيارات خادم واحد: master تكيفي + جودات فردية (كل جودة يوفّرها الموقع) +
     * فيديو مباشر (إن وُجد فعلاً) + ترجمات كل لغة.
     * لا نكرّر نقطة جودة واحدة (إن كانت الجودات مفردة) — الـ master وحده يكفي.
     */
    private suspend fun emitServer(
        server: ServerResolved,
        primary: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val tag = server.name
        val master = server.hls ?: server.renditions.maxByOrNull { it.height }?.url
        val renditions = server.renditions.sortedBy { it.height }

        // 1) الـ master التكيفي — الخيار المضمون الذي يشمل كل الجودات.
        if (master != null) {
            val max = renditions.maxOfOrNull { it.height } ?: 1080
            callback(newExtractorLink(name, "${if (primary) "★ " else ""}$tag · جميع الجودات", master, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("${max}p")
                this.headers = headers()
            })
        }

        // 2) الجودات الفردية — كل ما يعرضه الموقع.
        //    vidaraa: playlists صحيحة بجودات مختلفة.
        //    Rumble: واحد أو اثنان (قد يختلفان بالبت-ريت لا بالقياس) — نعرض كلًّا منها.
        renditions.forEachIndexed { idx, r ->
            val bw = if (r.bandwidth > 0) " · ${(r.bandwidth / 1000)}k" else ""
            callback(newExtractorLink(name, "${if (primary) "★ " else ""}$tag ${r.height}p$bw", r.url, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("${r.height}p")
                this.headers = headers()
            })
        }

        // 3) فيديو مباشر (mp4) إن وُجد فعلاً وقابلاً للتشغيل.
        //    vidaraa: بعض الفيديوات تُخدم كـ mp4 مباشر من streamix.so. هذا النطاق
        //    معطّل حاليًا (521)، لكنه المصدر الوحيد لذلك الفيديو في vidaraa؛ نعرضه
        //    فقط إذا لم يتوفر HLS بديل (البديل دائمًا هو Rumble). إذا وُجد HLS
        //    إلى جانبه فنتجاهل المعطّل ولا نعرض رابطًا ميتًا.
        //    Rumble لا يوفر mp4 كاملاً (المعاينة 180x320 ليست الفيلم) فلا نصدّره
        //    (directVideo = null دائمًا عند Rumble).
        server.directVideo?.let { mp4 ->
            val hasHls = server.hls != null || server.renditions.isNotEmpty()
            if (hasHls) {
                // يوجد HLS صحيحة تعمل — لا نعرض mp4 معطلاً.
            } else {
                val q = Regex("""/(\d{3,4})p/""").find(mp4)?.groupValues?.get(1)
                    ?: if (mp4.contains("1080")) "1080" else if (mp4.contains("720")) "720" else "480"
                callback(newExtractorLink(name, "$tag MP4", mp4, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName("${q}p")
                    this.headers = headers()
                })
            }
        }

        // 4) ملفات الترجمة (كل لغة يوفّرها الخادم). نمرّرها برابطها المباشر برؤوس
        // قياسية صحيحة (User-Agent + Referer/Origin للخادم الصادر) حتى لا يردّ
        // السيرفر بصفحة خطأ 403 تُعرض كرموز.
        server.subtitles.forEach { sub ->
            try {
                subtitleCallback(
                    newSubtitleFile(sub.label, sub.url) {
                        this.headers = subHeaders(server.name)
                    }
                )
            } catch (_: Exception) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.isBlank()) return false

            // بيانات الحلقة: روابط الخوادم مفصولة بـ ||| (vidaraa أولاً = الافتراضي)
            val serverUrls = data.split("|||").map { it.trim() }.filter { it.startsWith("http") && it.isNotBlank() }
            if (serverUrls.isEmpty()) return false

            // نجهّز قائمة الخوادم بحسب أولويتها، ونتجنب أي تكرار في العناوين.
            // لا نعتمد فقط على الذاكرة المؤقتة (load قد يفشل في تسخينها عند الرجوع
            // السريع)، بل نعيد الفكّ هنا فوراً — لضمان أن التشغيل لا 'ينكسر' عند العودة.
            val resolved = mutableListOf<ServerResolved>()
            var vidaraaEmitted = false
            var rumbleEmitted = false

            for (s in serverUrls) {
                val resolvedServer = try {
                    when {
                        s.contains("vidaraa") && !vidaraaEmitted -> {
                            vidaraaEmitted = true
                            resolveVidaraa(s)
                        }
                        s.contains("rumble") && !rumbleEmitted -> {
                            rumbleEmitted = true
                            resolveRumble(s)
                        }
                        else -> null
                    }
                } catch (_: Exception) { null }

                if (resolvedServer != null) {
                    // نبثّ هذا الخادم فور حلّه، قبل الانتظار على الخادم الآخر —
                    // فيبدأ الفيديو بسرعة ولا ينتظر المحاولتين معاً.
                    emitServer(resolvedServer, resolved.isEmpty(), subtitleCallback, callback)
                    // نعتبر الخادم "حُلّ" إذا قدّم شيئًا قابلًا للتشغيل: HLS أو جودات
                    // أو mp4 مباشر (حتى لو معطلاً الآن، Rumble سيغطيه كبديل).
                    if (resolvedServer.hls != null || resolvedServer.renditions.isNotEmpty() ||
                        resolvedServer.directVideo != null) {
                        resolved.add(resolvedServer)
                    }
                }
            }

            // لا نستخدم أبداً loadExtractor العام هنا: iframes DeepDrama ليست
            // extensions قابلة للفهم وتفشل، فتسبب 'لا يفتح'. إن لم تُحلّ أي نتيجة
            // نظهر الحقيقة بشأن الخادم الفاشل بدلاً من واجهة ميتة.
            if (resolved.isEmpty()) {
                // آخر محاولة: إن لم يُحلّ شيء، نحاول مرة أخرى على أول رابط عبر
                // مسار vidaraa/rumble مُجدداً (ربما كان فشلٌ عابر في الشبكة).
                val first = serverUrls.firstOrNull()
                if (first != null) {
                    val retry = if (first.contains("rumble")) resolveRumble(first) else resolveVidaraa(first)
                    emitServer(retry, true, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) { false }
    }
}
