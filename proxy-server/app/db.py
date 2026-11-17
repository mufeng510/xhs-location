"""SQLite 存储：proxies（仅最新一批）、sources（各源状态）、meta（token/last_update）。"""

from __future__ import annotations

import os
import sqlite3
import threading
import time

_DB_PATH = os.environ.get("PROXY_DB", "/data/proxy.db")
_init_lock = threading.Lock()
_initialized = False

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS proxies(
  server TEXT NOT NULL,
  port INTEGER NOT NULL,
  type TEXT NOT NULL,
  province TEXT NOT NULL,
  city TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  xhs_reachable INTEGER NOT NULL,
  latency_ms INTEGER,
  PRIMARY KEY(server, port)
);
CREATE TABLE IF NOT EXISTS sources(
  id TEXT PRIMARY KEY,
  ok INTEGER NOT NULL,
  candidates INTEGER NOT NULL,
  error TEXT,
  checked_at TEXT NOT NULL
);
"""


def db_path() -> str:
    return _DB_PATH


def _conn() -> sqlite3.Connection:
    conn = sqlite3.connect(_DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    global _initialized
    with _init_lock:
        if _initialized:
            return
        os.makedirs(os.path.dirname(_DB_PATH) or ".", exist_ok=True)
        with _conn() as conn:
            conn.executescript(_SCHEMA)
        _initialized = True


def get_meta(key: str) -> str | None:
    init_db()
    with _conn() as conn:
        row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row["value"] if row else None


def set_meta(key: str, value: str) -> None:
    init_db()
    with _conn() as conn:
        conn.execute(
            "INSERT INTO meta(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )


def get_or_create_token() -> str:
    """Token 首次生成并持久化；重建容器（保留 volume）后不变。"""
    token = get_meta("api_token")
    if token:
        return token
    import secrets

    token = secrets.token_urlsafe(32)
    set_meta("api_token", token)
    return token


def replace_proxies(rows: list[dict], source_results: list, update_ts: str) -> None:
    init_db()
    with _conn() as conn:
        conn.execute("DELETE FROM proxies")
        conn.executemany(
            "INSERT INTO proxies(server, port, type, province, city, latitude,"
            " longitude, xhs_reachable, latency_ms)"
            " VALUES(:server, :port, :type, :province, :city, :latitude,"
            " :longitude, :xhs_reachable, :latency_ms)",
            rows,
        )
        conn.execute("DELETE FROM sources")
        conn.executemany(
            "INSERT INTO sources(id, ok, candidates, error, checked_at)"
            " VALUES(?,?,?,?,?)",
            [
                (r.id, 1 if r.ok else 0, len(r.candidates), r.error, update_ts)
                for r in source_results
            ],
        )
        conn.execute(
            "INSERT INTO meta(key, value) VALUES('last_update', ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (update_ts,),
        )


def list_proxies(province: str | None = None, reachable: bool | None = None) -> list[dict]:
    init_db()
    sql = "SELECT * FROM proxies"
    clauses, args = [], []
    if province:
        clauses.append("province = ?")
        args.append(province)
    if reachable is not None:
        clauses.append("xhs_reachable = ?")
        args.append(1 if reachable else 0)
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY xhs_reachable DESC, latency_ms ASC, server ASC"
    with _conn() as conn:
        return [dict(r) for r in conn.execute(sql, args).fetchall()]


def status_summary() -> dict:
    init_db()
    with _conn() as conn:
        total = conn.execute("SELECT COUNT(*) AS c FROM proxies").fetchone()["c"]
        reachable = conn.execute(
            "SELECT COUNT(*) AS c FROM proxies WHERE xhs_reachable = 1").fetchone()["c"]
        sources = [dict(r) for r in conn.execute(
            "SELECT id, ok, candidates, error, checked_at FROM sources").fetchall()]
    return {
        "last_update": get_meta("last_update"),
        "total": total,
        "reachable": reachable,
        "sources": sources,
    }


def count_by_province() -> dict[str, int]:
    init_db()
    with _conn() as conn:
        rows = conn.execute(
            "SELECT province, COUNT(*) AS c FROM proxies GROUP BY province").fetchall()
        return {r["province"]: r["c"] for r in rows}


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")
