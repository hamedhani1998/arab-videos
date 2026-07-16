package com.arbada.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArbadaProvider : MainAPI() {
    override var name = "العربدة"
    override var mainUrl = "https://www.arbada.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "categories/افلام-سكس-عربي/" to "عربي",
        "categories/افلام-سكس-مترجم-عربي/" to "مترجم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("src") }
                    }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")

                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) {
                            this.score = Score.from(rating, 100)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/$query/", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("src") }
                    }

                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")
                ?.split(",")?.map { it.trim() }?.take(6)

            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
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
            val doc = app.get(data, referer = mainUrl).document
            val script = doc.select("script").map { it.html() }
                .firstOrNull { it.contains("flashvars") } ?: return false

            val videoUrl = extractFlashvarValue(script, "video_url")
            val videoAltUrl = extractFlashvarValue(script, "video_alt_url")
            val videoAltUrl2 = extractFlashvarValue(script, "video_alt_url2")
            val videoUrlText = extractFlashvarValue(script, "video_url_text") ?: "360p"
            val videoAltUrlText = extractFlashvarValue(script, "video_alt_url_text") ?: "480p"
            val videoAltUrl2Text = extractFlashvarValue(script, "video_alt_url2_text") ?: "720p"

            val referer = mainUrl

            videoUrl?.let { url ->
                val cleanUrl = cleanVideoUrl(url)
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(videoUrlText)
                    }
                )
            }

            videoAltUrl?.let { url ->
                val cleanUrl = cleanVideoUrl(url)
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(videoAltUrlText)
                    }
                )
            }

            videoAltUrl2?.let { url ->
                val cleanUrl = cleanVideoUrl(url)
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(videoAltUrl2Text)
                    }
                )
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractFlashvarValue(script: String, key: String): String? {
        // Try multiple patterns for better compatibility
        val patterns = listOf(
            """$key\s*[:=]\s*['"]([^'"]+)['"]""",
            """$key\s*:\s*["']([^"']+)["']""",
            """$key\s*=\s*["']([^"']+)["']""",
            """$key\s*:\s*'([^']+)'""",
            """$key\s*=\s*'([^']+)'""",
        )
        
        for (pattern in patterns) {
            try {
                val regex = Regex(pattern)
                val match = regex.find(script)
                if (match != null) {
                    val value = match.groupValues[1].ifBlank { null }
                    if (value != null) return value
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun cleanVideoUrl(url: String): String {
        return when {
            url.startsWith("function/0/") -> url.removePrefix("function/0/")
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }
}
