package com.buildapp.photos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentHashTest {
    @Test
    fun `matches the server's quick_hash vectors`() {
        // Same vectors as server/tests/test_api.py ContentHashTests.
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        assertEquals("c0cf07b6c7a6aeb7bf336a2af9c5dc04364500e6a65b1249eb5b2e78be8ccf3e", ContentHash.of(big))
        assertEquals("a5c35e5d848a9c891a479ebaeb7083b71e8bee487416b56f2333dca466e9f7e6", ContentHash.of("hello chitra".toByteArray()))
    }

    @Test
    fun `tail never overlaps the head`() {
        // A file just over the head size: tail starts at HEAD, not size - TAIL.
        val bytes = ByteArray(ContentHash.HEAD + 10) { (it % 7).toByte() }
        val head = bytes.copyOfRange(0, ContentHash.HEAD)
        val tail = bytes.copyOfRange(ContentHash.HEAD, bytes.size)
        assertEquals(ContentHash.of(bytes.size.toLong(), head, tail), ContentHash.of(bytes))
    }
}
