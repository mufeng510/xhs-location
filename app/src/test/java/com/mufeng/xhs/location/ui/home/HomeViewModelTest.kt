package com.mufeng.xhs.location.ui.home

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.mufeng.xhs.location.data.datastore.LocationConfig

class HomeViewModelTest {

    @Test
    fun `formatAddress should produce correct string`() {
        val lat = 31.2304
        val lon = 121.4737
        val expected = String.format("%.4f, %.4f", lat, lon)
        assertEquals("地址格式化应该正确", "31.2304, 121.4737", expected)
    }

    @Test
    fun `UiState default values should be correct`() {
        val state = com.mufeng.xhs.location.ui.home.HomeViewModel.UiState()
        assertTrue("默认不应该运行", !state.isRunning)
        assertTrue("默认应该加载中", state.isLoading)
        assertTrue("默认不应该显示权限引导", !state.showPermissionGuide)
        assertTrue("默认配置纬度应该是 0", state.locationConfig.latitude == 0.0)
        assertTrue("默认配置经度应该是 0", state.locationConfig.longitude == 0.0)
    }
}
