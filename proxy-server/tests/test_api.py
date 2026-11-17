"""API 集成测试：Bearer 鉴权 / 登录 Cookie / 数据落库 / 筛选 / 二维码。"""

import importlib

import pytest
from fastapi.testclient import TestClient

import app.db as db
import app.main as main


@pytest.fixture(autouse=True)
def fresh_db():
    db.init_db()
    with db._conn() as conn:
        conn.execute("DELETE FROM proxies")
        conn.execute("DELETE FROM sources")
        conn.execute("DELETE FROM meta WHERE key='last_update'")
    yield


def _seed():
    rows = [
        dict(server="1.1.1.1", port=1080, type="socks5", province="浙江", city="杭州",
             latitude=30.2741, longitude=120.1551, xhs_reachable=1, latency_ms=120),
        dict(server="2.2.2.2", port=8080, type="http", province="北京", city="北京",
             latitude=39.9042, longitude=116.4074, xhs_reachable=0, latency_ms=None),
        dict(server="3.3.3.3", port=3128, type="http", province="浙江", city="杭州",
             latitude=30.2741, longitude=120.1551, xhs_reachable=1, latency_ms=80),
    ]
    srcs = [type("R", (), {"id": "proxifly_cn", "ok": True, "candidates": [1] * 518, "error": None})(),
            type("R", (), {"id": "geonode_cn", "ok": False, "candidates": [], "error": "timeout"})()]
    db.replace_proxies(rows, srcs, "2026-11-17T10:00:00+0800")


def test_healthz_no_auth():
    client = TestClient(main.app)
    assert client.get("/healthz").status_code == 200
    assert client.get("/healthz").text == "ok"


def test_api_requires_bearer():
    client = TestClient(main.app)
    assert client.get("/api/proxies").status_code == 401
    assert client.get("/api/proxies", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/api/status").status_code == 401


def test_api_proxies_and_filters():
    _seed()
    token = db.get_or_create_token()
    client = TestClient(main.app)
    r = client.get("/api/proxies", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 3
    # 可达置顶，同可达按延迟升序
    assert rows[0]["server"] == "3.3.3.3"
    assert rows[-1]["server"] == "2.2.2.2"

    r = client.get("/api/proxies?province=浙江", headers={"Authorization": f"Bearer {token}"})
    assert [x["server"] for x in r.json()] == ["3.3.3.3", "1.1.1.1"]

    r = client.get("/api/proxies?reachable=true", headers={"Authorization": f"Bearer {token}"})
    assert [x["server"] for x in r.json()] == ["3.3.3.3", "1.1.1.1"]


def test_api_status():
    _seed()
    token = db.get_or_create_token()
    client = TestClient(main.app)
    r = client.get("/api/status", headers={"Authorization": f"Bearer {token}"})
    body = r.json()
    assert body["total"] == 3 and body["reachable"] == 2
    assert body["last_update"] == "2026-11-17T10:00:00+0800"
    ok_map = {s["id"]: s["ok"] for s in body["sources"]}
    assert ok_map == {"proxifly_cn": 1, "geonode_cn": 0}


def test_login_flow_and_dashboard_and_qr():
    _seed()
    client = TestClient(main.app, follow_redirects=False)
    # 未登录访问 / → 302 /login
    assert client.get("/").status_code == 302
    # 密码错误 → 401
    r = client.post("/login", data={"admin_password": "wrong"})
    assert r.status_code == 401
    # 密码正确 → 302 / + cookie
    r = client.post("/login", data={"admin_password": "test-admin-password-123"})
    assert r.status_code == 302
    assert "vlocation_session" in r.cookies
    # 登录后带 cookie 访问 / → 200 且包含 Token
    token = db.get_or_create_token()
    r = client.get("/", cookies=r.cookies)
    assert r.status_code == 200
    assert token in r.text
    # 二维码 png
    r = client.get("/setup/qr.png", cookies=client.cookies)
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.content[:4] == b"\x89PNG"
    # 登出 → cookie 失效后 / 重新 302
    client.get("/logout")
    assert client.get("/").status_code == 302


def test_token_persists():
    t1 = db.get_or_create_token()
    t2 = db.get_or_create_token()
    assert t1 == t2 and len(t1) > 20
