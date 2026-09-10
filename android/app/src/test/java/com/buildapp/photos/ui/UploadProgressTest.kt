package com.buildapp.photos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadProgressTest {
    @Test
    fun `summary mentions duplicates and failures only when present`() {
        assertEquals("Uploaded 3 of 3", UploadProgress(3, 3, 3, 0, 0, false).summary)
        assertEquals("Uploaded 2 of 4, 1 already in library, 1 failed", UploadProgress(4, 4, 2, 1, 1, false).summary)
    }

    @Test
    fun `filter chips carry human labels and parse by name`() {
        assertEquals("Uploads", Filter.UPLOADS.label)
        assertEquals(Filter.UPLOADS, Filter.byName("uploads"))
        assertEquals(null, Filter.byName("nope"))
    }
}
