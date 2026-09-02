package com.buildapp.photos.data

data class BackupOptions(
    /** MediaStore bucket ids the user chose to back up. Empty = nothing. */
    val bucketIds: Set<Long>,
    val includeVideos: Boolean = true,
    /** Launcher icons and other system images live in MediaStore too; skip tiny files. */
    val minBytes: Long = 10_000,
)

/**
 * Decides what to send, given what the device has and what the ledger says
 * was already sent. Pure Kotlin so it is unit-tested without a device.
 */
object BackupPlanner {
    fun plan(items: List<DeviceItem>, uploadedIds: Set<Long>, options: BackupOptions): List<DeviceItem> {
        if (options.bucketIds.isEmpty()) return emptyList()
        return items.asSequence()
            .filter { it.bucketId in options.bucketIds }
            .filter { options.includeVideos || !it.isVideo }
            .filter { it.size >= options.minBytes }
            .filter { it.id !in uploadedIds }
            // Newest first: the photo you just took shows up on the server
            // within the first batch, the 2019 backlog trickles in after.
            .sortedWith(compareByDescending<DeviceItem> { it.dateAdded }.thenByDescending { it.id })
            .toList()
    }

    /** Which folder(s) to pre-select the first time backup is switched on. */
    fun defaultBuckets(buckets: List<DeviceBucket>): Set<Long> {
        val camera = buckets.filter { it.name.equals("Camera", ignoreCase = true) }
        val chosen = if (camera.isNotEmpty()) camera else buckets.take(1)
        return chosen.map { it.id }.toSet()
    }
}
