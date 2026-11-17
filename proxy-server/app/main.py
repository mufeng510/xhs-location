"""vlocation 代理池服务：抓取 → 实测 → SQLite → API + 管理页。

配置（仅 ADMIN_PASSWORD 必填，其余可选带默认值）：
  ADMIN_PASSWORD   管理页登录密码
  PORT             监听端口（默认 8080）
  REFRESH_MINUTES  刷新周期分钟数（默认 20，最小 5）
  PROXY_DB         SQLite 路径（默认 /data/proxy.db）
  IP2REGION_XDB    ip2region xdb 路径（默认与 PROXY_DB 同目录）
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from urllib.parse import quote, urlencode

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response

from . import admin, auth, config, db
from .provinces import all_provinces
from .refresh import refresh_once

config.validate()


def _refresh_sync() -> None:
    try:
        refresh_once()
    except Exception as exc:
        print(f"[refresh] 失败（将在下一轮重试）：{exc}", flush=True)


async def _refresh_loop() -> None:
    # 启动后立即刷新一次，后续按周期执行；单任务串行，避免重入
    await asyncio.to_thread(_refresh_sync)
    while True:
        await asyncio.sleep(config.REFRESH_MINUTES * 60)
        await asyncio.to_thread(_refresh_sync)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    db.get_or_create_token()
    task = asyncio.create_task(_refresh_loop())
    yield
    task.cancel()


app = FastAPI(title="vlocation proxy pool", docs_url=None, redoc_url=None,
              openapi_url=None, lifespan=lifespan)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    resp = await call_next(request)
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "DENY"
    resp.headers["Referrer-Policy"] = "no-referrer"
    return resp


# ========== 公开 ==========

@app.get("/healthz")
def healthz() -> Response:
    return Response("ok", media_type="text/plain")


# ========== 管理页 ==========

def _base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


@app.get("/login", response_class=HTMLResponse)
def login_form() -> str:
    return admin.render_login()


@app.post("/login", response_class=HTMLResponse)
def login_submit(request: Request, admin_password: str = Form(...)):
    client = request.client.host if request.client else "unknown"
    if auth.login_rate_limited(client):
        return HTMLResponse(admin.render_login("尝试次数过多，请 5 分钟后再试"), status_code=429)
    import hmac

    if admin_password and hmac.compare_digest(admin_password.encode(), config.ADMIN_PASSWORD.encode()):
        auth.login_succeeded(client)
        resp = RedirectResponse("/", status_code=302)
        resp.set_cookie(auth.COOKIE_NAME, auth.session_value(config.ADMIN_PASSWORD),
                        httponly=True, samesite="lax", path="/",
                        max_age=auth.COOKIE_MAX_AGE)
        return resp
    auth.login_failed(client)
    print(f"[auth] 登录失败来源 {client}", flush=True)
    return HTMLResponse(admin.render_login("密码错误"), status_code=401)


@app.get("/logout")
def logout():
    resp = RedirectResponse("/login", status_code=302)
    resp.delete_cookie(auth.COOKIE_NAME, path="/")
    return resp


def _dashboard_data(request: Request):
    token = db.get_or_create_token()
    proxies = db.list_proxies()
    summary = db.status_summary()
    counts = db.count_by_province()
    order = [p[0] for p in all_provinces()] + ["境外", "其他地区"]
    grouped: dict[str, list[dict]] = {k: [] for k in order}
    for row in proxies:
        grouped.setdefault(row["province"], [])
    # 分组顺序按 34 省的注册顺序，未匹配的排最后
    order_set = {k: i for i, k in enumerate(order)}
    provinces = sorted(grouped.keys(), key=lambda k: order_set.get(k, 999))
    groups = [(p, grouped[p]) for p in provinces if grouped[p]]
    return token, summary, groups, _base_url(request)


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    if not auth.verify_session(request.cookies.get(auth.COOKIE_NAME), config.ADMIN_PASSWORD):
        return RedirectResponse("/login", status_code=302)
    token, summary, groups, base_url = _dashboard_data(request)
    return admin.render_dashboard(
        token=token, last_update=summary["last_update"],
        total=summary["total"], reachable=summary["reachable"],
        sources=summary["sources"], groups=groups, base_url=base_url)


@app.get("/setup/qr.png")
def setup_qr(request: Request):
    if not auth.verify_session(request.cookies.get(auth.COOKIE_NAME), config.ADMIN_PASSWORD):
        return Response("unauthorized", status_code=401)
    token = db.get_or_create_token()
    payload = "vlocation://setup?" + urlencode(
        {"url": _base_url(request), "token": token}, quote_via=quote)
    import io

    import segno

    buf = io.BytesIO()
    segno.make(payload, error="m").save(buf, kind="png", scale=6, border=2)
    return Response(buf.getvalue(), media_type="image/png",
                    headers={"Cache-Control": "no-store"})


# ========== API（Android App） ==========

def _authed(request: Request) -> bool:
    token = db.get_or_create_token()
    return auth.verify_token(request.headers.get("authorization"), token)


@app.get("/api/proxies")
def api_proxies(request: Request, province: str | None = None, reachable: bool | None = None):
    if not _authed(request):
        return JSONResponse({"detail": "unauthorized"}, status_code=401)
    if province is not None and len(province) > 20:
        return JSONResponse({"detail": "invalid province"}, status_code=400)
    return db.list_proxies(province, reachable)


@app.get("/api/status")
def api_status(request: Request):
    if not _authed(request):
        return JSONResponse({"detail": "unauthorized"}, status_code=401)
    return db.status_summary()
