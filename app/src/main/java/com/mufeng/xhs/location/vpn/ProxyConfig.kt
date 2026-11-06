package com.mufeng.xhs.location.vpn

/**
 * 代理服务器配置。
 *
 * 支持 SOCKS5 和 HTTP 两种代理类型。用户填写服务器地址与端口后，
 * 启用VPN即可将全局流量经该代理转发，从而改变出口 IP 地址。
 */
data class ProxyConfig(
    /** 代理服务器地址（IP 或 域名） */
    val server: String = "",
    /** 代理服务器端口 */
    val port: Int = 1080,
    /** 代理类型："socks5" | "http" */
    val type: String = "socks5",
    /** 是否启用VPN代理 */
    val enabled: Boolean = false,
    /** 上次修改时间戳 */
    val lastUpdated: Long = 0L
)

/** 代理类型常量 */
object ProxyType {
    const val SOCKS5 = "socks5"
    const val HTTP = "http"
}

/** VPN 连接状态 */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * 中国大陆免费代理服务器池（按省份/城市分组）。
 *
 * 数据来源：proxyhub.me、proxyfreeonly.com、goodips.com、zdaye.com 等公开免费代理列表
 * (抓取时间：2026-10-24)。所有条目均为 IP:PORT 格式，可直接用于 SOCKS5/HTTP 代理。
 *
 * ⚠️ 重要提示：
 * 1. 免费代理不稳定，可用性随时间变化，建议使用前先点击“测试连接”。
 * 2. 如需长期稳定可用，建议购买付费代理服务。
 * 3. 代理仅用于本地网络调试，不上传任何用户数据。
 */
object BuiltinProxies {

    /** 代理条目：IP、端口、类型、地区标签、对应 GPS 坐标 */
    data class BuiltinProxy(
        val server: String,
        val port: Int,
        val type: String,
        val label: String,        // 显示给用户的地区标签，如 "北京 - 电信"
        val latitude: Double = 0.0,   // 对应的模拟 GPS 纬度
        val longitude: Double = 0.0,  // 对应的模拟 GPS 经度
        val city: String = "",         // 城市名，用于显示
        val xhsReachable: Boolean = false,  // 经该代理测试访问小红书 API 是否可达
        val regionDetail: String = ""       // 经代理实测的真实归属地（如 "湖北 十堰 电信"），空表示未检测
    )

    /** 北京地区代理（GPS：北京 39.9042, 116.4074） */
    val beijing = listOf(
        BuiltinProxy("103.39.67.206", 7890, ProxyType.HTTP, "北京 - 电信", 39.9042, 116.4074, "北京"),
        BuiltinProxy("39.102.209.163", 8081, ProxyType.HTTP, "北京 - 阿里云", 39.9042, 116.4074, "北京"),
        BuiltinProxy("47.92.219.102", 8080, ProxyType.HTTP, "北京 - 阿里云", 39.9042, 116.4074, "北京"),
        BuiltinProxy("8.130.36.163", 8008, ProxyType.SOCKS5, "北京 - 阿里云", 39.9042, 116.4074, "北京"),
        BuiltinProxy("39.104.57.33", 106, ProxyType.SOCKS5, "北京", 39.9042, 116.4074, "北京"),
        BuiltinProxy("180.158.222.93", 1080, ProxyType.SOCKS5, "北京", 39.9042, 116.4074, "北京"),
        BuiltinProxy("218.95.39.57", 59999, ProxyType.SOCKS5, "江西九江 电信/IDC", 39.9042, 116.4074, "北京"),
    )

    /** 上海地区代理（GPS：上海 31.2304, 121.4737） */
    val shanghai = listOf(
        BuiltinProxy("139.196.46.164", 8888, ProxyType.HTTP, "上海 - 阿里云BGP", 31.2304, 121.4737, "上海"),
        BuiltinProxy("1.15.53.214", 1080, ProxyType.HTTP, "上海", 31.2304, 121.4737, "上海"),
    )

    /** 广州/深圳地区代理（GPS：广州 23.1291, 113.2644） */
    val guangdong = listOf(
        BuiltinProxy("61.142.113.42", 6666, ProxyType.SOCKS5, "广东中山 电信", 23.1291, 113.2644, "广州"),
        BuiltinProxy("47.106.216.202", 8181, ProxyType.HTTP, "广东深圳 阿里云", 22.5431, 114.0579, "深圳"),
        BuiltinProxy("47.104.27.165", 8090, ProxyType.HTTP, "山东青岛 阿里云", 36.0671, 120.3826, "青岛"),
    )

    /** 杭州地区代理（GPS：杭州 30.2741, 120.1551） */
    val hangzhou = listOf(
        BuiltinProxy("39.170.29.226", 10800, ProxyType.SOCKS5, "浙江杭州 移动", 30.2741, 120.1551, "杭州"),
        BuiltinProxy("47.115.221.69", 9090, ProxyType.HTTP, "浙江杭州 阿里云", 30.2741, 120.1551, "杭州"),
        BuiltinProxy("47.119.22.156", 3128, ProxyType.HTTP, "浙江杭州 阿里云", 30.2741, 120.1551, "杭州"),
        BuiltinProxy("47.116.126.57", 8443, ProxyType.HTTP, "浙江杭州 阿里云", 30.2741, 120.1551, "杭州"),
    )

    /** 成都地区代理（GPS：成都 30.6595, 104.0648） */
    val chengdu = listOf(
        BuiltinProxy("47.109.52.147", 9091, ProxyType.SOCKS5, "四川成都 阿里云", 30.6595, 104.0648, "成都"),
    )

    /** 其他地区代理（无特定 GPS，使用用户当前设置） */
    val other = listOf(
        BuiltinProxy("221.176.85.225", 1080, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("218.77.50.79", 6666, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("116.237.180.55", 7001, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("140.207.114.166", 10080, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("113.249.111.67", 1080, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("36.213.79.159", 1080, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("111.206.4.163", 18888, ProxyType.SOCKS5, "高匿节点"),
        BuiltinProxy("61.158.175.38", 9002, ProxyType.HTTP, "河南商丘 联通"),
        BuiltinProxy("39.172.97.192", 8060, ProxyType.HTTP, "高匿节点"),
    )

    /** 所有代理列表（扁平化） */
    val all: List<BuiltinProxy> =
        beijing + shanghai + guangdong + hangzhou + chengdu + other

    /** 按地区分组的映射，用于 UI 下拉选择 */
    val regions: Map<String, List<BuiltinProxy>> = mapOf(
        "北京" to beijing,
        "上海" to shanghai,
        "广东（广州/深圳）" to guangdong,
        "浙江（杭州）" to hangzhou,
        "四川（成都）" to chengdu,
        "其他地区" to other,
    )

    /** 推荐城市坐标列表，用于 GPS 模拟快速选择 */
    val recommendedCities: List<BuiltinProxy> = listOf(
        BuiltinProxy("北京", 0, ProxyType.SOCKS5, "北京 - 推荐", 39.9042, 116.4074, "北京"),
        BuiltinProxy("上海", 0, ProxyType.SOCKS5, "上海 - 推荐", 31.2304, 121.4737, "上海"),
        BuiltinProxy("广州", 0, ProxyType.SOCKS5, "广州 - 推荐", 23.1291, 113.2644, "广州"),
        BuiltinProxy("深圳", 0, ProxyType.SOCKS5, "深圳 - 推荐", 22.5431, 114.0579, "深圳"),
        BuiltinProxy("杭州", 0, ProxyType.SOCKS5, "杭州 - 推荐", 30.2741, 120.1551, "杭州"),
        BuiltinProxy("成都", 0, ProxyType.SOCKS5, "成都 - 推荐", 30.6595, 104.0648, "成都"),
    )
}
