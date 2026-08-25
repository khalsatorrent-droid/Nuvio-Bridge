package com.example

import com.example.data.model.RawPluginStream
import com.example.data.model.StreamQuality
import com.example.engine.StreamFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFormatterTest {

    @Test
    fun testQualityDetection() {
        assertEquals(StreamQuality.UHD_4K, StreamFormatter.detectQuality("Movie Title 4K UHD HDR"))
        assertEquals(StreamQuality.FHD_1080P, StreamFormatter.detectQuality("VidSrc 1080p stream"))
        assertEquals(StreamQuality.HD_720P, StreamFormatter.detectQuality("FastCDN 720p HD"))
        assertEquals(StreamQuality.SD_480P, StreamFormatter.detectQuality("Standard 480p SD"))
    }

    @Test
    fun testStreamSorting() {
        val rawStreams = listOf(
            RawPluginStream(name = "VidSrc", title = "720p stream", url = "http://url1", quality = "720p"),
            RawPluginStream(name = "AutoEmbed", title = "4K VIP stream", url = "http://url2", quality = "4K"),
            RawPluginStream(name = "SuperStream", title = "1080p stream", url = "http://url3", quality = "1080p")
        )

        val formatted = StreamFormatter.formatAndSortStreams(rawStreams, sortByQuality = true)

        assertEquals(3, formatted.size)
        assertTrue(formatted[0].name.contains("4K") || formatted[0].title.contains("4K"))
        assertTrue(formatted[1].name.contains("1080p") || formatted[1].title.contains("1080p"))
        assertTrue(formatted[2].name.contains("720p") || formatted[2].title.contains("720p"))
    }
}
