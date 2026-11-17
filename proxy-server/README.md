# vlocation 代理池服务（proxy-server）

把 vlocation Android 应用里的“抓免费代理 + 实测”搬到 NAS 上跑：
定时抓取公开免费 CN 代理 → 去重、轮转截断 60 个 → 并发实测（小红书可达性 + 出口归属地）
→ SQLite 保存**最新一批** → 手机 App 直接读处理好的数据（含省级 GPS），进入 App 秒开。

同一端口附带一个管理页：登录后可看到可用节点（按地区）、各数据源状态、API Token 和配对二维码。

> 自用工具：免费代理不稳定、随时消失，不可承诺可用率；不绕过小红书风控；与手机 App 均仅为个人使用。

## 配置项（只有一个必填）

| 变量 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `ADMIN_PASSWORD` | ✅ | 无 | 管理页登录密码（只你知道） |
| `PORT` | 否 | `8080` | 容器内监听端口（compose 里改映射即可） |
| `REFRESH_MINUTES` | 否 | `20` | 刷新周期（分钟，最小 5） |
| `PROXY_DB` | 否 | `/data/proxy.db` | SQLite 路径 |
| `IP2REGION_XDB` | 否 | `/data/ip2region_v4.xdb` | 离线归属地库路径 |

API Token **首次启动自动生成**并写入 SQLite，管理页里展示；**不需要、也不应该**手写 Token。

## 部署（Docker Compose，3 步）

```bash
cd proxy-server
cp .env.example .env
# 编辑 .env，把 ADMIN_PASSWORD 改成自己的管理密码
# 生成强密码示例：openssl rand -base64 18
docker compose up -d --build
```

1. 浏览器打开 `http://<NAS的IP>:8080/`，输入管理密码登录；
2. 等待首轮刷新（≈1 分钟，页面刷新可见）；
3. 手机安装 vlocation → 显示 Token / 配对二维码，扫码即可自动填入“服务器地址 + Token”。

> **务必挂载 `./data → /data`**（compose 已配置）：Token 存在 SQLite 里，
> 删掉或换成新 volume 会重新生成 Token，所有手机要重新配对。

## 手机 App 侧

Android App 设置页可配置 NAS 地址 + Token（或扫码配对，阶段 3 提供），
然后开始模拟。App 不可达 NAS 时会退回缓存显示 + 内置节点/手动输入兜底。

## API（Android App 用）

统一鉴权 `Authorization: Bearer <token>`（Token 见管理页）：

```
GET /healthz                      → 200 "ok"（不需鉴权，供反代/监控探活）
GET /api/proxies?province=浙江&reachable=true
→ 200 [ { "server": "1.2.3.4", "port": 1080, "type": "socks5",
          "province": "浙江", "city": "杭州",
          "latitude": 30.2741, "longitude": 120.1551,
          "xhs_reachable": 1, "latency_ms": 120,
          "checked_at": ... } ]
GET /api/status
→ 200 { "last_update": "...", "total": 60, "reachable": 7,
        "sources": [ { "id": "proxifly_cn", ... } ] }
```

**注意：URL 中的中文参数必须 percent-encode**（OkHttp/Retrofit 自动处理）。
中国大陆手机走 NAS 直连内网没问题；如需公网访问，HTTPS 反代证书/备案由你自己负责。

## 数据来源与原理

- 抓取源（与缓存到节点的来源、字段见 /api/status sources[]）：
  proxifly/free-proxy-list（GitHub 每 5 分钟更新）、ProxyScrape/free-proxy-list、
  proxyfreeonly.com、ProxyClean（仅 #cn）、Geonode 官方 API；
  各源各自隔离失败，互不影响；合并按 round-robin 轮转取样、ip:port 去重、截断 60。
- 实测：`edith.xiaohongshu.com:443` TLS 握手 + GET /（状态码 200–599 视为可达），
  并发 16、单节点 8 秒超时。
- 归属地：**本地离线库 ip2region**（Apache-2.0）——代理出口 IP 即节点自身 IP，
  查库即得，无需访问第三方查询 API、无限流风险。

## 运维

- 日志：`docker compose logs -f`（不含密码/Token）
- 立即触发刷新：重启容器（`docker compose restart`）即可，启动时立即跑一次
- 调整刷新周期：`.env` 里 `REFRESH_MINUTES=30`，重启生效
- 更新代码/镜像：`docker compose build --pull && docker compose up -d`
- 数据：`./data/proxy.db`；整体备份 `./data` 目录即可

## 常见问题

- **首次打开节点为空**：首轮刷新进行中，约 1 分钟后自动出现。
- **本批全部不可达**：免费代理常见；下一轮（≤20 分钟）自动重试；可临时用手动输入。
- **某个数据源显示失败**：不影响其他源；geonode 偶发超时可忽略。
- **Token 忘了/手机丢了**：清空 `./data/` 后 `docker compose up -d` 重新生成 Token，
  管理页重新配对即可。
- **内存/CPU**：<300MB / 平均 <10%；刷新期间短暂上升。

## 开发与测试

```bash
python3 -m venv .venv && ./.venv/bin/pip install -r requirements-dev.txt
./.venv/bin/python -m pytest tests/ -v
```

冒烟（不构建镜像时）：`PROXY_DB=/tmp/x.db ADMIN_PASSWORD=test ./.venv/bin/uvicorn app.main:app --port 8611`

## 目录结构

```
proxy-server/
├── Dockerfile              # 镜像（内置 ip2region xdb，POST 校验 SHA256）
├── docker-compose.yml      # 一键启动（./data 持久化）
├── .env.example            # 只需要设 ADMIN_PASSWORD
├── app/
│   ├── main.py             # FastAPI 路由（/ /login /api/* /healthz /setup/qr.png）
│   ├── admin.py            # 管理页 HTML（地区分组、Token、配对二维码）
│   ├── auth.py             # Cookie 会话 + Bearer Token + 登录限流
│   ├── proxy_sources.py    # 5 个源的抓取/解析/合并（从 Android 端移植）
│   ├── probe.py            # 探测：SOCKS5/HTTP CONNECT + XHS TLS 握手
│   ├── geoip.py            # ip2region 离线归属地（vendor 绑定，Apache-2.0）
│   ├── provinces.py        # 34 省映射 + GPS 坐标
│   ├── db.py               # SQLite（最新一批、源状态、meta）
│   ├── refresh.py          # 刷新编排（抓取→探测→单事务入库）
│   └── vendor/ip2region/   # ip2region Python 绑定（Apache-2.0 原样 vendor）
└── tests/                  # pytest 26 个用例
```
