package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.JsonObject

/**
 * NetEase Cloud Music. Cloud search for candidates, then the /api/song/lyric/v1 route which
 * returns YRC (word level), LRC and a translated LRC. No account is required.
 */
internal class NetEaseSource : LyricsSource {

    private val throttle = Throttle(400)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank()) return emptyList()
        throttle.await()
        val keyword = listOf(query.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
        val body = "s=" + Http.encode(keyword) + "&type=1&offset=0&limit=10"
        val text = Http.postForm(SEARCH_URL, body, SEARCH_HEADERS)
        val songs = Http.json(text).obj()?.obj("result")?.arr("songs").objects()

        return songs
            .mapNotNull { song ->
                val id = song.long("id") ?: return@mapNotNull null
                val title = song.str("name") ?: return@mapNotNull null
                val artist = song.arr("ar").objects().mapNotNull { it.str("name") }.joinToString(", ")
                val album = song.obj("al")?.str("name").orEmpty()
                val duration = (song.long("dt") ?: 0L) / 1000
                id to Candidate(title, artist, album, duration)
            }
            .filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(MAX_CANDIDATES)
            .map { (id, candidate) ->
                Lyrics(
                    id = "netease:$id",
                    title = candidate.title,
                    subtitle = "${candidate.artist} • NetEase",
                    lyrics = null,
                    extras = mapOf("id" to id.toString()),
                )
            }
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val id = lyrics.extras["id"] ?: throw LyricsException("NetEase candidate is missing its song id")
        throttle.await()
        val url = LYRIC_URL + "?id=" + Http.encode(id) +
            "&cp=false&lv=0&kv=0&tv=0&yv=0&ytv=0&yrv=0"
        val root = Http.json(Http.get(url, SONG_HEADERS)).obj()
            ?: throw LyricsException("Empty NetEase response")

        val yrc = root.obj("yrc")?.str("lyric")
        val lrc = root.obj("lrc")?.str("lyric")
        val translation = root.obj("tlyric")?.str("lyric")
        val romanization = root.obj("romalrc")?.str("lyric") ?: root.obj("yromalrc")?.str("lyric")

        var lines = if (!yrc.isNullOrBlank()) parseYrc(yrc) else emptyList()
        var wordLevel = lines.any { line -> line.words.size > 1 }
        if (lines.isEmpty() && !lrc.isNullOrBlank()) {
            lines = parseNetEaseLrc(lrc)
            wordLevel = false
        }
        if (lines.isEmpty()) throw LyricsException("NetEase has no lyrics for this song")

        if (settings?.getBoolean(KEY_TRANSLATIONS) == true && !translation.isNullOrBlank()) {
            lines = mergeByStart(lines, parseNetEaseLrc(translation))
        }
        if (settings?.getBoolean(KEY_ROMANIZATION) == true && !romanization.isNullOrBlank()) {
            lines = mergeByStart(lines, parseNetEaseLrc(romanization))
        }

        return lyrics.copy(lyrics = timed(lines, wordLevel))
    }

    companion object {
        const val KEY_TRANSLATIONS = "merge_translations"
        const val KEY_ROMANIZATION = "merge_romanization"

        private const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
        private const val LYRIC_URL = "https://music.163.com/api/song/lyric/v1"
        private const val MAX_CANDIDATES = 6

        private val SEARCH_HEADERS = mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "appver=2.0.2",
            "Content-Type" to "application/x-www-form-urlencoded; charset=utf-8",
        )
        private val SONG_HEADERS = mapOf("Referer" to "https://music.163.com/")

        fun settings(): List<Setting> = listOf(
            SettingSwitch(
                title = "Merge translations",
                key = KEY_TRANSLATIONS,
                summary = "Append the NetEase translation below each line. Echo has no separate translation track.",
                defaultValue = false,
            ),
            SettingSwitch(
                title = "Merge romanization",
                key = KEY_ROMANIZATION,
                summary = "Append the romanized text below each line when NetEase provides it.",
                defaultValue = false,
            ),
        )
    }
}

private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
private val YRC_WORD = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

/** Parses NetEase YRC: JSON credit header lines followed by [start,dur](wordStart,wordDur,0)text. */
internal fun parseYrc(text: String, fallbackEndMs: Long = 0L): List<Line> {
    val lines = mutableListOf<Line>()
    for (raw in text.lines()) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{")) continue
        val match = YRC_LINE.find(trimmed) ?: continue
        val start = match.groupValues[1].toLongOrNull() ?: continue
        val duration = match.groupValues[2].toLongOrNull() ?: 0L
        val body = match.groupValues[3]
        val words = YRC_WORD.findAll(body).mapNotNull { word ->
            val wordStart = word.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val wordDuration = word.groupValues[2].toLongOrNull() ?: 0L
            val content = word.groupValues[3]
            if (content.isEmpty()) null else Word(wordStart, wordStart + maxOf(wordDuration, 1L), content)
        }.toList()
        val textContent = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
        else YRC_WORD.replace(body, "").trim()
        if (textContent.isEmpty()) continue
        lines += Line(start, start + maxOf(duration, 1L), textContent, words)
    }
    return Lrc.withEnds(lines.sortedBy { it.startMs }, fallbackEndMs)
}

/** NetEase returns either JSON credit lines or plain LRC in the same field. */
internal fun parseNetEaseLrc(text: String): List<Line> {
    val withoutCredits = text.lines()
        .filterNot { it.trimStart().startsWith("{") }
        .joinToString("\n")
    return Lrc.parse(withoutCredits)
}

/** Appends the text of the nearest timed line (within 400 ms) below the original. */
internal fun mergeByStart(lines: List<Line>, extra: List<Line>): List<Line> {
    if (extra.isEmpty()) return lines
    return lines.map { line ->
        val match = extra.minByOrNull { kotlin.math.abs(it.startMs - line.startMs) }
        if (match != null && kotlin.math.abs(match.startMs - line.startMs) <= 400 && match.text.isNotBlank()) {
            line.copy(text = line.text + "\n" + match.text)
        } else {
            line
        }
    }
}
