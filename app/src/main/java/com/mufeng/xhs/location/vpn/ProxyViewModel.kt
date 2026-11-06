package com.mufeng.xhs.location.vpn

import android.content.Context
import android.util.Log
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
        fetchFreshProxies()
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
            _uiState.value = _uiState.value.copy(
                config = config,
                locationConfig = locationConfig,
                isLoading = false
            )
        }
    }

    // ========== 自动获取代理 ==========

    fun fetchFreshProxies() {
        if (_uiState.value.fetching) return
        _uiState.value = _uiState.value.copy(fetching = true, error = null)
        viewModelScope.launch {
            try {
                val fresh = proxyFetcher.fetchFreshProxies()
                val matched = fresh.find {
                    it.server == _uiState.value.config.server && it.port == _uiState.value.config.port
                }
                val allUnreachable = fresh.isNotEmpty() && fresh.none { it.xhsReachable }
                _uiState.value = _uiState.value.copy(
                    fetching = false,
                    freshProxies = fresh,
                    selectedXhsReachable = matched?.xhsReachable ?: _uiState.value.selectedXhsReachable,
                    error = when {
                        fresh.isEmpty() -> "未能获取到可用代理，已保留内置节点，可继续选择使用。"
                        allUnreachable -> "本批自动获取的节点均无法访问小红书（免费代理常见），可刷新重试，或切换内置/手动输入节点。"
                        else -> null
                    }
                )
            } catch (e: Exception) {
                Log.e("ProxyViewModel", "fetchFreshProxies failed", e)
                _uiState.value = _uiState.value.copy(
                    fetching = false,
                    error = "获取代理失败：${e.message ?: "网络不可达"}，已保留内置节点，可继续选择使用。"
                )
            }
        }
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
        val searchResults: List<OfflinePlaceDatabase.OfflinePlace> = emptyList()
    )
}
