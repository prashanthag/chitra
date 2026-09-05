package com.buildapp.photos.data

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The server's quick content hash (app.py quick_hash), bit for bit:
 * SHA-256 of the byte size (8 bytes, big-endian), the first 1 MiB and the
 * last 64 KiB (tail starts at max(1 MiB, size - 64 KiB), so it never
 * overlaps the head). Exact copies match regardless of file name; the cost
 * is one small read per file, so the pre-flight check can be by content.
 */
object ContentHash {
    const val HEAD = 1 shl 20
    const val TAIL = 64 shl 10

    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(size).array())
        md.update(head)
        if (size > HEAD) md.update(tail)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hash of a whole in-memory file (tests, small files). */
    fun of(bytes: ByteArray): String {
        val size = bytes.size.toLong()
        val head = bytes.copyOfRange(0, minOf(HEAD, bytes.size))
        val tailStart = maxOf(HEAD.toLong(), size - TAIL).toInt()
        val tail = if (size > HEAD) bytes.copyOfRange(tailStart, minOf(bytes.size, tailStart + TAIL)) else ByteArray(0)
        return of(size, head, tail)
    }

    /**
     * Hash of a content:// item; null when it cannot be opened. Always uses
     * the file's real size: MediaStore's SIZE column can be stale (a file
     * rewritten in place, a still-finishing download), and since the size is
     * part of the hash a stale value would never match the server's.
     */
    @Suppress("UNUSED_PARAMETER")
    fun of(context: Context, uri: Uri, sizeHint: Long = -1): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val ch = input.channel
                val size = ch.size()
                val head = ByteArray(minOf(HEAD.toLong(), size).toInt())
                readFully(ch, head, 0L)
                val tail = if (size > HEAD) {
                    val start = maxOf(HEAD.toLong(), size - TAIL)
                    ByteArray(minOf(TAIL.toLong(), size - start).toInt()).also { readFully(ch, it, start) }
                } else ByteArray(0)
                of(size, head, tail)
            }
        }
    }.getOrNull()

    private fun readFully(ch: java.nio.channels.FileChannel, dst: ByteArray, at: Long) {
        val buf = ByteBuffer.wrap(dst)
        var pos = at
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
    }
}
