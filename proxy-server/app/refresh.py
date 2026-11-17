"""一轮刷新：抓取 → 合并 → 探测 → 入库（单事务替换）。"""

from __future__ import annotations

import time

from .db import replace_proxies, now_iso
from .probe import probe_all
from .proxy_sources import fetch_all


def refresh_once() -> dict:
    t0 = time.monotonic()
    candidates, source_results = fetch_all()
    print(f"[refresh] 抓取完成：合并候选 {len(candidates)} 个（"
          f"{', '.join(f'{r.id}:{len(r.candidates) if r.ok else '失败'}' for r in source_results)}）",
          flush=True)
    rows = probe_all(candidates) if candidates else []
    update_ts = now_iso()
    replace_proxies(rows, source_results, update_ts)
    summary = {
        "total": len(rows),
        "reachable": sum(r["xhs_reachable"] for r in rows),
        "update_ts": update_ts,
        "elapsed_sec": round(time.monotonic() - t0, 1),
    }
    print(f"[refresh] 入库完成：{summary['total']} 个节点，可达 {summary['reachable']}，"
          f"耗时 {summary['elapsed_sec']}s", flush=True)
    return summary
