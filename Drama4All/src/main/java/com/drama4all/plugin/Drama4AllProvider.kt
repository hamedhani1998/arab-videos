package com.drama4all.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// /api/local_search item
private data class SearchItem(
    val cover: String? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int = 0,
    val slug: String? = null,
    val title: String? = null,
    val views: Long? = null,
    @JsonProperty("likes_count") val likesCount: Long? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
)

// /api/episode/{slug}/{ep}
private data class EpisodeItem(
    @JsonProperty("video_url") val videoUrl: String? = null,
    val subs: List<SubtitleItem>? = null,
)

private data class SubtitleItem(
    val lang: String? = null,
    val url: String? = null,
)

class Drama4AllProvider : MainAPI() {
    override var name = "دراما للجميع"
    override var mainUrl = "https://drama4all.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "list/trending" to "الأكثر مشاهدة",
        "list/recent" to "أضيف حديثاً",
        "list/all" to "المكتبة",
    )

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val s = slug ?: return null
        // هذا المصدر مسئول عن محتوى دراما للجميع فقط (sf_)؛ محتوى narto يعالج في مصدر NartoDrama
        if (!s.startsWith("sf_")) return null
        val t = title ?: return null
        return newTvSeriesSearchResponse(t, "$mainUrl/series/$s", TvType.TvSeries) {
            posterUrl = cover
            episodes = totalEpisodes.coerceAtLeast(1)
            score = rating?.let { Score.from(it, 10) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("script").filter { el -> el.html().contains("const LIBRARY") }
                .flatMap { el ->
                    val html = el.html()
                    val start = html.indexOf('[')
                    val end = html.lastIndexOf(']')
                    if (start < 0 || end <= start) return@flatMap emptyList()
                    try {
                        val list = mapper.readValue(
                            html.substring(start, end + 1),
                            object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                        )
                        list.mapNotNull { it.toSearchResponse() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val res = app.get("$mainUrl/api/local_search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", referer = mainUrl).text
            val items: List<SearchItem> = try {
                mapper.readValue(
                    res,
                    object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                )
            } catch (e: Exception) {
                return null
            }
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("p.synopsis")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("div.stage-tags a.tag").mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }

            // Slug from embedded SERIES JSON
            val slug = doc.select("script").mapNotNull { el ->
                val html = el.html()
                val m = Regex("""const SERIES\s*=\s*\{[^}]*slug:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(html)
                m?.groupValues?.get(1)
            }.firstOrNull()

            val eps = doc.select("div.eps a[data-ep]").mapNotNull { el ->
                val ep = el.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                newEpisode("/watch/$slug/$ep") {
                    episode = ep
                    name = "الحلقة $ep"
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
                this.tags = tags
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = /watch/<slug>/<ep>
            val m = Regex("""/watch/([\w\-]+)/(\d+)""").find(data) ?: return false
            val slug = m.groupValues[1]
            val ep = m.groupValues[2]

            val json = app.get("$mainUrl/api/episode/$slug/$ep", referer = "$mainUrl/watch/$slug/$ep").text
            val item = try {
                mapper.readValue(json, EpisodeItem::class.java)
            } catch (e: Exception) {
                return false
            }
            val vUrl = item.videoUrl ?: return false

            // 1) كل الترجمات/القرائن
            item.subs?.forEach { s ->
                val lang = s.lang ?: return@forEach
                val subUrl = s.url ?: return@forEach
                if (subUrl.isNotBlank()) {
                    try {
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    } catch (e: Exception) {}
                }
            }

            // 2) تحليل الـ m3u8: هل هو master (جودات + أصوات متعددة) أم ملف جودة واحدة؟
            val streamText = try { app.get(vUrl, referer = mainUrl).text } catch (e: Exception) { "" }

            if (streamText.contains("#EXT-X-STREAM-INF")) {
                // master playlist => نكشف كل الجودات وكل الأصوات
                val base = vUrl.substringBeforeLast('/')

                // روابط مقاطع الفيديو الخاصة بكل جودة (#EXT-X-STREAM-INF -> URI بالسطر التالي)
                val renditions = mutableListOf<Pair<Int, String>>()
                Regex("""#EXT-X-STREAM-INF:([^\n]*)\n\s*([^\s\n#]+)""").findAll(streamText).forEach { mm ->
                    val attrs = mm.groupValues[1]
                    val uri = mm.groupValues[2]
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    if (h != null && uri.isNotBlank()) {
                        renditions.add(h to (if (uri.startsWith("http")) uri else "$base/$uri"))
                    }
                }

                // أطواق الصوت (الدبلجة) من #EXT-X-MEDIA
                val audioUrls = Regex("""(?m)#EXT-X-MEDIA:TYPE=AUDIO[^\n]*?URI="([^"]+)"[^\n]*""")
                    .findAll(streamText)
                    .mapNotNull { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) }
                    .map { u -> if (u.startsWith("http")) u else "$base/$u" }
                    .distinct().toList()
                val audioTracks = audioUrls.mapNotNull { u ->
                    try { newAudioFile(u) { headers = mapOf("Referer" to mainUrl) } } catch (e: Exception) { null }
                }

                if (renditions.isNotEmpty()) {
                    // رابط واحد لكل جودة حتى تظهر جميع الجودات
                    renditions.sortedByDescending { it.first }.forEach { (h, rendUrl) ->
                        val qName = "${h}p"
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$qName (صوت)",
                                url = rendUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = mainUrl
                                quality = getQualityFromName(qName)
                                headers = mapOf("Referer" to mainUrl)
                                this.audioTracks = audioTracks
                            }
                        )
                    }
                    // رابط master كامل: كل الجودات + كل الأصوات (ضمانة للصوت)
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Full HD (كل الجودات والأصوات)",
                            url = vUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = mainUrl
                            quality = getQualityFromName("1080p")
                            headers = mapOf("Referer" to mainUrl)
                            this.audioTracks = audioTracks
                        }
                    )
                } else {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "HLS",
                            url = vUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = mainUrl
                            headers = mapOf("Referer" to mainUrl)
                            this.audioTracks = audioTracks
                        }
                    )
                }
            } else {
                // ملف media وحيد (جودة واحدة): نكشف عنه مباشرة
                val q = when {
                    vUrl.contains("1080p") -> "1080p"
                    vUrl.contains("720p") -> "720p"
                    vUrl.contains("480p") -> "480p"
                    else -> "480p"
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = q,
                        url = vUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to mainUrl)
                    }
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}