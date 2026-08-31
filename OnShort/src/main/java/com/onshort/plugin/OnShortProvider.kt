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

// قاعدة واجهة برمجة تطبيقات OnShort (نفسها للنسختين عربي/إنجليزي، يختلف فقط param lang)
private const val ONS_API = "https://onshort.net/wp-json/onshort-theme/v1"
private const val ONS_PLAY_API = "https://onshort.net/wp-json/onshort-shortmax/v1"

// بطاقة مسلسل على الصفحة الرئيسية / البحث (يُحلَّل من HTML الخاص بـ listing)
private val cardRe = Regex(
    """<article class="series-card"[^>]*data-series-card="(\d+)"[^>]*>\s*<a class="series-card__link" href="([^"]+)"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"[^>]*>.*?<span class="episode-pill">(\d+)\s*<small""",
    RegexOption.DOT_MATCHES_ALL
)

// مفاتيح في صفحة التفاصيل
// المعرّف الأهم هو الرقم داخل رابط runtime (/series/{id}/runtime) لأنه المطابق لرابط الحلقة
private val runtimeIdRe = Regex("""/series/(\d{3,12})/runtime""")
private val detailTotalRe = Regex("""(?:total"|Episodes")\s*:\s*(\d{1,5})""")
private val ogTitleRe = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
private val ogImageRe = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
private val ogDescRe = Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""")

// نتيجة تشغيل الحلقة من واجهة التشغيل
private data class OnsEpisode(
    val url: String?,
    val sources: Map<String, String>? = null,
    val subtitleList: List<OtherSubtitle>? = null,
)

private data class OtherSubtitle(
    val url: String?,
    val lang: String?,
)

/**
 * OnShort — WordPress REST API يجمّع عدة منصات (shortmax/netshort/goodshort…).
 * النسخة العربية والنسخة الإنجليزية عقدهما نفس البنية؛ فقط mainUrl و lang يختلفان.
 * الإصداران مسجّلان كأساسيين منفصلين (OnShortArabicProvider / OnShortEnglishProvider).
 */
abstract class OnShortProvider : MainAPI() {
    // lang المستخدم في واجهات البحث/العرض
    abstract val apiLang: String

    // رابط الموقع كأساس (العربي ينتهي /ar)
    override var mainUrl: String = "https://onshort.net"

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
            val base = "$ONS_API/listing?page=$page&per_page=12&lang=$apiLang"
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
            val base = "$ONS_API/search?q=$q&limit=48&lang=$apiLang"
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

            // معرّف المنشور (WP post id) الذي تستخدمه واجهة التشغيل — من رابط runtime أولاً
            val postId = runtimeIdRe.find(res)?.groupValues?.get(1)
                ?: Regex(""""id":(\d{3,12})""").find(res)?.groupValues?.get(1)
                ?: return null

            // عدد الحلقات
            val total = detailTotalRe.find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // العنوان: og:title ثم H1 احتياطياً
            val title = ogTitleRe.find(res)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(res)?.groupValues?.get(1)
                    ?.replace(Regex("""<[^>]+>"""), "")?.trim()
                ?: url.substringAfterLast('/').substringBefore('?').replace('-', ' ').trim()
                ?: "OnShort"

            val poster = ogImageRe.find(res)?.groupValues?.get(1)
            val plot = ogDescRe.find(res)?.groupValues?.get(1)

            val eps = mutableListOf<Episode>()
            // كل حلقة نمرر (postId | رقم الحلقة) لاسترجاع الرابط لاحقاً
            for (n in 1..total) {
                eps.add(newEpisode("$postId|$n") {
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
            if (parts.size != 2) return false
            val postId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false
            val epUrl = "$ONS_PLAY_API/series/$postId/episode/$ep"
            val text = app.get(epUrl, referer = mainUrl, headers = headers()).text
            val node = mapper.readTree(text)
            if (node.has("error") || node.has("code")) return false

            // المصادر: 480/720/1080 — نعرض كل جودة حقيقية كرابط منفصل
            val sources = node.get("sources")
            val emitted = java.util.HashSet<String>()
            if (sources != null && sources.isObject) {
                val fields = sources.fields()
                while (fields.hasNext()) {
                    val e = fields.next()
                    val u = e.value?.asText() ?: continue
                    if (u.isBlank() || !emitted.add(u)) continue
                    val label = "${e.key}p"
                    callback(newExtractorLink(name, label, u, ExtractorLinkType.M3U8) {
                        this.quality = getQualityFromName(label)
                        this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                    })
                }
            }
            // الرابط الرئيسي احتياطياً (قد يكون الجودة المفضلة)
            val mainUrl0 = node.get("url")?.asText()
            if (!mainUrl0.isNullOrBlank() && emitted.add(mainUrl0)) {
                callback(newExtractorLink(name, "تشغيل", mainUrl0, ExtractorLinkType.M3U8) {
                    this.quality = getQualityFromName((node.get("quality")?.asText() ?: "720p"))
                    this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                })
            }

            // الترجمات (لكل حلقة)
            val subs = node.get("subtitles")
            if (subs != null && subs.isArray) {
                for (s in subs) {
                    val su = s.get("url")?.asText() ?: continue
                    if (su.isBlank()) continue
                    val lang = s.get("lang")?.asText() ?: s.get("subtitleLanguage")?.asText() ?: "ar"
                    try { subtitleCallback(newSubtitleFile(lang, su)) } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) { false }
    }
}

class OnShortEnglishProvider : OnShortProvider() {
    override var name = "OnShort (English)"
    override var mainUrl = "https://onshort.net"
    override var lang = "en"
    override val apiLang = "en"
    override val mainPage = mainPageOf("latest" to "Latest Short Drama")
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
}

class OnShortArabicProvider : OnShortProvider() {
    override var name = "OnShort (عربي)"
    override var mainUrl = "https://onshort.net/ar"
    override var lang = "ar"
    override val apiLang = "ar"
    override val mainPage = mainPageOf("latest" to "أحدث المسلسلات")
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
}
