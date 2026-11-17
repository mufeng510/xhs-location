# 需求分析：免费代理池服务端化（NAS 部署）

> 日期：2026-11-17 · 状态：需求已确认，开发计划待批准
> 变更记录：见文末

## 1. 背景

当前梯版本（vlocation）在 App 内直接从公开源抓取免费 CN 代理并实测，首次进入页面要等很久。
用户多手机自用，希望将抓取/探测/存储全部部署到自家 NAS（当服务器），手机端只从 NAS 获取
“处理好的”节点（代理 IP + GPS 经纬度，按省份），进入 App 即用；同一个服务附带一个只读网站。

可行性结论：**成立**。服务端探测的“小红书可达性（代理→edith.xiaohongshu.com:443）”与
“出口归属地”，与手机侧流量路径 `手机→代理→小红书` 中的“代理→小红书”段等价，
服务端结论对所有手机同样有效；仅“手机→代理”这一跳需 App 侧保留测试（现有功能保留）。

## 2. 目标

1. NAS 常驻服务（Docker Compose 一键部署）：
   定时抓取公开免费 CN 代理 → 去重 → 实测（XHS 可达 + 本地 IP 库归属地）→ 附带 GPS 坐标 → 落 SQLite；
   只保留最新一批。
2. 同一服务提供极简只读网站（地区分组列表 + 上次更新时间 + 各源状态），
   网站入口为**管理页**：输入 `ADMIN_PASSWORD` 登录，页内展示 Token 与配对二维码。
3. Android App 改为从 NAS API 获取处理好的节点（冷启动先读本地缓存、后台静默刷新），
   不再本地逐个实测；列表秒开。App 内置“扫码配对”：扫描管理页二维码自动填入 NAS 地址与 Token。
4. App 在 NAS 不可达时可用（缓存 + 内置节点 + 手动输入兜底）。

## 3. 范围与 Non-goals

**范围**：服务端抓取/探测/存储/API/只读页/Token 鉴权/部署脚本；Android 端读取与缓存改造；
README/docs 更新。

**Non-goals**：
- 不做公网代理分发、不做多租户/代理出售、不做用户注册体系；
- 不做通用化代理池 API（只服务本 App 的小红书场景）；
- 不做历史可用率大盘/报表（只保留最新一批，不存历史）；
- 不绕过第三方应用风控机制；
- 不代理其他 App 或用户的流量（代理只被 App 的 VPN 栈使用）。

## 4. 决策记录（2026-11-17，用户确认）

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | NAS 网络位置 | 中国大陆宽带/内网（探测视角有效） |
| 2 | 手机访问方式 | **仅局域网可达；HTTPS 公网反向代理由用户自行负责**（2026-11-17 更正，App 无感） |
| 3 | 认证 | 固定 Token（Authorization: Bearer）；网站为**管理页**，输 `ADMIN_PASSWORD` 登录后 Cookie 保持会话 |
| 4 | 网站范围 | 管理页（登录后只读展示 + 显示 Token + 配对 QR）+ `/api/status`；无独立管理后台操作 |

> **配置最小化（2026-11-17 用户备注，硬约束）**：只必填 `ADMIN_PASSWORD` 一个环境变量；
> API Token 首次启动自动生成并持久化到 SQLite，展示在管理页；`PORT`（默认 8080）、
> `REFRESH_MINUTES`（默认 20）可选、带默认值。手机通过管理页二维码扫码配对，零手输。
| 5 | 归属地查询 | 本地离线 IP 库（首选 ip2region，中文省市级，MIT 许可；无外部依赖、无限流风险） |
| 6 | GPS 粒度 | 省级（34 省映射到省会；与小红书 IP 属地省级粒度一致） |
| 7 | 刷新 | 每 15–30 分钟全量刷新，只保留最新一批 |
| 8 | 技术栈 | Python 3.12 + FastAPI + asyncio + SQLite；Docker 部署 |
| 9 | App 端旧功能 | 删除 5 源直连多选代码（本次已提交 2f5e189 的 ProxySources 等），只留 NAS 模式；Git 历史可回溯 |
| 10 | 需求文档 | 本文档 `docs/requirement-proxy-server.md` |

## 5. 用户流程

**主路径（App）**：打开 App → 读本地缓存即时展示 → 后台刷新 NAS `/api/proxies` → 列表更新 →
选节点 → GPS+VPN 启动模拟。
**备选**：NAS 不可达 → 保持缓存（标注“更新于 X 分钟前”）→ 内置节点 / 手动输入兜底。
**网站路径**：浏览器访问 `http://nas-ip:8080/` → 输入 `ADMIN_PASSWORD` 登录 → Cookie 保持会话 →
看地区分组列表、各源状态、上次更新时间、Token 与配对二维码；手机扫码自动填入配置。

## 6. API 草案

统一鉴权：API 用 `Authorization: Bearer <token>`；管理页 POST 登录后下发 HttpOnly Cookie（`/`、`/setup/qr.png`、`/logout` 用 Cookie）。

```
GET /healthz                       → 200 "ok"（无需鉴权，供反代/监控探活）
POST /login                        → form admin_password=xxx；成功 302 → / 并 Set-Cookie；失败 401
GET /logout                        → 清 Cookie；302 → /login
GET /                              → 管理页 HTML（未登录 302 → /login）：代理池列表 + 状态 + Token + QR
GET /setup/qr.png                  → Cookie 鉴权；二维码内容 vlocation://setup?url=<request base_url>&token=<token>
GET /api/proxies?province=北京&reachable=true
→ 200 [ { "server": "1.2.3.4", "port": 1080, "type": "socks5",
          "province": "北京", "city": "北京",
          "latitude": 39.9042, "longitude": 116.4074,
          "xhs_reachable": true, "latency_ms": 120,
          "checked_at": "2026-11-17T10:00:00+08:00" } ]
GET /api/status
→ 200 { "last_update": "...", "total": 60, "reachable": 25,
        "sources": [ { "id": "proxifly_cn", "ok": true, "candidates": 518 }, ... ] }
```

未授权返回 401；参数非法 400；服务内部错误 500，App 按“请求失败”走缓存兜底。
Token 首次启动自动生成存 SQLite `meta` 表；管理页 url 从请求的 base_url 推断（零配置）。

## 7. 前置条件与风险

1. **局域网直连，公网由用户自行兜底**（2026-11-17 更正）：服务绑定容器内 0.0.0.0:8080，
   用户在路由器/反代（HTTPS、证书、DDNS）自行处理；本服务不实现公网可达性逻辑。
2. **Token 泄露面与持久化**：APK 内置 Token 可被解包提取；仅自用且 API 只读的前提下影响有限
   （别人能看到你筛选过的免费代理列表、能刷你 NAS 的带宽）。Token 自动生成并存 NAS 的
   SQLite（挂载 volume 持久化，重建容器不丢），仅存在用户设备 DataStore 与 NAS 数据库，
   不进源码仓库明文；若泄露，清空 volume 数据即重新生成。
3. **免费代理固有风险**：公开节点、随时消失、带宽有限、出口流量可被节点侧看到
   （XHS 走 HTTPS，元数据可见、内容加密）。不可承诺可用率；App 文案需告知。
4. **多手机共享同一批免费节点**：并发高的手机体验由最差代理决定；选节点前“测试连接”按钮保留。
5. **XHS 探测**：每批 ≤60 并发 16，仅 TLS 握手 + GET /，量极小，不构成爬虫行为。
6. **免责**：不保证绕过小红书风控；本服务与 App 均为自用工具，不承担第三方平台处置责任。

## 8. 省份 → GPS 映射（34 项）

服务端内置表（国标省份 + 省会经纬度），归属地关键词匹配后直接映射；未知省份归“其他地区”，
GPS 回退北京坐标并标注未知。

北京(39.9042,116.4074) 天津(39.3434,117.3616) 河北-石家庄(38.0423,114.5149)
山西-太原(37.8706,112.5489) 内蒙古-呼和浩特(40.8415,111.7519) 辽宁-沈阳(41.8057,123.4315)
吉林-长春(43.8252,125.3225) 黑龙江-哈尔滨(45.8038,126.5349) 上海(31.2304,121.4737)
江苏-南京(32.0603,118.7968) 浙江-杭州(30.2741,120.1551) 安徽-合肥(31.8206,117.2269)
福建-福州(26.0745,119.2965) 江西-南昌(28.6841,115.8865) 山东-济南(36.6683,117.0184)
河南-郑州(34.7466,113.6254) 湖北-武汉(30.5928,114.3055) 湖南-长沙(28.2282,112.9388)
广东-广州(23.1291,113.2644) 广西-南宁(22.8170,108.3664) 海南-海口(20.0443,110.3319)
重庆(29.5630,106.5516) 四川-成都(30.5723,104.0668) 贵州-贵阳(26.5783,106.7132)
云南-昆明(25.0388,102.7193) 西藏-拉萨(29.6500,91.1000) 陕西-西安(34.3416,108.9398)
甘肃-兰州(36.0611,103.8343) 青海-西宁(36.6171,101.7782) 宁夏-银川(38.4863,106.2300)
新疆-乌鲁木齐(43.8259,87.6168) 香港(22.3193,114.1694) 澳门(22.1987,113.5439) 台湾-台北(25.0330,121.5654)

## 9. 数据模型（SQLite）

```
meta(key TEXT PRIMARY KEY, value TEXT)          -- last_update, version
proxies(server TEXT, port INTEGER, type TEXT,
        province TEXT, city TEXT, latitude REAL, longitude REAL,
        xhs_reachable INTEGER, latency_ms INTEGER,
        checked_at TEXT, PRIMARY KEY(server, port))
sources(id TEXT PRIMARY KEY, ok INTEGER, candidates INTEGER,
        error TEXT, checked_at TEXT)
```

每轮刷新：全量 probe → 单事务 `DELETE FROM proxies; INSERT …`（仅保留最新一批）→ 更新 meta/sources。
查询：`SELECT … ORDER BY xhs_reachable DESC, latency_ms ASC`。

## 10. 部署架构

```
[用户自行 HTTPS 反代（可选）] → proxy-server (Docker Compose, restart=unless-stopped)
                                    ├─ FastAPI: / /login /api/* /healthz /setup/qr.png
                                    ├─ 后台任务: 每 REFRESH_MINUTES（默认 20）分钟抓取+探测+入库
                                    └─ SQLite (bind mount ./data → /data)
```

**最小配置（仅 1 个必填项）**：`ADMIN_PASSWORD`（管理页登录密码）。
可选：`PORT`（默认 8080）、`REFRESH_MINUTES`（默认 20）、`PROXY_DB`（默认 /data/proxy.db）。
API Token 首次启动自动生成存 SQLite（**请务必挂载 volume**，否则重建容器会重新生成、所有手机需重新配对）。
管理页内展示 Token 与配对二维码（`vlocation://setup?url=<base_url>&token=<token>`）。

## 11. 验收标准（AC）

1. NAS 上 `docker compose up -d` 后 ≤10 min，`/api/proxies` 返回非空且字段齐全；
2. 抽查 10 个节点：归属地为“未知”≤3 个，省份 GPS 与归属地一致 ≥7 个；
3. App 冷启动展示节点列表 <1s（缓存命中），后台刷新失败不报错弹窗、只静默降级；
4. 停 NAS：App 展示缓存 + 显示“更新于 X 分钟前”，可正常开始模拟（内置/手动节点）；
5. 无 Token / 错 Token：401；`/` 未登录 302 → /login；正确管理密码登录后 Cookie 免登；
6. `docker compose logs` 无敏感信息（管理密码、Token 不打印）；
7. 24h 观察：服务内存 <300MB，CPU 平均 <10%，刷新失败可自愈（下一轮自动重试，无人工干预）；
8. 首次启动后 `/` 显示 Token 与配对二维码；二维码可被手机扫码解析出 `vlocation://setup?url=…&token=…`；
9. 重建容器（保留 ./data volume）：Token 不变，已配对手机无需重新配对。

## 12. 开发计划（分阶段）

- **阶段 1｜服务端闭环 + 管理页**：✅ 已完成（b173abc）。
  抓取源移植（proxifly / ProxyScrape / proxyfreeonly / ProxyClean-#cn / geonode）
  → 去重轮转截断 60 → asyncio 并发探测（XHS TLS + ip2region 归属地）
  → SQLite 入库 → `/api/proxies` `/api/status` `/healthz` + Token 鉴权
  + 管理页（登录 / 列表 / Token / 配对 QR，server-rendered HTML，无前端框架）
  → Dockerfile + docker-compose（bind mount ./data）。
  测试：解析器/映射/合并单测（pytest）26 个全过；本机端到端冒烟（uvicorn + 真网）通过；
  容器内运行测试待 docker run 授权。
- **阶段 2｜Android App 改造**（即下文之“阶段 3”）：删 ProxySources/Registry（2f5e189 引入）→ 新增
  `ServerProxyFetcher`（GET NAS，Bearer Token，5s 超时）+ 本地缓存 → 设置页 NAS 配置
  （URL/Token/**扫码配对**：vlocation://setup?url=&token=，相机权限 + ML Kit 条码扫描，
  扫码成功自动填入）→ 主页改“数据来自 NAS · 更新于 x 分钟前” → 兜底链路保留 →
  单测更新（解析/降级），README 更新。
- **阶段 3｜实测验收**：按 §11 AC 逐项验证并记录；问题修复；需要时把验收结果补进本文档。

预估改动量：服务端全新约 1000 行（Python+模板+测试，已完成）；App 端改约 400 行删改 + 扫码模块；文档同步。
**风险**：LAN 可达性由用户网络决定；服务默认不依赖公网，HTTPS/证书/反代由用户负责。

## 13. 变更记录

| 日期 | 变更 | 原因 | 影响 |
|------|------|------|------|
| 2026-11-17 | 初版确认；10 项决策落定 | 需求评审 | 初始范围 |
| 2026-11-17 | 访问方式改为“LAN 直连、反代由用户负责”；配置最小化：仅必填 `ADMIN_PASSWORD`，API Token 自动生成存 SQLite；管理页展示 Token + vlocation://setup 配对二维码；App 侧扫码填入配置；阶段 2 并入阶段 1 | 用户追加备注 | API 与部署章节、AC（§7/§10/§11/§12）、阶段 3 新增扫码模块 |
| 2026-11-17 | 阶段编号拨正：阶段 2=App 改造（即原“阶段 3”），阶段 3=实测验收（即原“阶段 4”）；阶段 1 标记 b173abc 完成 | 编号歧义（用户问“阶段二”） | §12、本文档其余阶段引用 |
| 2026-11-17 | CI 增加 docker-publish 任务：main/tag 推送自动构建并推送 GHCR 镜像（:latest / :main / :sha-* / :tag）；release-build 增加 APK 更名为 `vlocation-<ref>.apk`；tag 触发 Release 附 APK | 用户要求“自动发布 Docker 镜像和手机端安装包文件” | .github/workflows/ci.yml、README CI/CD 章节 |
