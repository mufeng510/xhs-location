package com.mufeng.xhs.location.map

/**
 * 离线地点数据库。
 *
 * 由于 nominatim.openstreetmap.org 在中国大陆境内被屏蔽，设备无网络时
 * 无法进行在线搜索。此处内置常用中国城市坐标，作为离线搜索的兜底，
 * 保证用户在无网络环境下仍能选定位置并启动模拟定位。
 */
object OfflinePlaceDatabase {

    data class OfflinePlace(
        val name: String,
        val lat: Double,
        val lon: Double,
        val displayName: String,
        val aliases: List<String> = emptyList()
    )

    private val places = listOf(
        OfflinePlace("北京", 39.9042, 116.4074, "北京, 中国", listOf("beijing")),
        OfflinePlace("上海", 31.2304, 121.4737, "上海, 中国", listOf("shanghai")),
        OfflinePlace("广州", 23.1291, 113.2644, "广州, 中国", listOf("guangzhou")),
        OfflinePlace("深圳", 22.5431, 114.0579, "深圳, 中国", listOf("shenzhen")),
        OfflinePlace("杭州", 30.2741, 120.1551, "杭州, 中国", listOf("hangzhou")),
        OfflinePlace("南京", 32.0603, 118.7968, "南京, 中国", listOf("nanjing")),
        OfflinePlace("武汉", 30.5928, 114.3055, "武汉, 中国", listOf("wuhan")),
        OfflinePlace("成都", 30.5723, 104.0668, "成都, 中国", listOf("chengdu")),
        OfflinePlace("重庆", 29.5630, 106.5516, "重庆, 中国", listOf("chongqing")),
        OfflinePlace("西安", 34.3416, 108.9398, "西安, 中国", listOf("xian")),
        OfflinePlace("天津", 39.3434, 117.3616, "天津, 中国", listOf("tianjin")),
        OfflinePlace("苏州", 31.2991, 120.6189, "苏州, 中国", listOf("suzhou")),
        OfflinePlace("长沙", 28.2282, 112.9388, "长沙, 中国", listOf("changsha")),
        OfflinePlace("郑州", 34.7466, 113.6254, "郑州, 中国", listOf("zhengzhou")),
        OfflinePlace("合肥", 31.8206, 117.2269, "合肥, 中国", listOf("hefei")),
        OfflinePlace("福州", 26.0745, 119.2965, "福州, 中国", listOf("fuzhou")),
        OfflinePlace("厦门", 24.4798, 118.0894, "厦门, 中国", listOf("xiamen")),
        OfflinePlace("南昌", 28.6841, 115.8865, "南昌, 中国", listOf("nanchang")),
        OfflinePlace("济南", 36.6683, 117.0184, "济南, 中国", listOf("jinan")),
        OfflinePlace("青岛", 36.0671, 120.3826, "青岛, 中国", listOf("qingdao")),
        OfflinePlace("大连", 38.9140, 121.6147, "大连, 中国", listOf("dalian")),
        OfflinePlace("沈阳", 41.8057, 123.4315, "沈阳, 中国", listOf("shenyang")),
        OfflinePlace("哈尔滨", 45.8038, 126.5349, "哈尔滨, 中国", listOf("harbin")),
        OfflinePlace("长春", 43.8252, 125.3225, "长春, 中国", listOf("changchun")),
        OfflinePlace("昆明", 25.0388, 102.7193, "昆明, 中国", listOf("kunming")),
        OfflinePlace("贵阳", 26.5783, 106.7132, "贵阳, 中国", listOf("guiyang")),
        OfflinePlace("南宁", 22.8170, 108.3664, "南宁, 中国", listOf("nanning")),
        OfflinePlace("海口", 20.0443, 110.3319, "海口, 中国", listOf("haikou")),
        OfflinePlace("乌鲁木齐", 43.8259, 87.6168, "乌鲁木齐, 中国", listOf("urumqi")),
        OfflinePlace("拉萨", 29.6500, 91.1000, "拉萨, 中国", listOf("lhasa")),
    )

    /**
     * 按名称模糊匹配离线地点，最多返回 5 个结果。
     * 匹配项包括中文名、显示名和英文别名。
     */
    fun search(query: String): List<OfflinePlace> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return places.filter {
            it.name.lowercase().contains(q) ||
                it.displayName.lowercase().contains(q) ||
                it.aliases.any { it.lowercase().contains(q) }
        }.take(5)
    }
}
