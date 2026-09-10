package com.buildapp.photos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.buildapp.photos.api.Auth
import com.buildapp.photos.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class PhotosApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // The session must be in place before the first image or API call.
        Auth.token = runBlocking { SettingsRepository(this@PhotosApplication).sessionToken.first() }
    }

    /** Coil's image loader sends the session header so thumbnails work once accounts exist. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { OkHttpClient.Builder().addInterceptor(Auth.interceptor).build() }
            .crossfade(true)
            .build()
}
