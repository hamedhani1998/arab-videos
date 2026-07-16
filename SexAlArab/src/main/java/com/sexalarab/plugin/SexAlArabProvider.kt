package com.sexalarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SexAlArabProvider : MainAPI() {
    override var name = "سكس العرب"
    override var mainUrl = "https://sexalarab.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "category/سكس-مترجم/" to "مترجم",
        "category/سكس-امهات/" to "أمهات",
        "category/سكس-محارم/" to "محارم",
        "category/سكس-نيك-عربي/" to "عربي",
        "category/مسلسلات-سكس-مترجم/" to "مسلسلات",
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
            val doc = app.get("$mainUrl/search/videos/?q=$query", referer = mainUrl).document
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
            
            // Method 1: HTML5 video sources
            val videoSources = doc.select("video source")
            if (videoSources.isNotEmpty()) {
                videoSources.forEach { source ->
                    val src = source.attr("src")
                    val quality = source.attr("title").ifBlank {
                        when {
                            src.contains("720p") -> "720p"
                            src.contains("480p") -> "480p"
                            src.contains("360p") -> "360p"
                            src.contains("240p") -> "240p"
                            else -> "360p"
                        }
                    }
                    if (src.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = src,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
                return true
            }
            
            // Method 2: Flashvars
            val script = doc.select("script").map { it.html() }
                .firstOrNull { it.contains("flashvars") }
            
            if (script != null) {
                val videoUrl = extractFlashvarValue(script, "video_url")
                val videoAltUrl = extractFlashvarValue(script, "video_alt_url")
                val videoAltUrl2 = extractFlashvarValue(script, "video_alt_url2")
                val videoUrlText = extractFlashvarValue(script, "video_url_text") ?: "360p"
                val videoAltUrlText = extractFlashvarValue(script, "video_alt_url_text") ?: "480p"
                val videoAltUrl2Text = extractFlashvarValue(script, "video_alt_url2_text") ?: "720p"

                videoUrl?.let { url ->
                    val cleanUrl = cleanVideoUrl(url)
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = cleanUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = mainUrl
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
                            this.referer = mainUrl
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
                            this.referer = mainUrl
                            this.quality = getQualityFromName(videoAltUrl2Text)
                        }
                    )
                }
                return true
            }
            
            // Method 3: Iframe embed
            val iframe = doc.selectFirst("div.embed-wrap iframe, iframe[src*=embed]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = iframeUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = data
                            this.quality = getQualityFromName("720p")
                        }
                    )
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun extractFlashvarValue(script: String, key: String): String? {
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
