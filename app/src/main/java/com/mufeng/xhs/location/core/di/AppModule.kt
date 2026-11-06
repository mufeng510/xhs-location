package com.mufeng.xhs.location.core.di

import android.content.Context
import android.content.SharedPreferences
import com.mufeng.xhs.location.data.datastore.AppSettingsDataStore
import com.mufeng.xhs.location.data.datastore.DefaultAppSettingsDataStore
import com.mufeng.xhs.location.data.datastore.DefaultLocationDataStore
import com.mufeng.xhs.location.data.datastore.LocationDataStore
import com.mufeng.xhs.location.map.OfflinePlaceDatabase
import com.mufeng.xhs.location.vpn.DefaultProxyDataStore
import com.mufeng.xhs.location.vpn.ProxyDataStore
import com.mufeng.xhs.location.vpn.ProxyFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("vlocation_preferences", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideLocationDataStore(prefs: SharedPreferences): LocationDataStore =
        DefaultLocationDataStore(prefs)

    @Provides
    @Singleton
    fun provideAppSettingsDataStore(prefs: SharedPreferences): AppSettingsDataStore =
        DefaultAppSettingsDataStore(prefs)

    @Provides
    @Singleton
    fun provideLocationAccuracy(): Float = 5.0f

    @Provides
    @Singleton
    fun provideProxyDataStore(prefs: SharedPreferences): ProxyDataStore =
        DefaultProxyDataStore(prefs)

    @Provides
    @Singleton
    fun provideOfflinePlaceDatabase(): OfflinePlaceDatabase =
        OfflinePlaceDatabase

    @Provides
    @Singleton
    fun provideProxyFetcher(): ProxyFetcher =
        ProxyFetcher.create()
}
