package com.powermediaplayer.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RssFeedParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
        <channel>
          <title>Test Show</title>
          <description>Demo description.</description>
          <itunes:image href="https://cdn.example.com/art.jpg"/>
          <item>
            <title>Episode 1</title>
            <guid>ep-1</guid>
            <pubDate>Mon, 01 May 2026 09:00:00 +0000</pubDate>
            <itunes:duration>00:42:13</itunes:duration>
            <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="1234"/>
          </item>
          <item>
            <title>Episode 2</title>
            <guid>ep-2</guid>
            <pubDate>Mon, 08 May 2026 09:00:00 +0000</pubDate>
            <itunes:duration>1830</itunes:duration>
            <enclosure url="https://cdn.example.com/ep2.mp3" type="audio/mpeg" length="2345"/>
          </item>
        </channel>
        </rss>
    """.trimIndent()

    @Test fun parses_show_metadata_and_episodes() {
        val parser = RssFeedParser()
        val parsed = try {
            parser.parse("https://feed.example.com/rss", sample)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
        if (parsed == null) {
            System.err.println("Parser returned null for sample: ${sample.take(200)}")
        }
        assertNotNull(parsed)
        val (show, episodes) = parsed!!
        assertEquals("Test Show", show.title)
        assertEquals("Demo description.", show.description)
        assertEquals("https://cdn.example.com/art.jpg", show.artworkUrl)
        assertEquals(2, episodes.size)
        assertEquals("Episode 1", episodes[0].title)
        assertEquals("https://cdn.example.com/ep1.mp3", episodes[0].audioUrl)
        // 00:42:13 = 42 * 60 + 13 = 2533s
        assertEquals(2533L, episodes[0].durationS)
        // Raw seconds form parses too.
        assertEquals(1830L, episodes[1].durationS)
    }

    @Test fun rejects_garbage() {
        val parser = RssFeedParser()
        // Genuine garbage that isn't even XML — parser returns null.
        assertNull(parser.parse("https://x", "not xml at all <<>>"))
    }

    @Test fun episodes_without_enclosure_are_skipped() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>X</title>
              <item><title>Has audio</title><guid>a</guid>
                <enclosure url="https://x/a.mp3" type="audio/mpeg"/></item>
              <item><title>No audio</title><guid>b</guid></item>
            </channel></rss>
        """.trimIndent()
        val parser = RssFeedParser()
        val parsed = parser.parse("u", xml)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.second.size)
        assertEquals("Has audio", parsed.second[0].title)
    }
}
