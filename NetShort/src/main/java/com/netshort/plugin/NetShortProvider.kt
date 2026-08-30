package com.netshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URL
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val NS_AES_KEY = "5k3KYTOO9jnO0CeyGhdHc3pIjGnVgrMN"
private const val NS_RSA_PUBLIC = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2poXMstZ8NCWE7915MXz
DWC5/t+oB2waGfskPqSZwLqxd4ZBR0H1cb1tAZRZcV7P+LmOd6SYNxhnELaWuKTD
+D3xkz8Tt1L5j/ynGqVt1MDbiQIEzXQKUkNDSH6T0A+Xzo/67/8QOQXlVJfW06res
baeNvibfx6Qc78j96bCIPlxPrtieilVTBHUFOXjirxK/ki/mO8P2smRbpt73fsQW
dGmTGMfYGvfPApGyxbxLkL/qrBjU25XpM8a0MBqzFWUAchHmqSBJ6Mbfam1SSgf3
b2U28s67nOW+JiOrhd6iVLcsLFxXA54HX+Zbej3AbOB6jKaEmp/bz1amneE1NYXw
wIDAQAB
-----END PUBLIC KEY-----
"""
private const val NS_RSA_PRIVATE = """
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCK0Tl1pd7bjTRU93bWoHW1hLCDj2+9bg1MgY8j5C7xXaw6bJfToXhWbH1fXNbnFFVqxyYNErcuOUwJZxyDgcxUXM4yWnRseb2GF97GOicAQ2keDzVYmwky4lrSRwvcXutJRLPUCRQNfc6upfk2G5TKh6/CcP4TV1eXTF7+vdEw2SHxAOITKbSfcaZXr/hVs6a1aRHsBF+7RG99ebwZIP6/AgIyqX9RbDVN6ixi1v2G3/bwAULHLSqGdSaqij/ca17fbFGITaeCeEaZ6d/P4ZuOK+PEPdbPQt6SbY4lZaYwRvdrpH73kigPITgDzIDONFybJ1m7wRKlq1wxWHwbimptAgMBAAECggEAPz3cYJXFtt5YphDrahJGLgEabYVOUc2ub1li/eX54OpdCWzpqneYnD7myyg/m5zu4SuDUVdibsOZuXrpSZw7m3+ATP5apgS8bDe5vTNHC16qqBAjrI9NHIp09/F4HNh9dq6/Am10XkUfgP+KTrU4DyDL2NijV+pltD8N1B5kDE1igokVcsavhnu2INoMRXYE78Wq6urNECuFWw9hldv81M9m2w56t1CQOUukpo4mfmLjZRe2s+kwtcBVefGHP8Cj0OeH2dGltjl2YSQMRBFUCVoixYpOrcjIHoqzWri8IfUZ2tW+nUvHl5IZ9RVxefnFaLGnxiXd2sk6Sn4aD/l9YQKBgQDVv3HaOZxHRqlNSPrNGqplGhE066HnDsq6MlPukiovxE43CRBmpTnk9zDCqrDh9t2HbJuao7nSq5WlBERWgwqXU/qDpH43W7Y/lJfHkDv6A2m0viJa0a9x8+CJpNnCDu1ATo4/IQKwoXYice6JKnUyXgkGKn+HipiN6tO0EtWHlQKBgQCmQfklKFtXtm/FZ6NIMs+d+EyvaE5xNLKGYQxmiCR10WGYd8ZV+K0Q6qXHS+a32TirWB9F3TqPOklTytMrfPZB3BCXj4weEldb8W716G8FYf7LLhaT+MdpF7KDcruObwoQAvKV3N4eX6tUEMmdrx9hpCmmIU5EeXUkhGdmwk7BeQKBgAIXMkThJV8pGMTRvuo8pYgBnkN3PoklAuSZU2rU8Sawc9dj9k4atZtAs7BjvQEoyffmHwt/KHUgCoGnrgdulq7uOlgJRtbBxeGPUYC5L2z9lY4YAfwDawThTsPp4dtdDAMCAbAqYX1axu4FUUD0MltAwjPWPJMVzvIsZs+vE3mVAoGAJPja3OaCmZjadj2709xoyypic0dw2j/ry3JdfZec9A5h87P/CTNJ2U81GoLIhe3qakAohDLUSPGfSOD74NnjMXYswmeLs0xE3Q9tq4XK2pmWPby8DJ/wSHCapByplN0gkbr2E1mQk5SW1xT8oPJGukH1eRpC+3s/D6XaEMH5HZECgYEAigoX5l39LDsCgeaUcI4S9grkaas/WsKv37eqo3oD9Qk6VFiMM5L5Zig6aXJxuAPLVjb38caJRPmPmOXLT2kEP1E1h6OJOhEhETwVIUtcBzsK25ju9LqL89bC+W0uS7BPvk6Tcws/tXHCkQCTgb9jVXceZ2ox+6axvlW/5WgHt5Q=
-----END PRIVATE KEY-----
"""

private fun pemToBytes(pem: String): ByteArray {
    val stripped = pem
        .replace(Regex("-----BEGIN [A-Z ]+-----"), "")
        .replace(Regex("-----END [A-Z ]+-----"), "")
        .replace(Regex("\\s"), "")
    return Base64.getDecoder().decode(stripped)
}

private fun encryptRequestAes(json: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(NS_AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
    return Base64.getEncoder().encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
}

private fun decryptResponseAes(b64Cipher: String, respAesKeyBase64: String): String {
    // respAesKeyBase64 is the RSA-decrypted response: it's the server's per-request AES key,
    // delivered as a base64 string. Decode once to get the 32 raw bytes.
    val rawKey = Base64.getDecoder().decode(respAesKeyBase64)
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"))
    return String(cipher.doFinal(Base64.getDecoder().decode(b64Cipher)), Charsets.UTF_8)
}

private fun rsaEncrypt(plain: ByteArray): ByteArray {
    val pub = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(pemToBytes(NS_RSA_PUBLIC)))
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, pub)
    return cipher.doFinal(plain)
}

private fun rsaDecrypt(cipherBytes: ByteArray): ByteArray {
    val priv = KeyFactory.getInstance("RSA")
        .generatePrivate(PKCS8EncodedKeySpec(pemToBytes(NS_RSA_PRIVATE)))
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.DECRYPT_MODE, priv)
    return cipher.doFinal(cipherBytes)
}

private data class EpisodeResult(
    val playVoucher: String?,
    val isLock: Boolean?,
    val subtitleList: List<SubtitleItem>?,
)

private data class SubtitleItem(
    val url: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("subtitleLanguage")
    val language: String?,
)

private fun nsPost(path: String, body: Map<String, Any?>): Map<String, Any?>? {
    val plain = mapper.writeValueAsString(body)
    val aesB64 = encryptRequestAes(plain)
    val keyB64 = Base64.getEncoder().encodeToString(NS_AES_KEY.toByteArray(Charsets.UTF_8))
    val encKey = Base64.getEncoder().encodeToString(rsaEncrypt(keyB64.toByteArray(Charsets.UTF_8)))
    val url = URL("https://netshort.com/prod-web-api$path")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
    conn.setRequestProperty("encrypt-key", encKey)
    conn.setRequestProperty("OS", "4")
    conn.setRequestProperty("canary", "v1")
    conn.setRequestProperty("version", "1.2.0")
    conn.setRequestProperty("Content-Language", "ar_AE")
    conn.setRequestProperty("User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    conn.connectTimeout = 15000
    conn.readTimeout = 30000
    conn.outputStream.use { it.write(aesB64.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    if (code !in 200..299) return null
    val respBody = conn.inputStream.bufferedReader().readText()
    val respKey = conn.getHeaderField("encrypt-key")
        ?: return null
    val respAesKey = String(rsaDecrypt(Base64.getDecoder().decode(respKey)), Charsets.UTF_8)
    val json = decryptResponseAes(respBody, respAesKey)
    val node = mapper.readTree(json)
    if (node.get("code")?.asInt() != 200) return null
    return mapper.convertValue(node.get("data"), Map::class.java) as? Map<String, Any?>
}

private fun fetchEpisode(playId: String, ep: Int): EpisodeResult? {
    val data = nsPost("/web/v4/short_play/episode_info",
        mapOf("shortPlayId" to playId, "episodeNo" to ep)) ?: return null
    val lock = data["isLock"] as? Boolean
    if (lock == true) return null
    val pv = data["playVoucher"] as? String
    val subsRaw = data["subtitleList"]
    val subs: List<SubtitleItem>? = if (subsRaw is List<*>) {
        @Suppress("UNCHECKED_CAST")
        mapper.convertValue(subsRaw, List::class.java) as? List<SubtitleItem>
    } else null
    return EpisodeResult(pv, lock, subs)
}

class NetShortProvider : MainAPI() {
    override var name = "NetShort"
    override var mainUrl = "https://netshort.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // صفحة NetShort لا تحوي روابط في HTML الثابت — تُحمَّل من RSC.
    // الصيغة الصحيحة هي /ar/episode/ (مفرد)
    private val cardRegex = Regex("""<a\s+href="(/ar/episode/[^"]+)""")
    // احتياطي: استخرج روابط الحلقات من HTML مباشرة
    private val htmlEpLinkRegex = Regex("""href="(/ar/episode/[^"?#]+)""")
    private val htmlEpTitleRegex = Regex("""title="([^"]+)"""")
    // نستخرج البيانات من حمولة RSC (self.__next_f.push) — الاقتباسات مهرّبة (\")
    // نطابق حتى علامة الاقتباس المهرّبة التالية (\")، مع السماح بأي محارف بينها
    private val nextDataNameUrl = Regex("""\\"shortPlayNameUrl\\"\s*:\s*\\"((?:[^"\\\\]|\\\\.)*)\\"""")
    private val nextDataFullUrl = Regex("""\\"fullEpisodeNameUrl\\"\s*:\s*\\"((?:[^"\\\\]|\\\\.)*)\\"""")
    private val nextDataName = Regex("""\\"shortPlayName\\"\s*:\s*\\"((?:[^"\\\\]|\\\\.)*)\\"""")
    private val nextDataCover = Regex("""\\"shortPlayCover\\"\s*:\s*\\"((?:[^"\\\\]|\\\\.)*)\\"""")
    private val nextDataEpisodes = Regex("""\\"totalEpisode\\"\s*:\s*(\d+)""")
    private val titleInCardRegex = Regex("""title="([^"]+)"""")
    private val posterInCardRegex = Regex("""class="poster[^"]*"\s+src="([^"]+)"""")

    private fun decodeTitle(encoded: String): String =
        try { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }
        catch (e: Exception) { encoded }

    private fun titleFromPath(path: String): String {
        val segments = path.split("-")
        return segments.dropLast(2).joinToString("-")
            .replace(Regex("""^\d+-"""), "")
            .trim()
    }

    // يستخرج قائمة المسلسلات من دفعات self.__next_f.push
    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        // أولاً: ابحث في HTML الحيّ (لو كان فيه <a href>...)
        for (m in cardRegex.findAll(html)) {
            val raw = m.groupValues[1]
            val decoded = decodeTitle(raw)
            val path = decoded.removePrefix("/ar/episode/").substringBefore("?")
            val parts = path.split("-")
            if (parts.size < 3) continue
            val showId = parts.last()
            if (!showId.all { it.isDigit() }) continue
            val title = parts.dropLast(2).joinToString("-").replace(Regex("""^\d+-"""), "").trim()
            if (title.isBlank()) continue
            val url = "$mainUrl$raw"
            if (seen.add(url)) {
                val block = html.substring(m.range.first, m.range.last + 1)
                val poster = posterInCardRegex.find(block)?.groupValues?.get(1)
                results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }
        // ثانياً: احتياطي — ابحث في HTML الحيّ عن روابط /ar/episode/...
        if (results.isEmpty()) {
            val htmlLinks = htmlEpLinkRegex.findAll(html).map { it.groupValues[1] }.toList()
            val htmlTitles = htmlEpTitleRegex.findAll(html).map { it.groupValues[1] }.toList()
            var tIdx = 0
            for (raw in htmlLinks) {
                val decoded = decodeTitle(raw)
                val path = decoded.removePrefix("/ar/episode/").substringBefore("?")
                val parts = path.split("-")
                if (parts.size < 2) continue
                val showId = parts.last()
                if (!showId.all { it.isDigit() }) continue
                val url = "$mainUrl$raw"
                if (seen.add(url)) {
                    val title = htmlTitles.getOrNull(tIdx++)?.takeIf { it.isNotBlank() }
                        ?: parts.dropLast(1).joinToString("-").replace(Regex("""^\d+-"""), "")
                            .takeIf { it.isNotBlank() } ?: path
                    results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries))
                }
            }
        }
        // ثالثاً: ابحث في دفعات RSC (Next.js streaming payload)
        if (results.isEmpty()) {
            val names = nextDataName.findAll(html).map { it.groupValues[1] }.toList()
            val urls = nextDataNameUrl.findAll(html).map { it.groupValues[1] }.toList()
            val covers = nextDataCover.findAll(html).map { it.groupValues[1] }.toList()
            for (i in urls.indices) {
                val rel = urls[i]
                val decoded = decodeTitle(rel)
                val path = decoded.removePrefix("/ar/episode/").substringBefore("?")
                val parts = path.split("-")
                if (parts.size < 2) continue
                val showId = parts.last()
                if (!showId.all { it.isDigit() }) continue
                val url = "$mainUrl$rel"
                if (seen.add(url)) {
                    val title = (names.getOrNull(i) ?: parts.dropLast(1).joinToString("-").replace(Regex("""^\d+-"""), ""))
                        .takeIf { it.isNotBlank() } ?: path
                    val poster = covers.getOrNull(i)
                    results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        }
        return results
    }

    private fun parseCard(html: String, m: MatchResult): SearchResponse? {
        val raw = m.groupValues[1]
        val decoded = decodeTitle(raw)
        val path = decoded.removePrefix("/ar/episode/").substringBefore("?")
        val parts = path.split("-")
        if (parts.size < 3) return null
        val showId = parts.last()
        if (!showId.all { it.isDigit() }) return null
        val title = parts.dropLast(2).joinToString("-").replace(Regex("""^\d+-"""), "").trim()
        if (title.isBlank()) return null
        val block = html.substring(m.range.first, m.range.last + 1)
        val poster = posterInCardRegex.find(block)?.groupValues?.get(1)
        return newTvSeriesSearchResponse(title, "$mainUrl$raw", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val pageNum = if (page <= 1) 1 else page
            val path = if (pageNum == 1) "$mainUrl/ar/all-episodes" else "$mainUrl/ar/all-episodes/page/$pageNum"
            val res = app.get(path, referer = mainUrl).text
            val seen = mutableSetOf<String>()
            // ابحث أولاً في HTML الحيّ
            val items = cardRegex.findAll(res).mapNotNull { m ->
                val item = parseCard(res, m)
                if (item != null && seen.add(item.url)) item else null
            }.toList()
            // ثم في RSC payload
            val finalItems = if (items.isNotEmpty()) items else {
                val rsc = parseShowList(res)
                rsc.filter { seen.add(it.url) }
            }
            if (finalItems.isEmpty()) null else newHomePageResponse(request.name, finalItems)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/ar/search?keywords=$q&type=categories", referer = mainUrl).text
            val seen = mutableSetOf<String>()
            val items = cardRegex.findAll(res).mapNotNull { m ->
                val item = parseCard(res, m)
                if (item != null && seen.add(item.url)) item else null
            }.toList()
            val finalItems = if (items.isNotEmpty()) items else {
                val rsc = parseShowList(res)
                rsc.filter { seen.add(it.url) }
            }
            finalItems
        } catch (e: Exception) {
            null
        }
    }

    private fun extractShortPlayIdFromPath(path: String): String? {
        val parts = path.split("-")
        if (parts.isEmpty()) return null
        val last = parts.last().substringBefore("?").substringBefore("&")
        return last.takeIf { it.all(Char::isDigit) && it.length >= 10 }
    }

    private val epRefRegex = Regex("""<a\s+href="(/ar/episode/[^"]+)"\s+class="video_item[^"]*"""")
    // أنماط إضافية من حمولة RSC لصفحة الحلقة (اقتباسات مهرّبة)
    private val rscInitialEpRegex = Regex("""\\"initialCurrentEpisodeInfo\\"\s*:\s*\{[^}]*?\\"episodeNo\\"\s*:\s*(\d+)""")
    private val rscTotalEpRegex = Regex("""\\"totalEpisode\\"\s*:\s*(\d+)""")

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, referer = mainUrl).text
            val path = url.substringAfter("/ar/episode/").substringBefore("?")
            val shortPlayId = extractShortPlayIdFromPath(path) ?: return null
            val title = run {
                val h1 = Regex("""<h1[^>]*>([^<]+)</h1>""").find(res)?.groupValues?.get(1)?.trim()
                if (!h1.isNullOrBlank()) h1
                else path.split("-").dropLast(2).joinToString("-")
                    .replace(Regex("""^\d+-"""), "").trim().ifBlank { "NetShort" }
            }
            val plot = Regex("""<meta\s+name="description"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)
            val poster = Regex("""<meta\s+property="og:image"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)

            val epLinks = epRefRegex.findAll(res).map { it.groupValues[1] }.toSet()
            val data = nsPost("/web/web/v3/detail_info/episode_info/cascade_label",
                mapOf("shortPlayId" to shortPlayId, "language" to "ar_AE"))
            val videoEps = (data?.get("videoEpisodeInfos") as? List<*>)
            val epNums: List<Int> = if (videoEps != null) {
                @Suppress("UNCHECKED_CAST")
                (mapper.convertValue(videoEps, List::class.java) as? List<Map<String, Any?>>)
                    ?.mapNotNull { (it["episodeNo"] as? Number)?.toInt() }
                    ?: emptyList()
            } else emptyList()

            // احتياطي: استخرج العدد الإجمالي للحلقات من RSC
            val totalFromRsc = rscTotalEpRegex.findAll(res).map { it.groupValues[1].toIntOrNull() ?: 0 }
                .filter { it > 0 }.maxOrNull()
            val initialEpFromRsc = rscInitialEpRegex.find(res)?.groupValues?.get(1)?.toIntOrNull()

            val eps = mutableListOf<Episode>()
            val seen = HashSet<Int>()
            if (epNums.isNotEmpty()) {
                for (n in epNums.sorted()) {
                    if (seen.add(n)) {
                        eps.add(newEpisode("$shortPlayId|$n") {
                            episode = n
                            name = "الحلقة $n"
                        })
                    }
                }
            } else if (totalFromRsc != null && totalFromRsc > 0) {
                for (n in 1..totalFromRsc) {
                    if (seen.add(n)) {
                        eps.add(newEpisode("$shortPlayId|$n") {
                            episode = n
                            name = "الحلقة $n"
                        })
                    }
                }
            } else {
                var n = 1
                for (ep in epLinks) {
                    val epPath = decodeTitle(ep).removePrefix("/ar/episode/").substringBefore("?")
                    val epParts = epPath.split("-")
                    val num = epParts.lastOrNull()?.toIntOrNull() ?: continue
                    if (seen.add(num)) {
                        eps.add(newEpisode("$shortPlayId|$num") {
                            episode = num
                            name = "الحلقة $num"
                        })
                    }
                    n++
                }
            }
            if (eps.isEmpty()) return null
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
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
            val parts = data.split("|")
            if (parts.size != 2) return false
            val playId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false
            val res = fetchEpisode(playId, ep) ?: return false
            val url = res.playVoucher ?: return false
            callback(newExtractorLink(name, "NetShort 720p", url, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = getQualityFromName("720p")
            })
            res.subtitleList?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                if (subUrl.isBlank()) return@forEach
                try { subtitleCallback(newSubtitleFile(sub.language ?: "ar", subUrl)) } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
