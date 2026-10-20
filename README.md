# vlocation — 无 Root Android 虚拟定位工具

[![CI](https://github.com/mufeng510/xhs-location/actions/workflows/ci.yml/badge.svg)](https://github.com/mufeng510/xhs-location/actions/workflows/ci.yml)

vlocation 是一个面向 Android 定位测试场景的开源虚拟定位工具。基于 Android 官方 Mock Location 机制，在无需 Root 的设备上为系统提供模拟位置信息。

优先支持小红书（Xiaohongshu）等应用的位置测试场景。

> **免责声明**：vlocation 仅提供系统模拟定位能力，不上传用户位置，不收集数据，无账号，无服务器。**不保证绕过第三方应用检测机制。**

## 功能特性

- **模拟定位**：输出经纬度、精度、海拔、速度、方位角、时间戳，模拟真实 GPS 参数
- **地图选点**：基于 MapLibre + OpenStreetMap，支持拖动地图、点击定位、获取经纬度
- **地点搜索**：Nominatim 搜索 API，支持输入地点、返回候选、地图定位
- **随机漂移**：围绕中心点的小范围随机漂移，模拟自然移动，参数内部固定
- **前台服务**：Android Foreground Service，常驻通知显示模拟位置
- **开始/停止模拟**：权限检查 → Mock Location 状态检查 → 加载配置 → 启动服务 → 发送模拟位置
- **权限检测**：检测开发者选项与模拟位置应用设置，未设置则显示引导页
- **开机自动恢复**：BOOT_COMPLETED 广播，读取上次状态并恢复模拟服务；权限失效则不启动并提示用户

## 技术栈

- Kotlin + Android SDK
- Jetpack Compose + Material 3
- ViewModel + StateFlow + Kotlin Coroutines
- Hilt 依赖注入
- DataStore (Preferences) 数据持久化
- MapLibre Android SDK + OpenStreetMap 瓦片
- Retrofit + OkHttp (Nominatim 搜索)
- JUnit + Android Instrumentation Test

## 支持平台

- Android 8.0 (API 26) 及以上
- 重点测试：Android 10 ~ 14

## 目录结构

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/mufeng/xhs/location/
│   │   ├── boot/           # 开机恢复 (BootReceiver)
│   │   ├── core/           # Application, Hilt DI
│   │   ├── data/           # DataStore, Repository
│   │   ├── location/       # MockLocationManager, DriftEngine, LocationScheduler
│   │   ├── map/            # MapLibre 地图, Nominatim 搜索
│   │   ├── permission/     # 权限管理
│   │   ├── service/        # 前台服务
│   │   └── ui/             # Compose 页面 (home/map/settings/components)
│   └── res/
├── src/test/               # 单元测试 (JUnit)
└── src/androidTest/        # 插桩测试
.github/
└── workflows/
    └── ci.yml              # GitHub Actions CI/CD
```

## 构建

### 前提条件

- Android SDK (API 34)
- JDK 17
- Gradle 8.x

### 调试构建

```bash
./gradlew :app:assembleDebug
```

### 运行单元测试

```bash
./gradlew :app:testDebugUnitTest
```

### 运行插桩测试

需要连接 Android 设备或启动模拟器：

```bash
./gradlew :app:connectedDebugAndroidTest
```

### 发布构建

```bash
./gradlew :app:assembleRelease
```

## 权限

应用在 `AndroidManifest.xml` 中声明以下权限：

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` | 访问精确位置 |
| `ACCESS_COARSE_LOCATION` | 访问粗略位置 |
| `ACCESS_MOCK_LOCATION` | 设置模拟位置 |
| `INTERNET` | Nominatim 地点搜索 |
| `ACCESS_NETWORK_STATE` | 网络状态 |
| `FOREGROUND_SERVICE` | 前台服务 |
| `FOREGROUND_SERVICE_LOCATION` | 前台服务定位类型 (Android 10+) |
| `RECEIVE_BOOT_COMPLETED` | 开机自动恢复 |

使用前需在系统设置中：
1. 开启“开发者选项”
2. 将 vlocation 设置为“模拟位置应用”（Mock Location App）

## 数据设计

不使用数据库，全部使用 **DataStore (Preferences)** 持久化：

### LocationConfig

| 字段 | 类型 | 说明 |
|------|------|------|
| latitude | Double | 纬度 |
| longitude | Double | 经度 |
| accuracy | Float | 精度 (米) |
| enabled | Boolean | 是否启用模拟 |
| lastUpdated | Long | 最后更新时间戳 |

### AppSettings

| 字段 | 类型 | 说明 |
|------|------|------|
| firstLaunchCompleted | Boolean | 首次启动是否完成 |
| autoRestoreEnabled | Boolean | 开机自动恢复 |

## 异常处理

- **权限异常**：未设置模拟位置应用时禁止启动，显示引导页
- **网络异常**：Nominatim 不可访问时提示用户检查网络
- **服务异常**：Foreground Service 启动失败时显示错误
- **Android 后台限制**：后台被杀时尝试恢复

## CI/CD

- **Pull Request**：Gradle 构建 + 单元测试 + 插桩测试
- **Main 分支**：自动构建 Release APK 并生成 Release Artifact

## 协议

本项目采用 [GPL-3.0](LICENSE) 协议开源。

## 安全与隐私

- 不上传用户位置
- 不收集任何数据
- 无账号体系
- 无服务器端

## 后续计划

- [ ] 完善 MapLibre 地图标注与交互
- [ ] 增加漂移参数可视化
- [ ] 适配 Android 15 前台服务新要求
- [ ] 增加更多单元测试覆盖率
