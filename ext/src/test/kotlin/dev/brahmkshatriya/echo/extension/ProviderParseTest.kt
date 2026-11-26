package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseParseTest {

    @Test
    fun parsesYrcWordTiming() {
        val yrc = """
            {"t":0,"c":[{"tx":"作词: "}]}
            [18940,3660](18940,270,0)We're (19210,180,0)no (19390,600,0)strangers (19990,270,0)to (20260,2340,0)love
        """.trimIndent()
        val lines = parseYrc(yrc)
        assertEquals(1, lines.size)
        assertEquals(18_940L, lines[0].startMs)
        assertEquals("We're no strangers to love", lines[0].text)
        assertEquals(5, lines[0].words.size)
        assertEquals(18_940L, lines[0].words[0].startMs)
    }

    @Test
    fun parsesLegacyLrcBranch() {
        val lines = parseNetEaseLrc("[00:18.684]First\n[00:22.657]Second")
        assertEquals(2, lines.size)
        assertEquals(18_684L, lines[0].startMs)
    }

    @Test
    fun mergesTranslationByTimestamp() {
        val base = parseNetEaseLrc("[00:18.684]Hello\n[00:22.657]World")
        val merged = mergeByStart(base, parseNetEaseLrc("[00:18.684]你好"))
        assertEquals("Hello\n你好", merged[0].text)
        assertEquals("World", merged[1].text)
    }

    @Test
    fun jsonCreditHeaderIsNotALine() {
        assertTrue(parseYrc("""{"t":0,"c":[{"tx":"credit"}]}""").isEmpty())
    }
}
