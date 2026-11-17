"""代理探测：经代理测试小红书可达性 + 本地库查询出口归属地。

与 Android 端 ProxyFetcher 同语义：TLS 握手 + HTTP GET /，状态码 200-599 视为可达。
使用线程池 + 阻塞 socket（实现简单、行为与 Kotlin 端一致、无第三方依赖）。
"""

from __future__ import annotations

import re
import socket
import ssl
import time
from concurrent.futures import ThreadPoolExecutor

from .geoip import lookup
from .provinces import classify_province

XHS_HOST = "edith.xiaohongshu.com"
XHS_PORT = 443
PROBE_CONNECT_TIMEOUT = 6.0   # 代理握手超时
PROBE_READ_TIMEOUT = 8.0
PROBE_WORKERS = 16

_HTTP_STATUS = re.compile(r"HTTP/\S*\s+(\d{3})")


def _socks5_connect(sock: socket.socket, target_host: str, target_port: int) -> None:
    """SOCKS5 CONNECT 握手（无认证）。目标统一按域名发送。"""
    host_b = target_host.encode("idna")
    sock.sendall(b"\x05\x01\x00")  # VER=5, 1 method, NO AUTH
    resp = sock.recv(2)
    if len(resp) < 2 or resp[0] != 0x05 or resp[1] != 0x00:
        raise OSError("socks5 no-auth rejected")
    # CMD=CONNECT, ATYP=DOMAIN
    req = b"\x05\x01\x00\x03" + bytes([len(host_b)]) + host_b + target_port.to_bytes(2, "big")
    sock.sendall(req)
    head = sock.recv(4)
    if len(head) < 4 or head[0] != 0x05 or head[1] != 0x00:
        raise OSError(f"socks5 connect failed rep={head[1] if len(head) > 1 else '?'}")
    atyp = head[3]
    if atyp == 0x01:  # IPv4
        _ = sock.recv(4 + 2)
    elif atyp == 0x03:  # DOMAIN
        ln = sock.recv(1)[0]
        _ = sock.recv(ln + 2)
    elif atyp == 0x04:  # IPv6
        _ = sock.recv(16 + 2)
    else:
        raise OSError("socks5 bad atyp")


def _http_connect(sock: socket.socket, target_host: str, target_port: int) -> None:
    """HTTP CONNECT 握手。"""
    req = (
        f"CONNECT {target_host}:{target_port} HTTP/1.1\r\n"
        f"Host: {target_host}:{target_port}\r\n"
        f"User-Agent: Mozilla/5.0\r\n"
        f"Proxy-Connection: keep-alive\r\n\r\n"
    ).encode("ascii")
    sock.sendall(req)
    buf = b""
    sock.settimeout(PROBE_READ_TIMEOUT)
    while b"\r\n\r\n" not in buf and len(buf) < 16384:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    head = buf.split(b"\r\n\r\n", 1)[0].decode("iso-8859-1", errors="replace")
    m = re.match(r"HTTP/\S*\s+(\d{3})", head)
    code = int(m.group(1)) if m else 0
    if not (200 <= code <= 299):
        raise OSError(f"http proxy connect failed code={code}")


def _open_tunnel(proxy, target_host: str, target_port: int) -> socket.socket:
    sock = socket.create_connection((proxy.server, proxy.port), timeout=PROBE_CONNECT_TIMEOUT)
    try:
        sock.settimeout(PROBE_READ_TIMEOUT)
        if proxy.type == "socks5":
            _socks5_connect(sock, target_host, target_port)
        else:  # http / https CONNECT
            _http_connect(sock, target_host, target_port)
        return sock
    except Exception:
        sock.close()
        raise


def probe_xhs(proxy) -> tuple[bool, int | None]:
    """经代理 TLS 握手 + HTTP GET /，返回 (可达, latency_ms)。"""
    start = time.monotonic()
    try:
        sock = _open_tunnel(proxy, XHS_HOST, XHS_PORT)
    except Exception:
        return False, None
    ctx = ssl.create_default_context()
    try:
        tls = ctx.wrap_socket(sock, server_hostname=XHS_HOST)
    except Exception:
        sock.close()
        return False, None
    try:
        latency = int((time.monotonic() - start) * 1000)
        req = (
            f"GET / HTTP/1.1\r\nHost: {XHS_HOST}\r\n"
            f"User-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
        ).encode("ascii")
        tls.sendall(req)
        head = tls.recv(2048).decode("iso-8859-1", errors="replace")
        m = _HTTP_STATUS.search(head)
        if not m:
            return False, latency
        return 200 <= int(m.group(1)) <= 599, latency
    except Exception:
        return False, None
    finally:
        try:
            tls.close()
        except Exception:
            pass


def probe_one(proxy):
    """探测单个候选，返回补齐归属地后的 dict（写库格式）。"""
    xhs_ok, latency = probe_xhs(proxy)
    region_raw = lookup(proxy.server)
    prov, city, lat, lon = classify_province(region_raw)
    # 非中国大陆出口节点标注“境外”（GPS 回退北京坐标，UI 可区分）
    if region_raw:
        first = region_raw.split("|", 1)[0].strip()
        if first and "中国" not in first and "CN" not in region_raw.rsplit("|", 1)[-1]:
            prov, city = "境外", "境外"
    return {
        "server": proxy.server,
        "port": proxy.port,
        "type": proxy.type,
        "province": prov,
        "city": city,
        "latitude": lat,
        "longitude": lon,
        "xhs_reachable": 1 if xhs_ok else 0,
        "latency_ms": latency,
    }


def probe_all(candidates):
    """并发探测，返回 (写库 dict 列表, 探测数)。"""
    out = []
    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as pool:
        for row in pool.map(probe_one, candidates):
            out.append(row)
    return out
