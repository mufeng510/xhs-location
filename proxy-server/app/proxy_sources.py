"""免费代理数据源注册表（从 Android 端 ProxySources.kt 移植）。

每个源独立抓取，单个源失败不影响其他源；合并后按
“源优先级（注册顺序）+ 源内质量排序”轮转取样、ip:port 去重，
截断 MAX_FETCH_CANDIDATES 个进入实测探测。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Callable

import urllib.request

from .provinces import classify_province

MAX_FETCH_CANDIDATES = 60

_TIMEOUT = 12


@dataclass
class ProxyCandidate:
    server: str
    port: int
    type: str  # "socks5" | "http"
    label: str
    latitude: float
    longitude: float
    city: str

    def key(self) -> str:
        return f"{self.server.lower()}:{self.port}"


@dataclass
class SourceResult:
    id: str
    ok: bool
    candidates: list[ProxyCandidate] = field(default_factory=list)
    error: str | None = None


def _http_get(url: str, accept_json: bool = False) -> str:
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (xhs-proxy-server)",
    })
    if accept_json:
        req.add_header("Accept", "application/json")
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return resp.read().decode("utf-8", errors="replace")


def parse_proxy_line(raw: str):
    """解析 `socks5://1.2.3.4:1080 #cn` / `1.2.3.4:1080` 等行。

    返回 (server, port, type, country_tag_or_None)，无法解析返回 None。
    """
    line = raw.strip()
    if not line or line.startswith("#"):
        return None
    m = re.match(
        r"^(?:(socks5|socks4|https?)://)?([0-9]{1,3}(?:\.[0-9]{1,3}){3}|[A-Za-z0-9.-]+):([0-9]{1,5})(?:\s*#\s*([A-Za-z]{2}))?\s*$",
        line,
    )
    if not m:
        return None
    proto, host, port_s, tag = m.groups()
    port = int(port_s)
    if port <= 0 or port > 65535 or not host:
        return None
    proto = (proto or "").lower()
    if proto in ("", "socks5"):
        ptype = "socks5"
    elif proto in ("http", "https"):
        ptype = "http"
    else:
        return None  # socks4 等协议不支持
    return host, port, ptype, (tag or "").lower() or None


def parse_proxy_lines(text: str, country_filter: str | None = None):
    """每行一个代理；country_filter="cn" 时只保留无标签或标签匹配的行。"""
    out = []
    for raw in text.splitlines():
        parsed = parse_proxy_line(raw)
        if not parsed:
            continue
        host, port, ptype, tag = parsed
        if country_filter:
            want = country_filter.lower()
            if tag is not None and tag != want:
                continue
        out.append((host, port, ptype))
    return out


def _parse_geonode_json(text: str) -> list[ProxyCandidate]:
    """兼容裸数组（proxyfreeonly）与 {"data": [...]} 信封（geonode 官方）。"""
    text = text.strip()
    try:
        data = json.loads(text)
    except Exception:
        return []
    arr = data if isinstance(data, list) else data.get("data") if isinstance(data, dict) else None
    if not isinstance(arr, list):
        return []
    out: list[tuple[float, ProxyCandidate]] = []
    for item in arr:
        try:
            ip = str(item.get("ip") or "").strip()
            port = int(str(item.get("port", "0")).split(":")[0] or 0)
            if not ip or port <= 0:
                continue
            protos = [str(p).lower() for p in (item.get("protocols") or [])]
            if "socks5" in protos:
                ptype = "socks5"
            elif "http" in protos or "https" in protos:
                ptype = "http"
            else:
                continue
            prov, city, lat, lon = classify_province(str(item.get("city") or ""))
            city_display = prov
            uptime = float(item.get("upTime") or -1.0)
            out.append((uptime, ProxyCandidate(ip, port, ptype, f"{prov} · 未知归属", lat, lon, city_display)))
        except Exception:
            continue
    out.sort(key=lambda pair: pair[0], reverse=True)
    return [c for _, c in out]


def _parse_proxifly_json(text: str) -> list[ProxyCandidate]:
    """proxifly proxies/countries/CN/data.json：protocol/ip/port/score/geolocation.city。"""
    try:
        arr = json.loads(text.strip())
    except Exception:
        return []
    if not isinstance(arr, list):
        return []
    out: list[tuple[int, ProxyCandidate]] = []
    for item in arr:
        try:
            ip = str(item.get("ip") or "").strip()
            port = int(item.get("port") or 0)
            proto = str(item.get("protocol") or "").lower()
            if not ip or port <= 0:
                continue
            if proto == "socks5":
                ptype = "socks5"
            elif proto in ("http", "https"):
                ptype = "http"
            else:
                continue
            city = (item.get("geolocation") or {}).get("city") or ""
            prov, _, lat, lon = classify_province(str(city))
            out.append((int(item.get("score") or 0),
                        ProxyCandidate(ip, port, ptype, f"{prov} · 未知归属", lat, lon, prov)))
        except Exception:
            continue
    out.sort(key=lambda pair: pair[0], reverse=True)
    return [c for _, c in out]


def _parse_proxyscrape_json(text: str) -> list[ProxyCandidate]:
    """ProxyScrape cn/data.json：protocol/ip/port/uptime_percent/city。"""
    try:
        arr = json.loads(text.strip())
    except Exception:
        return []
    if not isinstance(arr, list):
        return []
    out: list[tuple[float, ProxyCandidate]] = []
    for item in arr:
        try:
            ip = str(item.get("ip") or "").strip()
            port = int(item.get("port") or 0)
            proto = str(item.get("protocol") or "").lower()
            if not ip or port <= 0:
                continue
            if proto == "socks5":
                ptype = "socks5"
            elif proto in ("http", "https"):
                ptype = "http"
            else:
                continue
            prov, _, lat, lon = classify_province(str(item.get("city") or ""))
            out.append((float(item.get("uptime_percent") or -1.0),
                        ProxyCandidate(ip, port, ptype, f"{prov} · 未知归属", lat, lon, prov)))
        except Exception:
            continue
    out.sort(key=lambda pair: pair[0], reverse=True)
    return [c for _, c in out]


def _fetch_proxifly() -> list[ProxyCandidate]:
    return _parse_proxifly_json(_http_get(
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/CN/data.json",
        accept_json=True))


def _fetch_proxyscrape() -> list[ProxyCandidate]:
    return _parse_proxyscrape_json(_http_get(
        "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/cn/data.json",
        accept_json=True))


def _fetch_proxyfreeonly() -> list[ProxyCandidate]:
    return _parse_geonode_json(_http_get(
        "https://proxyfreeonly.com/api/free-proxy-list?limit=150&page=1&country=CN&sortBy=lastChecked&sortType=desc",
        accept_json=True))


def _fetch_geonode() -> list[ProxyCandidate]:
    return _parse_geonode_json(_http_get(
        "https://proxylist.geonode.com/api/proxy-list?country=CN&limit=100&sort_by=upTime&sort_type=desc",
        accept_json=True))


def _fetch_proxyclean() -> list[ProxyCandidate]:
    text = _http_get(
        "https://raw.githubusercontent.com/HankNovic/ProxyClean/refs/heads/main/SOCKS5.txt")
    out = []
    for host, port, ptype in parse_proxy_lines(text, country_filter="cn"):
        prov, _, lat, lon = classify_province("")
        out.append(ProxyCandidate(host, port, ptype, f"{prov} · 未知归属", lat, lon, prov))
    return out


# (id, 展示名, 抓取函数)。顺序即合并优先级（与 Android 端一致）。
SOURCES: list[tuple[str, str, Callable[[], list[ProxyCandidate]]]] = [
    ("proxifly_cn", "proxifly（GitHub）", _fetch_proxifly),
    ("proxyscrape_cn", "ProxyScrape（GitHub）", _fetch_proxyscrape),
    ("proxyfreeonly", "proxyfreeonly.com", _fetch_proxyfreeonly),
    ("proxyclean_cn", "ProxyClean（GitHub）", _fetch_proxyclean),
    ("geonode_cn", "Geonode 官方 API", _fetch_geonode),
]


def merge_candidates(per_source: list[list[ProxyCandidate]], max_n: int) -> list[ProxyCandidate]:
    """轮转取样 + ip:port 去重。某源少不影响其他源继续供给。"""
    if max_n <= 0:
        return []
    seen: set[str] = set()
    out: list[ProxyCandidate] = []
    round_idx = 0
    while len(out) < max_n:
        progressed = False
        for lst in per_source:
            if round_idx >= len(lst):
                continue
            progressed = True
            cand = lst[round_idx]
            if cand.key() in seen:
                continue
            seen.add(cand.key())
            out.append(cand)
            if len(out) >= max_n:
                break
        if not progressed:
            break
        round_idx += 1
    return out


def fetch_all() -> tuple[list[ProxyCandidate], list[SourceResult]]:
    """并发抓取所有源（线程池），返回 (合并去重截断后的候选, 各源状态)。"""
    from concurrent.futures import ThreadPoolExecutor

    per_source: list[list[ProxyCandidate]] = []
    results: list[SourceResult] = []
    with ThreadPoolExecutor(max_workers=len(SOURCES)) as pool:
        futures = {pool.submit(fn): (sid, name) for sid, name, fn in SOURCES}
        for fut, (sid, name) in futures.items():
            try:
                cand = fut.result()
                per_source.append(cand)
                results.append(SourceResult(id=sid, ok=True, candidates=cand))
            except Exception as exc:
                results.append(SourceResult(id=sid, ok=False, error=str(exc)[:200]))
    merged = merge_candidates(per_source, MAX_FETCH_CANDIDATES)
    return merged, results
