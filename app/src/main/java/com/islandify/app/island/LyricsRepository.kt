package com.islandify.app.island

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single lyric line. timeMs is real LRC timing or an explicitly estimated fallback. */
data class LyricLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val lines: List<LyricLine>,
    val estimated: Boolean = false,
    val source: String = ""
)

object LyricsRepository {
    private const val USER_AGENT = "Islandify/1.2 (https://github.com/ysufimanuel/Islandify)"

    suspend fun find(title: String, artist: String, durationMs: Long): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || artist.isBlank()) return@withContext null

            // 1) Search LRCLIB with title + artist.
            val full = runCatching {
                bestRecord(searchLrclib(title, artist), title, artist, durationMs)
            }.getOrNull()
            if (full != null) return@withContext full

            // 2) Retry by title only. Artist metadata from media apps is often noisy.
            val titleOnly = runCatching {
                bestRecord(searchLrclib(title, null), title, null, durationMs)
            }.getOrNull()
            if (titleOnly != null) return@withContext titleOnly

            // 3) Plain-lyrics fallback. Timing is estimated and labeled as such in the UI.
            runCatching {
                val url = "https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}"
                val json = JSONObject(request(url))
                val plain = json.optString("lyrics").trim()
                if (plain.isBlank()) null else {
                    val lines = plain.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) null else LyricsResult(
                        lines = estimate(lines, durationMs),
                        estimated = true,
                        source = "Lyrics.ovh"
                    )
                }
            }.getOrNull()
        }

    private fun searchLrclib(title: String, artist: String?): List<JSONObject> {
        val params = buildList {
            add("track_name" to title)
            if (!artist.isNullOrBlank()) add("artist_name" to artist)
        }.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val arr = JSONArray(request("https://lrclib.net/api/search?$params"))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun bestRecord(
        records: List<JSONObject>,
        title: String,
        artist: String?,
        durationMs: Long
    ): LyricsResult? {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist.orEmpty())
        return records
            .filter { normalize(it.optString("trackName").ifBlank { it.optString("name") }) == wantedTitle }
            .sortedBy { record ->
                val foundArtist = normalize(record.optString("artistName"))
                val artistPenalty = if (wantedArtist.isBlank() || foundArtist == wantedArtist ||
                    foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist)
                ) 0L else 1_000_000L
                val foundDuration = (record.optDouble("duration", 0.0) * 1000).toLong()
                artistPenalty + if (durationMs > 0 && foundDuration > 0) kotlin.math.abs(foundDuration - durationMs) else 0L
            }
            .firstNotNullOfOrNull { parseRecord(it, durationMs) }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun parseRecord(record: JSONObject, durationMs: Long): LyricsResult? {
        if (record.optBoolean("instrumental")) return null
        val synced = record.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) return LyricsResult(parsed, false, "LRCLIB")
        }
        val plain = record.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val lines = plain.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return LyricsResult(estimate(lines, durationMs), true, "LRCLIB")
    }

    private fun parseLrc(raw: String): List<LyricLine> {
        val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        return raw.lineSequence().flatMap { line ->
            val matches = regex.findAll(line).toList()
            val text = line.replace(regex, "").trim()
            if (matches.isEmpty() || text.isBlank()) emptySequence()
            else matches.asSequence().map { m ->
                val minute = m.groupValues[1].toLong()
                val second = m.groupValues[2].toLong()
                val fraction = m.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    3 -> fraction.toLong()
                    else -> 0L
                }
                LyricLine((minute * 60 + second) * 1000 + millis, text)
            }
        }.sortedBy { it.timeMs }.toList()
    }

    private fun estimate(lines: List<String>, durationMs: Long): List<LyricLine> {
        val safeDuration = max(durationMs, lines.size * 2500L)
        val step = safeDuration / lines.size
        return lines.mapIndexed { index, text -> LyricLine(index * step, text) }
    }

    private fun request(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = android.net.Uri.encode(value)
}
