package com.mufeng.xhs.location.vpn

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

const val KEY_PROXY_SERVER = "proxy_server"
const val KEY_PROXY_PORT = "proxy_port"
const val KEY_PROXY_TYPE = "proxy_type"
const val KEY_PROXY_ENABLED = "proxy_enabled"
const val KEY_PROXY_LAST_UPDATED = "proxy_last_updated"
const val KEY_NAS_URL = "nas_url"
const val KEY_NAS_TOKEN = "nas_token"
const val KEY_NAS_CACHE_PROXIES = "nas_cache_proxies"
const val KEY_NAS_CACHE_STATUS = "nas_cache_status"
const val KEY_NAS_CACHE_AT = "nas_cache_at"

/** NAS 代理池服务配置（用户在设置页填写或扫码导入） */
data class NasConfig(val url: String, val token: String)

/** NAS 缓存：上次成功拉取的原始 JSON + 本地时间戳 */
data class NasCache(val proxiesJson: String, val statusJson: String, val timestampMs: Long)

interface ProxyDataStore {
    suspend fun getProxyConfig(): ProxyConfig
    suspend fun saveProxyConfig(config: ProxyConfig)
    val proxyConfigFlow: Flow<ProxyConfig>
    suspend fun clearProxyConfig()

    /** NAS 配置；未配置时 url/token 均为空串 */
    suspend fun getNasConfig(): NasConfig
    suspend fun saveNasConfig(config: NasConfig)
    suspend fun clearNasConfig()

    /** NAS 缓存读写；未缓存时 getNasCache() 返回 null */
    suspend fun getNasCache(): NasCache?
    suspend fun saveNasCache(proxiesJson: String, statusJson: String)
    suspend fun clearNasCache()
}

class DefaultProxyDataStore(
    private val prefs: SharedPreferences
) : ProxyDataStore {

    override suspend fun getProxyConfig(): ProxyConfig {
        return ProxyConfig(
            server = prefs.getString(KEY_PROXY_SERVER, "") ?: "",
            port = prefs.getInt(KEY_PROXY_PORT, 1080),
            type = prefs.getString(KEY_PROXY_TYPE, ProxyType.SOCKS5) ?: ProxyType.SOCKS5,
            enabled = prefs.getBoolean(KEY_PROXY_ENABLED, false),
            lastUpdated = prefs.getLong(KEY_PROXY_LAST_UPDATED, 0L)
        )
    }

    override suspend fun saveProxyConfig(config: ProxyConfig) {
        val editor = prefs.edit()
        editor.putString(KEY_PROXY_SERVER, config.server)
        editor.putInt(KEY_PROXY_PORT, config.port)
        editor.putString(KEY_PROXY_TYPE, config.type)
        editor.putBoolean(KEY_PROXY_ENABLED, config.enabled)
        editor.putLong(KEY_PROXY_LAST_UPDATED, config.lastUpdated)
        editor.apply()
    }

    override val proxyConfigFlow: Flow<ProxyConfig>
        get() = flow {
            while (true) {
                emit(getProxyConfig())
                delay(1000)
            }
        }

    override suspend fun clearProxyConfig() {
        val editor = prefs.edit()
        editor.remove(KEY_PROXY_SERVER)
        editor.remove(KEY_PROXY_PORT)
        editor.remove(KEY_PROXY_TYPE)
        editor.remove(KEY_PROXY_ENABLED)
        editor.remove(KEY_PROXY_LAST_UPDATED)
        editor.apply()
    }

    override suspend fun getNasConfig(): NasConfig =
        NasConfig(
            url = prefs.getString(KEY_NAS_URL, "") ?: "",
            token = prefs.getString(KEY_NAS_TOKEN, "") ?: ""
        )

    override suspend fun saveNasConfig(config: NasConfig) {
        prefs.edit()
            .putString(KEY_NAS_URL, config.url.trim().trimEnd('/'))
            .putString(KEY_NAS_TOKEN, config.token.trim())
            .apply()
    }

    override suspend fun clearNasConfig() {
        prefs.edit()
            .remove(KEY_NAS_URL)
            .remove(KEY_NAS_TOKEN)
            .apply()
    }

    override suspend fun getNasCache(): NasCache? {
        val proxies = prefs.getString(KEY_NAS_CACHE_PROXIES, null) ?: return null
        val status = prefs.getString(KEY_NAS_CACHE_STATUS, "") ?: ""
        val at = prefs.getLong(KEY_NAS_CACHE_AT, 0L)
        return NasCache(proxies, status, at)
    }

    override suspend fun saveNasCache(proxiesJson: String, statusJson: String) {
        prefs.edit()
            .putString(KEY_NAS_CACHE_PROXIES, proxiesJson)
            .putString(KEY_NAS_CACHE_STATUS, statusJson)
            .putLong(KEY_NAS_CACHE_AT, System.currentTimeMillis())
            .apply()
    }

    override suspend fun clearNasCache() {
        prefs.edit()
            .remove(KEY_NAS_CACHE_PROXIES)
            .remove(KEY_NAS_CACHE_STATUS)
            .remove(KEY_NAS_CACHE_AT)
            .apply()
    }
}