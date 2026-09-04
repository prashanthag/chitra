package com.buildapp.photos.api

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    @Test
    fun `thumb url carries the edit version and tolerates a trailing slash`() {
        // No version known: unversioned (server gives it a short max-age).
        assertEquals("http://h:1/api/media/abc/thumb", Urls.thumb("http://h:1", "abc"))
        assertEquals("http://h:1/api/media/abc/thumb?v=0", Urls.thumb("http://h:1", "abc", 0))
        assertEquals("http://h:1/api/media/abc/thumb?v=3", Urls.thumb("http://h:1/", "abc", 3))
        assertEquals("http://h:1/api/media/abc/thumb?v=3&w=32", Urls.thumb("http://h:1", "abc", 3, w = 32))
        assertEquals("http://h:1/api/media/abc/thumb?w=1024", Urls.thumb("http://h:1", "abc", w = 1024))
        assertEquals("http://h:1/api/media/abc/preview?v=2", Urls.preview("http://h:1", "abc", 2))
    }

    @Test
    fun `other urls keep their shape`() {
        assertEquals("http://h:1/api/media/abc/full?as=jpeg", Urls.full("http://h:1", "abc", asJpeg = true))
        assertEquals("http://h:1/api/media/abc/stream.mp4", Urls.stream("http://h:1/", "abc"))
        assertEquals("http://h:1/api/media/abc/play?codecs=h264,hevc", Urls.play("http://h:1", "abc"))
        assertEquals("http://h:1/s/tok", Urls.shareLink("http://h:1", "tok"))
    }
}
