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

interface ProxyDataStore {
    suspend fun getProxyConfig(): ProxyConfig
    suspend fun saveProxyConfig(config: ProxyConfig)
    val proxyConfigFlow: Flow<ProxyConfig>
    suspend fun clearProxyConfig()
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
}
