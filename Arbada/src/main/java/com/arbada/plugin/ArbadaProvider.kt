package com.arbada.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64

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
        "categories/arab-porn-sex-افلام-سكس-عربي/" to "عربي",
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
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                    }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/videos/?q=$query", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data, referer = mainUrl).document
            val allScript = doc.select("script").joinToString("\n") { it.html() }
            var found = false

            // Method 1: flashvars (primary KVS method - get ALL qualities)
            if (allScript.contains("flashvars")) {
                val urls = extractFlashvars(allScript)
                for ((url, quality) in urls) {
                    if (url.isNotBlank()) {
                        lnk(url, quality, callback)
                        found = true
                    }
                }
            }
            if (found) return true

            // Method 2: Direct video_url regex
            val directUrl = Regex("""video_url\s*=\s*["']([^"']+)["']""").find(allScript)?.groupValues?.get(1)
            if (!directUrl.isNullOrBlank()) {
                lnk(directUrl, "360p", callback)
                val altUrl = Regex("""video_alt_url\s*=\s*["']([^"']+)["']""").find(allScript)?.groupValues?.get(1)
                if (!altUrl.isNullOrBlank()) lnk(altUrl, "480p", callback)
                val altUrl2 = Regex("""video_alt_url2\s*=\s*["']([^"']+)["']""").find(allScript)?.groupValues?.get(1)
                if (!altUrl2.isNullOrBlank()) lnk(altUrl2, "720p", callback)
                return true
            }

            // Method 3: HTML5 video sources (DON'T return early - continue to flashvars)
            doc.select("video source, video").forEach { src ->
                val url = src.attr("src")
                if (url.isNotBlank()) {
                    lnk(url, detectQuality(url), callback)
                    found = true
                }
            }

            // Method 4: iframe embed
            val iframe = doc.selectFirst("div.embed-wrap iframe, iframe[src*=embed], iframe[src*=video]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    lnk(iframeUrl, "720p", callback)
                    return true
                }
            }

            found
        } catch (e: Exception) { false }
    }

    private fun extractFlashvars(script: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val flashvarsPattern = Regex("""flashvars\s*[=:]\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
        val block = flashvarsPattern.find(script)?.groupValues?.get(1) ?: return results

        val entries = listOf(
            "video_url" to "360p",
            "video_alt_url" to "480p",
            "video_alt_url2" to "720p",
            "video_alt_url3" to "1080p"
        )
        for ((key, defaultQuality) in entries) {
            val url = extractFromBlock(block, key) ?: continue
            val qualityKey = "${key}_text"
            val quality = extractFromBlock(block, qualityKey) ?: defaultQuality
            results.add(url to quality)
        }
        return results
    }

    private fun extractFromBlock(block: String, key: String): String? {
        val pattern = Regex("""$key\s*:\s*["']([^"']+)["']""")
        val match = pattern.find(block) ?: return null
        return match.groupValues[1].ifBlank { null }
    }

    private fun detectQuality(url: String): String = when {
        url.contains("1080") -> "1080p"
        url.contains("720") -> "720p"
        url.contains("480") -> "480p"
        url.contains("360") -> "360p"
        else -> "360p"
    }

    private fun clean(url: String): String = when {
        url.startsWith("function/0/") -> {
            val encoded = url.removePrefix("function/0/")
            try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (_: Exception) {
                encoded
            }
        }
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    private fun getVideoType(url: String): ExtractorLinkType {
        return if (url.contains(".m3u8") || url.contains("m3u8")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
    }

    private suspend fun lnk(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val cleaned = clean(url)
        if (cleaned.isBlank()) return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = cleaned,
                type = getVideoType(cleaned)
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(quality)
            }
        )
    }
}
