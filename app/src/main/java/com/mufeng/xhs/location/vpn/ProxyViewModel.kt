package com.mufeng.xhs.location.vpn

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.data.repository.LocationRepository
import com.mufeng.xhs.location.location.MockLocationManager
import com.mufeng.xhs.location.map.OfflinePlaceDatabase
import com.mufeng.xhs.location.permission.PermissionManager
import com.mufeng.xhs.location.service.LocationForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * VPN 代理 + 模拟定位 ViewModel。
 *
 * 使用流程：
 *  1. 进入页面即自动获取最新代理池（并发探测连通性，按地区归类）
 *  2. 用户选择地区 → 点击节点 → 代理配置与对应 GPS 坐标同时写入
 *  3. 点击「开始模拟」→ VPN 授权 → 同时启动 VPN 代理与 GPS 模拟
 */
@HiltViewModel
class ProxyViewModel @Inject constructor(
    private val proxyDataStore: ProxyDataStore,
    private val proxyFetcher: ProxyFetcher,
    private val locationRepository: LocationRepository,
    private val mockLocationManager: MockLocationManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    companion object {
        private const val PROBE_BUILTIN_CONCURRENCY = 12
    }

    init {
        load()
        checkBuiltinReachability()
    }

    /** 并发实测内置节点是否可访问小红书，结果存入 builtinChecked */
    private fun checkBuiltinReachability() {
        viewModelScope.launch {
            val all = BuiltinProxies.all
            val checked = coroutineScope {
                all.map { proxy ->
                    async(Dispatchers.IO) { proxyFetcher.probeXiaohongshu(proxy) }
                }.awaitAll()
            }
            val updated = all.zip(checked).map { (proxy, ok) -> proxy.copy(xhsReachable = ok) }
            val matched = updated.find {
                it.server == _uiState.value.config.server && it.port == _uiState.value.config.port
            }
            _uiState.value = _uiState.value.copy(
                builtinChecked = updated,
                selectedXhsReachable = matched?.xhsReachable ?: _uiState.value.selectedXhsReachable
            )
        }
    }

    fun load() {
        viewModelScope.launch {
            val config = proxyDataStore.getProxyConfig()
            val locationConfig = locationRepository.getLocationConfig()
            val nasConfig = proxyDataStore.getNasConfig()
            val cache = proxyDataStore.getNasCache()
            val cachedProxies = cache?.let { NasJson.parseProxies(it.proxiesJson) } ?: emptyList()
            val cachedStatus = cache?.statusJson?.let { NasJson.parseStatus(it) }
            val configured = nasConfig.url.isNotBlank() && nasConfig.token.isNotBlank()
            _uiState.value = _uiState.value.copy(
                config = config,
                locationConfig = locationConfig,
                nasUrl = nasConfig.url,
                nasToken = nasConfig.token,
                nasConfigured = configured,
                freshProxies = cachedProxies,
                nasTotal = cachedStatus?.total ?: cachedProxies.size,
                nasReachable = cachedStatus?.reachable ?: cachedProxies.count { it.xhsReachable },
                nasLastUpdateIso = cachedStatus?.lastUpdateIso,
                nasLastFetchMs = cache?.timestampMs ?: 0L,
                isLoading = false
            )
            // 加载完成后再自动获取：缓存先展示，NAS 后台静默刷新
            if (configured) fetchFromNas()
        }
    }

    // ========== NAS 代理池 ==========

    /** 从 NAS 拉取节点列表（缓存先展示）；失败保留缓存并提示 */
    fun fetchFromNas() {
        val state = _uiState.value
        if (state.fetching) return
        val config = currentNasConfig()
        if (config == null) {
            _uiState.value = state.copy(error = "未配置 NAS 代理池服务，请先在下方“NAS 代理池配置”中填写地址和 Token。")
            return
        }
        _uiState.value = state.copy(fetching = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { NasProxyClient.fetch(config.url, config.token) }
            }
            outcome.fold(
                onSuccess = { fetch ->
                    proxyDataStore.saveNasCache(
                        proxiesJson = NasJson.serializeProxies(fetch.proxies),
                        statusJson = NasJson.serializeStatus(fetch.status)
                    )
                    applyFreshProxies(fetch.proxies, fetch.status, null)
                },
                onFailure = { e ->
                    val cached = _uiState.value.freshProxies
                    _uiState.value = _uiState.value.copy(
                        fetching = false,
                        error = "NAS 获取失败：${e.message ?: "网络不可达"}" +
                            if (cached.isNotEmpty()) "（已显示本地缓存的 ${cached.size} 个节点）" else ""
                    )
                }
            )
        }
    }

    private fun currentNasConfig(): NasConfig? {
        val url = _uiState.value.nasUrl.trim().trimEnd('/')
        val token = _uiState.value.nasToken.trim()
        return if (url.isBlank() || token.isBlank()) null else NasConfig(url, token)
    }

    private fun applyFreshProxies(
        fresh: List<BuiltinProxies.BuiltinProxy>,
        status: NasStatus,
        error: String?
    ) {
        val matched = fresh.find {
            it.server == _uiState.value.config.server && it.port == _uiState.value.config.port
        }
        val allUnreachable = fresh.isNotEmpty() && fresh.none { it.xhsReachable }
        _uiState.value = _uiState.value.copy(
            fetching = false,
            freshProxies = fresh,
            nasTotal = status.total,
            nasReachable = status.reachable,
            nasLastUpdateIso = status.lastUpdateIso,
            nasLastFetchMs = System.currentTimeMillis(),
            selectedXhsReachable = matched?.xhsReachable ?: _uiState.value.selectedXhsReachable,
            error = when {
                error != null -> error
                fresh.isEmpty() -> "NAS 代理池为空：服务端正在抓取/实测，请稍后刷新（约 1 分钟）。"
                allUnreachable -> "本批节点均无法访问小红书（免费代理常见），可稍后刷新，或切换内置/手动节点。"
                else -> null
            }
        )
    }

    /** 测试并保存 NAS 配置：连通鉴权通过 → 保存并立即拉取一次 */
    fun testAndSaveNas(url: String, token: String) {
        _uiState.value = _uiState.value.copy(nasTestResult = "测试中…")
        viewModelScope.launch {
            val trimmed = url.trim().trimEnd('/')
            val normalized = NasConfig(trimmed, token.trim())
            val outcome = withContext(Dispatchers.IO) {
                runCatching { NasProxyClient.fetchStatus(normalized.url, normalized.token) }
            }
            outcome.fold(
                onSuccess = {
                    proxyDataStore.saveNasConfig(normalized)
                    _uiState.value = _uiState.value.copy(
                        nasUrl = normalized.url,
                        nasToken = normalized.token,
                        nasConfigured = true,
                        nasTestResult = null
                    )
                    fetchFromNas()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        nasTestResult = "连接失败：${e.message ?: "网络不可达"}"
                    )
                }
            )
        }
    }

    /** 扫码配对：解析 vlocation://setup?url=&token=，保存并拉取。返回 (成功, 消息) */
    fun applyNasQr(raw: String): Pair<Boolean, String> {
        val config = NasQr.parse(raw) ?: return false to "不是有效的 vlocation 配对二维码"
        viewModelScope.launch {
            proxyDataStore.saveNasConfig(config)
        }
        _uiState.value = _uiState.value.copy(
            nasUrl = config.url,
            nasToken = config.token,
            nasConfigured = true,
            nasTestResult = "已导入 NAS 配置，正在拉取节点…"
        )
        fetchFromNas()
        return true to "已保存 NAS 配置"
    }

    /** 清除 NAS 配置与缓存 */
    fun clearNas() {
        viewModelScope.launch {
            proxyDataStore.clearNasConfig()
            proxyDataStore.clearNasCache()
        }
        _uiState.value = _uiState.value.copy(
            nasUrl = "",
            nasToken = "",
            nasConfigured = false,
            nasTotal = 0,
            nasReachable = 0,
            nasLastUpdateIso = null,
            nasLastFetchMs = 0L,
            freshProxies = emptyList(),
            nasTestResult = null,
            error = null
        )
    }

    // ========== 选择代理节点 ==========

    /** 选中一个代理节点：同时填入代理配置与对应地区的 GPS 坐标 */
    fun selectBuiltinProxy(proxy: BuiltinProxies.BuiltinProxy) {
        viewModelScope.launch {
            val newConfig = ProxyConfig(
                server = proxy.server,
                port = proxy.port,
                type = proxy.type,
                enabled = true,
                lastUpdated = System.currentTimeMillis()
            )
            proxyDataStore.saveProxyConfig(newConfig)
            applyLocation(proxy.latitude, proxy.longitude, proxy.city)
            _uiState.value = _uiState.value.copy(
                config = newConfig,
                selectedXhsReachable = proxy.xhsReachable
            )
        }
    }

    /** 手动应用经纬度（坐标输入功能，含范围校验） */
    fun applyCoordinates(latitude: Double, longitude: Double) {
        if (latitude.isNaN() || longitude.isNaN() ||
            latitude < -90.0 || latitude > 90.0 ||
            longitude < -180.0 || longitude > 180.0
        ) {
            _uiState.value = _uiState.value.copy(error = "坐标非法：纬度需在 -90~90，经度需在 -180~180。")
            return
        }
        val city = _uiState.value.locationConfig.city
        viewModelScope.launch {
            applyLocation(latitude, longitude, city)
        }
    }

    /** 城市名搜索（离线库），填充经纬度 */
    fun searchCity(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        _uiState.value = _uiState.value.copy(searchResults = OfflinePlaceDatabase.search(query))
    }

    fun applyOfflinePlace(place: OfflinePlaceDatabase.OfflinePlace) {
        viewModelScope.launch {
            applyLocation(place.lat, place.lon, place.name)
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    private suspend fun applyLocation(latitude: Double, longitude: Double, city: String) {
        val current = locationRepository.getLocationConfig()
        val updated = current.copy(
            latitude = latitude,
            longitude = longitude,
            city = city,
            lastUpdated = System.currentTimeMillis()
        )
        locationRepository.saveLocationConfig(updated)
        _uiState.value = _uiState.value.copy(locationConfig = updated)
    }

    // ========== 代理配置（手动输入） ==========

    fun onServerChanged(server: String) {
        val current = _uiState.value.config
        _uiState.value = _uiState.value.copy(
            config = current.copy(server = server, lastUpdated = System.currentTimeMillis())
        )
    }

    fun onPortChanged(port: String) {
        val current = _uiState.value.config
        val p = port.toIntOrNull() ?: current.port
        _uiState.value = _uiState.value.copy(
            config = current.copy(port = p, lastUpdated = System.currentTimeMillis())
        )
    }

    fun onTypeChanged(type: String) {
        val current = _uiState.value.config
        _uiState.value = _uiState.value.copy(
            config = current.copy(type = type, lastUpdated = System.currentTimeMillis())
        )
    }

    fun commitManualConfig() {
        viewModelScope.launch {
            proxyDataStore.saveProxyConfig(
                _uiState.value.config.copy(enabled = true, lastUpdated = System.currentTimeMillis())
            )
        }
    }

    fun testProxy(server: String, port: Int, type: String) {
        _uiState.value = _uiState.value.copy(error = null, testState = VpnState.CONNECTING)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (type == ProxyType.HTTP) {
                        val client = HttpProxyClient.create(server, port)
                        try {
                            client.connect("www.baidu.com", 80)
                            true
                        } finally {
                            client.close()
                        }
                    } else {
                        val client = Socks5Client.create(server, port)
                        try {
                            client.connect("www.baidu.com", 80)
                            true
                        } finally {
                            client.close()
                        }
                    }
                } catch (e: IOException) {
                    false
                }
            }
            _uiState.value = if (result) {
                _uiState.value.copy(testState = VpnState.CONNECTED, error = null)
            } else {
                _uiState.value.copy(
                    testState = VpnState.FAILED,
                    error = "代理连接失败：无法建立隧道到 $server:$port（www.baidu.com:80）。请检查地址与端口，或更换节点。"
                )
            }
        }
    }

    /** 经代理测试小红书 API 是否可达（TLS 握手 + HTTP GET） */
    fun testXiaohongshu(server: String, port: Int, type: String) {
        _uiState.value = _uiState.value.copy(xhsTestResult = "testing")
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                proxyFetcher.testXiaohongshu(server, port, type)
            }
            _uiState.value = _uiState.value.copy(xhsTestResult = if (ok) "ok" else "fail")
        }
    }

    // ========== 开始 / 停止模拟 ==========

    /**
     * 开始模拟。VPN 系统授权由 UI 层用 [VpnService.prepare] 发起：
     *  - 已授权过（prepare 返回 null）→ UI 直接调用本方法；
     *  - 未授权 → UI 弹系统授权框，授权结果 OK 后调用本方法（或 [startSimulationAfterPermission]，二者等价）。
     */
    fun startSimulation() {
        startSimulationAfterPermission()
    }

    /** 启动 GPS 模拟 + VPN 代理（含坐标与 GPS 权限校验） */
    fun startSimulationAfterPermission() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            if (config.latitude == 0.0 && config.longitude == 0.0) {
                _uiState.value = _uiState.value.copy(error = "请先选择代理节点（自动填入经纬度），或手动输入坐标。")
                return@launch
            }
            val state = PermissionManager.checkPermissionState(context, mockLocationManager)
            if (!state.isAllGranted) {
                _uiState.value = _uiState.value.copy(showPermissionGuide = true)
                return@launch
            }

            val lat = config.latitude
            val lon = config.longitude
            val acc = config.accuracy
            val address = "%.4f, %.4f".format(lat, lon)

            locationRepository.saveLocationConfig(
                config.copy(enabled = true, lastUpdated = System.currentTimeMillis())
            )
            context.startService(
                LocationForegroundService.createStartIntent(context, lat, lon, acc, address)
            )

            proxyDataStore.saveProxyConfig(
                _uiState.value.config.copy(enabled = true, lastUpdated = System.currentTimeMillis())
            )
            context.startService(VpnProxyService.createStartIntent(context))

            _uiState.value = _uiState.value.copy(
                isRunning = true,
                vpnRunning = true,
                showPermissionGuide = false,
                error = null
            )
        }
    }

    /** VPN 授权被用户拒绝 */
    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            vpnRunning = false,
            error = "已拒绝 VPN 授权，无法启动代理。可在设置中允许后重试。"
        )
    }

    /** 坐标未设置提示（UI 层点击开始模拟但坐标为 0） */
    fun reportMissingCoordinates() {
        _uiState.value = _uiState.value.copy(
            error = "请先选择代理节点（自动填入经纬度），或手动输入坐标。"
        )
    }

    /** 关闭 GPS 权限引导框 */
    fun clearPermissionGuide() {
        _uiState.value = _uiState.value.copy(showPermissionGuide = false)
    }

    fun stopSimulation() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            locationRepository.saveLocationConfig(
                config.copy(enabled = false, lastUpdated = System.currentTimeMillis())
            )
        }
        context.startService(LocationForegroundService.createStopIntent(context))
        context.startService(VpnProxyService.createStopIntent(context))
        _uiState.value = _uiState.value.copy(isRunning = false, vpnRunning = false)
    }

    data class UiState(
        val config: ProxyConfig = ProxyConfig(),
        val locationConfig: LocationConfig = LocationConfig(),
        val isLoading: Boolean = true,
        val fetching: Boolean = false,
        val error: String? = null,
        val freshProxies: List<BuiltinProxies.BuiltinProxy> = emptyList(),
        val builtinChecked: List<BuiltinProxies.BuiltinProxy> = BuiltinProxies.all,
        val isRunning: Boolean = false,
        val vpnRunning: Boolean = false,
        val showPermissionGuide: Boolean = false,
        val testState: VpnState = VpnState.DISCONNECTED,
        val selectedXhsReachable: Boolean? = null,
        val xhsTestResult: String? = null,
        val searchResults: List<OfflinePlaceDatabase.OfflinePlace> = emptyList(),
        /** NAS 代理池服务配置与状态（输入框直接绑 uiState，无需本地变量） */
        val nasUrl: String = "",
        val nasToken: String = "",
        val nasConfigured: Boolean = false,
        val nasLastUpdateIso: String? = null,
        val nasLastFetchMs: Long = 0L,
        val nasTotal: Int = 0,
        val nasReachable: Int = 0,
        /** “测试并保存”的结果提示（null = 空闲） */
        val nasTestResult: String? = null
    )
}
