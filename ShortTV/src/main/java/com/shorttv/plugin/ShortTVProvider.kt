package com.shorttv.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URL

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

class ShortTVProvider : MainAPI() {
    override var name = "ShortTV"
    override var mainUrl = "https://www.shorttv.live"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // رابط صفحة المسلسل: /ar/drama/{slug}-{showId}
    private val dramaLinkRegex = Regex("""/ar/drama/([^"'\\?]+)""")
    // رابط صفحة الحلقة: /ar/episode/{slug}-{showId}-{ep}
    private val episodeLinkRegex = Regex("""/ar/episode/([^"'\\?]+)""")

    // استخراج __NUXT_DATA__ من صفحة ShortTV
    private fun extractNuxtData(html: String): JsonNode? {
        val regex = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>([\s\S]*?)</script>""")
        val match = regex.find(html) ?: return null
        return try { mapper.readTree(match.groupValues[1]) } catch (e: Exception) { null }
    }

    // حلّ مرجع __NUXT_DATA__
    private fun resolveRef(data: JsonNode, idx: Int, maxHops: Int = 6): JsonNode? {
        var cur: JsonNode? = data.get(idx) ?: return null
        var hops = 0
        while (cur != null && cur.isNumber && cur.asInt() < data.size() && hops < maxHops) {
            cur = data.get(cur.asInt())
            hops++
        }
        return cur
    }

    // تحليل قائمة العروض من صفحة ShortTV الرئيسية
    // الواجهة أصبحت تُصيّر البطاقات في HTML مباشرة (drama-card) بدلاً من NUXT_DATA__
    private fun parseShowList(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        // كل بطاقة: <div class="drama-card"> ... <a href="/ar/episode/{slug}-{id}-1" class="card-image"><img alt="..." ...>
        //   + <a href="/ar/drama/{slug}-{id}" class="card-title-layout"><p class="card-title">NAME</p>
        val cardRe = Regex("""<div class="drama-card"[\s\S]*?</div>""")
        val epHrefRe = Regex("""href="(/ar/episode/[^"]+)"""")
        val dramaHrefRe = Regex("""href="(/ar/drama/[^"]+)"""")
        val posterRe = Regex("""<img[^>]*?(?:data-src|src)="(https://akamai-static[^"]+)"""")
        val titleRe = Regex("""<p class="card-title"[^>]*>([^<]+)</p>""")
        for (m in cardRe.findAll(html)) {
            val block = m.value
            val epHref = epHrefRe.find(block)?.groupValues?.get(1) ?: continue
            val dramaHref = dramaHrefRe.find(block)?.groupValues?.get(1) ?: continue
            // id من رابط الحلقة: /ar/episode/{slug}-{id}-1 → آخر رقمين
            val epClean = epHref.substringBefore("?").substringBefore("#")
            val epSegs = epClean.split("-")
            val id = epSegs.takeLast(2).firstOrNull()?.takeIf { it.all(Char::isDigit) } ?: continue
            if (id.length < 3 || !seen.add(id)) continue
            val title = titleRe.find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: continue
            val poster = posterRe.find(block)?.groupValues?.get(1)
            val url = "$mainUrl$dramaHref"
            results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        if (results.isNotEmpty()) return results
        // احتياطي: NUXT_DATA القديمة (إن وُجدت)
        val data = extractNuxtData(html) ?: return emptyList()
        for (i in 0 until data.size()) {
            val e = data.get(i) ?: continue
            if (!e.isObject) continue
            if (!e.has("shortPlayId")) continue
            val playId = resolveRef(data, e.get("shortPlayId").asInt())?.asText() ?: continue
            if (!seen.add(playId)) continue
            val titleNode = resolveRef(data, e.get("shortPlayName")?.asInt() ?: -1)
            val title = titleNode?.asText()?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: continue
            val posterNode = resolveRef(data, e.get("coverUrl")?.asInt() ?: -1)
            val poster = posterNode?.asText()
            results.add(newTvSeriesSearchResponse(title, "$mainUrl/ar/drama/$playId", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return results
    }

    // من مسار /ar/drama/{slug}-{showId} نستخرج showId
    private fun showIdFromPath(rawPath: String): String? {
        val path = try { java.net.URLDecoder.decode(rawPath, "UTF-8") } catch (e: Exception) { rawPath }
        val parts = path.split("-")
        if (parts.isEmpty()) return null
        for (i in parts.indices.reversed()) {
            val seg = parts[i]
            if (seg.length >= 3 && seg.all(Char::isDigit)) return seg
        }
        return null
    }

    private data class ShowInfo(
        val shortPlayId: String,
        val title: String,
        val poster: String?,
        val description: String?,
        val lockBegin: Int,
        val episodeList: List<EpisodeInfo>,
    )

    private data class EpisodeInfo(
        val episodeNum: Int,
        val videoUrl480: String?,
        val videoUrl720: String?,
        val videoUrl1080: String?,
        val isFree: Boolean,
    )

    // يحلّل __NUXT_DATA__ في صفحة الحلقة ويستخرج معلومات المسلسل + قائمة الحلقات
    private fun parseShowFromNuxt(data: JsonNode): ShowInfo? {
        for (i in 0 until data.size()) {
            val e = data.get(i)
            if (e == null || e.isNull || !e.isObject) continue
            if (!e.has("shortPlayId") || !e.has("episodeList")) continue
            val playId = resolveRef(data, e.get("shortPlayId").asInt())?.asText() ?: continue
            val titleNode = resolveRef(data, e.get("shortPlayName")?.asInt() ?: -1)
            val title = titleNode?.asText()?.takeIf { it.isNotBlank() } ?: playId
            val posterNode = resolveRef(data, e.get("coverUrl")?.asInt() ?: -1)
            val poster = posterNode?.asText()
            val descNode = resolveRef(data, e.get("shortPlayDescription")?.asInt() ?: -1)
            val desc = descNode?.asText()
            val lockNode = resolveRef(data, e.get("lockBegin")?.asInt() ?: -1)
            val lockBegin = lockNode?.asInt() ?: 0
            val epListNode = data.get(e.get("episodeList").asInt())
            val eps = if (epListNode != null && epListNode.isArray) {
                epListNode.mapNotNull { ref ->
                    val epObj = resolveRef(data, ref.asInt()) ?: return@mapNotNull null
                    val num = epObj.get("episodeNum")?.asInt() ?: return@mapNotNull null
                    val isFree = num <= lockBegin
                    val encNode = epObj.get("encryptedVideoUrl")
                    var u480: String? = null; var u720: String? = null; var u1080: String? = null
                    if (encNode != null && encNode.isInt) {
                        val str = resolveRef(data, encNode.asInt())?.asText()
                        if (str != null) {
                            try {
                                val obj = mapper.readTree(str)
                                u480 = obj.get("video_480")?.asText()
                                u720 = obj.get("video_720")?.asText()
                                u1080 = obj.get("video_1080")?.asText()
                            } catch (_: Exception) {}
                        }
                    }
                    EpisodeInfo(num, u480, u720, u1080, isFree)
                }
            } else emptyList()
            return ShowInfo(playId, title, poster, desc, lockBegin, eps)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/${request.data}", referer = mainUrl).text
            val items = parseShowList(res)
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val res = app.get("$mainUrl/ar", referer = mainUrl).text
            val all = parseShowList(res)
            val q = query.trim().lowercase()
            if (q.isBlank()) all else all.filter { it.name.lowercase().contains(q) }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            // نستخرج showId من URL
            val path = url.substringAfter("/ar/").substringAfter("/")
            val showId = showIdFromPath(path) ?: return null
            // صفحة الحلقة الأولى تحوي بيانات المسلسل كاملة في __NUXT_DATA__
            val res = app.get("$mainUrl/ar/episode/$showId-1", referer = mainUrl).text
            val nuxt = extractNuxtData(res)
            val show = nuxt?.let { parseShowFromNuxt(it) }
            val effectiveShortPlayId = show?.shortPlayId?.takeIf { it.all(Char::isDigit) } ?: showId
            val episodes = show?.episodeList ?: emptyList()
            // العنوان: من NUXT_DATA__ أولاً، ثم og:title من HTML
            val nuxtTitle = show?.title?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
            val ogTitle = Regex("""<meta\s+property="og:title"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)?.trim()
            val h1 = Regex("""<h1[^>]*>([^<]+)</h1>""").find(res)?.groupValues?.get(1)?.trim()
            val title = nuxtTitle ?: ogTitle ?: h1 ?: "ShortTV #$showId"
            val plot = show?.description
            val nuxtPoster = show?.poster
            val ogImage = Regex("""<meta\s+property="og:image"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)
            val poster = nuxtPoster ?: ogImage
            if (episodes.isEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                    newEpisode("$showId|1") { episode = 1; name = "الحلقة 1" }
                )) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            val sortedEps = episodes.sortedBy { it.episodeNum }
            val eps = sortedEps.mapIndexed { index, ep ->
                val serialNo = index + 1
                newEpisode("$effectiveShortPlayId|${ep.episodeNum}") {
                    episode = serialNo
                    name = if (ep.isFree) "الحلقة $serialNo" else "🔒 الحلقة $serialNo"
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    // استخراج معلومات الحلقة من __NUXT_DATA__ لمعرّف معيّن
    private fun findEpisodeUrls(html: String, episodeNum: Int): Triple<String?, String?, String?>? {
        val nuxt = extractNuxtData(html) ?: return null
        for (i in 0 until nuxt.size()) {
            val e = nuxt.get(i)
            if (e == null || !e.isObject || !e.has("shortPlayId")) continue
            val eps = nuxt.get(e.get("episodeList").asInt())
            if (eps == null || !eps.isArray) continue
            for (ref in eps) {
                val epObj = resolveRef(nuxt, ref.asInt()) ?: continue
                val num = epObj.get("episodeNum")?.asInt() ?: continue
                if (num != episodeNum) continue
                val encNode = epObj.get("encryptedVideoUrl") ?: continue
                if (!encNode.isInt) continue
                val str = resolveRef(nuxt, encNode.asInt())?.asText() ?: continue
                return try {
                    val obj = mapper.readTree(str)
                    Triple(
                        obj.get("video_480")?.asText(),
                        obj.get("video_720")?.asText(),
                        obj.get("video_1080")?.asText()
                    )
                } catch (_: Exception) { null }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            if (parts.size != 2) return false
            val shortPlayId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false
            // صفحة الحلقة الأولى للمسلسل تكفي لاستخراج كل الحلقات
            val res = app.get("$mainUrl/ar/episode/${shortPlayId}-1", referer = mainUrl).text
            val urls = findEpisodeUrls(res, ep) ?: return false
            var found = false
            urls.third?.let { url ->
                callback(newExtractorLink(name, "1080p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("1080p")
                })
                found = true
            }
            urls.second?.let { url ->
                callback(newExtractorLink(name, "720p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("720p")
                })
                found = true
            }
            urls.first?.let { url ->
                callback(newExtractorLink(name, "480p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("480p")
                })
                found = true
            }
            found
        } catch (e: Exception) { false }
    }
}
