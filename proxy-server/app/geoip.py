"""本地 IP 归属地查询（ip2region，离线 xdb 库）。

代理节点的出口 IP 就是节点本身，因此查 proxy.server 即得真实出口归属地，
与手机 App 流量经代理后 XHS 看到的归属地一致（对直接假设成立的免费代理成立）。

xdb 数据文件默认 /data/ip2region_v4.xdb（Docker 构建时下载，见 Dockerfile）。
查询失败返回 None，由调用方回退“未知归属”，不阻塞抓取。
"""

from __future__ import annotations

import os
import sys
import threading

_VENDOR_DIR = os.path.join(os.path.dirname(__file__), "vendor")
if _VENDOR_DIR not in sys.path:
    sys.path.insert(0, _VENDOR_DIR)

import ip2region.util as ip2r_util  # type: ignore  # noqa: E402
import ip2region.searcher as ip2r_searcher  # type: ignore  # noqa: E402

_XDB_PATH = os.environ.get("IP2REGION_XDB", "/data/ip2region_v4.xdb")
_lock = threading.Lock()
_searcher = None
_load_failed = False


def _load_searcher():
    global _searcher, _load_failed
    with _lock:
        if _searcher is not None or _load_failed:
            return _searcher
        try:
            vector_index = ip2r_util.load_vector_index_from_file(_XDB_PATH)
            _searcher = ip2r_searcher.new_with_vector_index(
                ip2r_util.IPv4, _XDB_PATH, vector_index
            )
        except Exception as exc:  # xdb 缺失/损坏：不致命，回退未知归属
            _load_failed = True
            print(f"[geoip] 无法加载 ip2region 库 {_XDB_PATH}: {exc}", flush=True)
            return None
        return _searcher


def lookup(ip: str) -> str | None:
    """返回 "中国|浙江省|杭州市|电信|CN" 形式字符串；任何失败返回 None。"""
    s = _load_searcher()
    if s is None:
        return None
    try:
        return s.search(ip)
    except Exception:
        return None
