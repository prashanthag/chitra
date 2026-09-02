package com.buildapp.photos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Which MediaStore rows have been sent to which server. Keyed by MediaStore
 * _ID + server, so switching servers or adding a folder later backfills
 * correctly (the old single "last seen id" cursor could not do either).
 */
class UploadLedger(context: Context) : SQLiteOpenHelper(context.applicationContext, "backup_ledger.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE uploaded (
                 media_id INTEGER NOT NULL,
                 server TEXT NOT NULL,
                 name TEXT,
                 size INTEGER,
                 server_id TEXT,
                 duplicate INTEGER NOT NULL DEFAULT 0,
                 uploaded_at INTEGER NOT NULL,
                 PRIMARY KEY (media_id, server))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun uploadedIds(server: String): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM uploaded WHERE server = ?", arrayOf(server)).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun markUploaded(server: String, item: DeviceItem, serverId: String?, duplicate: Boolean) {
        val v = ContentValues().apply {
            put("media_id", item.id)
            put("server", server)
            put("name", item.name)
            put("size", item.size)
            put("server_id", serverId)
            put("duplicate", if (duplicate) 1 else 0)
            put("uploaded_at", System.currentTimeMillis() / 1000)
        }
        writableDatabase.insertWithOnConflict("uploaded", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(server: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM uploaded WHERE server = ?", arrayOf(server)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun clear(server: String) {
        writableDatabase.delete("uploaded", "server = ?", arrayOf(server))
    }
}
