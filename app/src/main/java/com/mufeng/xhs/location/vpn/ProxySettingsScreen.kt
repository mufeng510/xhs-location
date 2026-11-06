package com.mufeng.xhs.location.vpn

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.permission.PermissionManager

/**
 * 主界面：VPN 代理 + 模拟定位一体化。
 *
 * 流程：
 *  1. 进入页面即自动获取最新代理（可一键刷新），按地区归类展示；
 *  2. 或从“内置节点”按地区选择；
 *  3. 点击节点 → 代理配置与对应 GPS 经纬度自动填入；
 *  4. 点击“开始模拟”→ 首次使用弹出系统 VPN 授权框 → 同时启动 GPS 模拟与代理 VPN。
 */
@Composable
fun ProxySettingsScreen() {
    val context = LocalContext.current
    val viewModel = hiltViewModel<ProxyViewModel>()
    val uiState = viewModel.uiState.collectAsState().value
    val config = uiState.config
    val locConfig = uiState.locationConfig

    // VPN 授权 launcher：prepare 返回 Intent 时先弹系统授权框
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startSimulationAfterPermission()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    fun onStartClicked() {
        if (locConfig.latitude == 0.0 && locConfig.longitude == 0.0) {
            viewModel.reportMissingCoordinates()
            return
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            viewModel.startSimulation()
        } else {
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    // 手动输入框状态（与 config 同步）
    var serverText by remember { mutableStateOf(config.server) }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var proxyType by remember { mutableStateOf(config.type) }
    LaunchedEffect(config.server, config.port, config.type) {
        serverText = config.server
        portText = if (config.port > 0) config.port.toString() else ""
        proxyType = config.type
    }

    // 手动坐标输入框状态
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    LaunchedEffect(locConfig.latitude, locConfig.longitude) {
        if (locConfig.latitude != 0.0 || locConfig.longitude != 0.0) {
            latText = locConfig.latitude.toString()
            lonText = locConfig.longitude.toString()
        }
    }

    // 城市搜索输入框
    var cityQuery by remember { mutableStateOf("") }

    var selectedRegion by remember { mutableStateOf("北京") }
    var builtinExpanded by remember { mutableStateOf(true) }
    /** 自动获取列表：每个地区默认最多展示 3 个节点 */
    var expandedCities by remember { mutableStateOf(setOf<String>()) }

    val fresh = uiState.freshProxies
    val freshXhsCount = fresh.count { it.xhsReachable }

    // 内置节点：使用实测过的 builtinChecked 列表（带小红书可达标记）
    val builtinList = uiState.builtinChecked
    fun regionMatches(region: String, proxy: BuiltinProxies.BuiltinProxy): Boolean = when (region) {
        "北京" -> proxy.city == "北京"
        "上海" -> proxy.city == "上海"
        "广东（广州/深圳）" -> proxy.city == "广州" || proxy.city == "深圳" || proxy.city == "广东"
        "浙江（杭州）" -> proxy.city == "杭州" || proxy.city == "浙江"
        "四川（成都）" -> proxy.city == "成都" || proxy.city == "四川"
        else -> proxy.city !in setOf("北京", "上海", "广州", "深圳", "广东", "杭州", "浙江", "成都", "四川")
    }
    val regionDisplayOrder = listOf("北京", "上海", "广东（广州/深圳）", "浙江（杭州）", "四川（成都）", "其他地区")
    val regionGroups: List<Pair<String, List<BuiltinProxies.BuiltinProxy>>> =
        regionDisplayOrder.map { region -> region to builtinList.filter { regionMatches(region, it) } }
    val regionProxies = regionGroups.firstOrNull { it.first == selectedRegion }?.second ?: emptyList()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // GPS 权限缺失引导框
    if (uiState.showPermissionGuide) {
        AlertDialog(
            onDismissRequest = { viewModel.clearPermissionGuide() },
            title = { Text(text = stringResource(R.string.permission_title)) },
            text = { Text(text = stringResource(R.string.permission_guide_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    PermissionManager.openDeveloperSettings(context)
                    viewModel.clearPermissionGuide()
                }) {
                    Text(text = stringResource(R.string.btn_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearPermissionGuide() }) {
                    Text(text = "知道了")
                }
            }
        )
    }

    fun isSelected(proxy: BuiltinProxies.BuiltinProxy): Boolean =
        config.server == proxy.server && config.port == proxy.port

    @Composable
    fun ProxyRow(proxy: BuiltinProxies.BuiltinProxy, showXhs: Boolean = false) {
        val selected = isSelected(proxy)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.selectBuiltinProxy(proxy) }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = proxy.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${proxy.server}:${proxy.port}  ${proxy.type.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (proxy.latitude != 0.0 || proxy.longitude != 0.0) {
                    Text(
                        text = "GPS: ${proxy.city} (${fmtCoord(proxy.latitude)}, ${fmtCoord(proxy.longitude)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (showXhs) {
                    Text(
                        text = stringResource(if (proxy.xhsReachable) R.string.vpn_xhs_ok else R.string.vpn_xhs_fail),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (proxy.xhsReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Text(
                        text = stringResource(R.string.vpn_selected),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        HorizontalDivider()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== 状态与开始/停止模拟 ==========
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vpn_status_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (uiState.isRunning) R.string.vpn_status_sim_running
                            else R.string.vpn_status_sim_idle
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.isRunning) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (locConfig.latitude != 0.0 || locConfig.longitude != 0.0) {
                            "坐标：${fmtCoord(locConfig.latitude)}, ${fmtCoord(locConfig.longitude)}" +
                                if (locConfig.city.isNotBlank()) "（${locConfig.city}）" else ""
                        } else {
                            "坐标：未设置"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (config.server.isNotBlank()) {
                            "代理：${config.server}:${config.port}  ${config.type.uppercase()}"
                        } else {
                            "代理：未选择"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (config.server.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                when (uiState.selectedXhsReachable) {
                                    true -> R.string.vpn_xhs_current_ok
                                    false -> R.string.vpn_xhs_current_fail
                                    null -> R.string.vpn_xhs_current_unknown
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (uiState.selectedXhsReachable) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    uiState.error?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { if (uiState.isRunning) viewModel.stopSimulation() else onStartClicked() },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.isRunning) R.string.vpn_btn_stop_simulation
                                else R.string.vpn_btn_start_simulation
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.vpn_publish_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ========== 自动获取代理（进入页面自动加载） ==========
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_auto_fetch_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(
                            onClick = { viewModel.fetchFreshProxies() },
                            enabled = !uiState.fetching && uiState.selectedSources.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    if (uiState.fetching) R.string.vpn_btn_fetching
                                    else R.string.vpn_btn_fetch
                                )
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.vpn_auto_fetch_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // ========== 代理源选择器 ==========
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_sources_title) +
                                "（已选 ${uiState.selectedSources.size}/${ProxySourceRegistry.all.size}）",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row {
                            TextButton(
                                onClick = { viewModel.selectAllSources() },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text(stringResource(R.string.vpn_sources_select_all)) }
                            TextButton(
                                onClick = { viewModel.resetSources() },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text(stringResource(R.string.vpn_sources_reset)) }
                        }
                    }
                    ProxySourceRegistry.all.forEach { source ->
                        val checked = source.id in uiState.selectedSources
                        val status = uiState.sourceStatus[source.id]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleSource(source.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleSource(source.id) }
                            )
                            Column(modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = source.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                when (status) {
                                    is SourceFetchStatus.Ok -> Text(
                                        text = stringResource(R.string.vpn_source_ok_count, status.candidateCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    is SourceFetchStatus.Failed -> Text(
                                        text = stringResource(R.string.vpn_source_failed, status.message),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    else -> Unit
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.vpn_sources_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.fetching) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val fresh = uiState.freshProxies
                    if (fresh.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.vpn_fresh_summary, fresh.size, freshXhsCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        fresh.groupBy { it.city }.forEach { (city, nodes) ->
                            val expanded = city in expandedCities
                            val shown = if (expanded) nodes else nodes.take(3)
                            Text(
                                text = "— $city（${nodes.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                            shown.forEach { proxy -> ProxyRow(proxy, showXhs = true) }
                            if (nodes.size > 3) {
                                Text(
                                    text = if (expanded) "收起" else "展开全部 ${nodes.size} 个",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedCities = if (expanded) {
                                                expandedCities - city
                                            } else {
                                                expandedCities + city
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ========== 内置节点（按地区选择，默认折叠） ==========
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { builtinExpanded = !builtinExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_builtin_nodes_toggle),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (builtinExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (builtinExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.vpn_region_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        regionGroups.forEach { (region, proxies) ->
                            val selected = selectedRegion == region
                            val count = proxies.size
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedRegion = region }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selected) "● $region" else "○ $region",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$count 个节点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "地区：$selectedRegion，共 ${regionProxies.size} 个节点（点击即选中）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        regionProxies.forEach { proxy -> ProxyRow(proxy, showXhs = true) }
                    }
                }
            }
        }

        // ========== 坐标输入（手动微调） ==========
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vpn_current_selection),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text(text = stringResource(R.string.vpn_latitude)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lonText,
                            onValueChange = { lonText = it },
                            label = { Text(text = stringResource(R.string.vpn_longitude)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val lat = latText.trim().toDoubleOrNull()
                            val lon = lonText.trim().toDoubleOrNull()
                            if (lat == null || lon == null) {
                                viewModel.reportMissingCoordinates()
                            } else {
                                viewModel.applyCoordinates(lat, lon)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.vpn_btn_apply_coordinates))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // 城市搜索
                    OutlinedTextField(
                        value = cityQuery,
                        onValueChange = {
                            cityQuery = it
                            viewModel.searchCity(it)
                        },
                        label = { Text(text = stringResource(R.string.vpn_city_search_label)) },
                        placeholder = { Text(text = stringResource(R.string.vpn_city_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (uiState.searchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.city_search_results),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        uiState.searchResults.take(8).forEach { place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.applyOfflinePlace(place)
                                        cityQuery = ""
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = place.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${fmtCoord(place.lat)}, ${fmtCoord(place.lon)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // ========== 手动输入代理 ==========
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vpn_manual_input),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = serverText,
                        onValueChange = {
                            serverText = it
                            viewModel.onServerChanged(it)
                        },
                        label = { Text(text = stringResource(R.string.vpn_proxy_server)) },
                        placeholder = { Text(text = stringResource(R.string.vpn_proxy_server_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = {
                                portText = it
                                viewModel.onPortChanged(it)
                            },
                            label = { Text(text = stringResource(R.string.vpn_proxy_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Text(
                                text = "类型",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = proxyType == ProxyType.SOCKS5,
                                    onClick = { proxyType = ProxyType.SOCKS5; viewModel.onTypeChanged(ProxyType.SOCKS5) },
                                    label = { Text(text = "SOCKS5") }
                                )
                                FilterChip(
                                    selected = proxyType == ProxyType.HTTP,
                                    onClick = { proxyType = ProxyType.HTTP; viewModel.onTypeChanged(ProxyType.HTTP) },
                                    label = { Text(text = "HTTP") }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = { viewModel.commitManualConfig() }) {
                            Text(text = stringResource(R.string.btn_save))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            viewModel.testProxy(
                                serverText.trim(),
                                portText.trim().toIntOrNull() ?: 1080,
                                proxyType
                            )
                        }) {
                            Text(text = stringResource(R.string.vpn_btn_test))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.testXiaohongshu(
                                serverText.trim(),
                                portText.trim().toIntOrNull() ?: 1080,
                                proxyType
                            )
                        }) {
                            Text(text = stringResource(R.string.vpn_btn_test_xhs))
                        }
                    }
                    // 小红书测试结果
                    when (uiState.xhsTestResult) {
                        "testing" -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.vpn_xhs_testing),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        "ok" -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.vpn_xhs_test_ok),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        "fail" -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.vpn_xhs_test_fail),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // ========== 使用步骤 ==========
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vpn_steps_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.vpn_steps_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.vpn_permission_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun fmtCoord(v: Double): String = String.format("%.4f", v)
