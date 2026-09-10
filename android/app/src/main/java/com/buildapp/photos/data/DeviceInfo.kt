package com.buildapp.photos.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/** How this phone identifies itself on uploads (source device + camera fallback). */
object DeviceInfo {
    /** The user-visible device name ("Galaxy Z Fold6"), falling back to the model number. */
    fun name(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun make(): String = Build.MANUFACTURER
}
