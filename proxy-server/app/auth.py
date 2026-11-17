"""认证：管理页 Cookie 会话 + API Bearer Token。

会话 Cookie 值为 HMAC-SHA256(ADMIN_PASSWORD, "vlocation-session") 的 hex，
改密码即时失效；不存服务端会话状态。
"""

from __future__ import annotations

import hashlib
import hmac
import time
from collections import defaultdict, deque

COOKIE_NAME = "vlocation_session"
COOKIE_MAX_AGE = 30 * 24 * 3600  # 30 天


def session_value(admin_password: str) -> str:
    return hmac.new(admin_password.encode(), b"vlocation-session", hashlib.sha256).hexdigest()


def verify_session(cookie_value: str | None, admin_password: str) -> bool:
    if not cookie_value:
        return False
    return hmac.compare_digest(cookie_value, session_value(admin_password))


def verify_token(auth_header: str | None, token: str) -> bool:
    if not auth_header or not auth_header.startswith("Bearer "):
        return False
    return hmac.compare_digest(auth_header[7:].strip(), token)


# --- 登录限流（内存版，防暴力破解；重启后清零） ---
_FAIL_WINDOW = 300  # 秒
_FAIL_MAX = 5
_fails: dict[str, deque[float]] = defaultdict(deque)


def login_rate_limited(ip: str) -> bool:
    now = time.monotonic()
    dq = _fails[ip]
    while dq and now - dq[0] > _FAIL_WINDOW:
        dq.popleft()
    return len(dq) >= _FAIL_MAX


def login_failed(ip: str) -> None:
    _fails[ip].append(time.monotonic())


def login_succeeded(ip: str) -> None:
    _fails.pop(ip, None)
