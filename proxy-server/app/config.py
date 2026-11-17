"""配置：全部环境变量驱动；仅 ADMIN_PASSWORD 必填。"""

import os
import sys

ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "").strip()
PORT = int(os.environ.get("PORT", "8080"))
REFRESH_MINUTES = max(5, int(os.environ.get("REFRESH_MINUTES", "20")))
PROXY_DB = os.environ.get("PROXY_DB", "/data/proxy.db")
IP2REGION_XDB = os.environ.get("IP2REGION_XDB", PROXY_DB.rsplit("/", 1)[0] + "/ip2region_v4.xdb")


def validate() -> None:
    if not ADMIN_PASSWORD:
        print(
            "FATAL: 未设置 ADMIN_PASSWORD。\n"
            "  本服务只有一个必填配置：管理密码（用于登录管理页）。\n"
            "  生成方式：openssl rand -base64 18\n"
            "  配置：docker-compose.yml 的 .env（见 .env.example），或环境变量 ADMIN_PASSWORD=xxx",
            file=sys.stderr,
            flush=True,
        )
        sys.exit(2)
