"""管理页 HTML（server-rendered，无前端框架）。动态值一律 html.escape。"""

from __future__ import annotations

import html
import time

_PAGE_HEAD = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="120">
<title>vlocation · 代理池</title>
<style>
:root { --bg:#f6f7f9; --card:#fff; --border:#e3e6ea; --fg:#1f2329; --muted:#6b7280;
        --accent:#0b57d0; --ok:#137333; --bad:#b3261e; --chip:#eef2f7; }
* { box-sizing: border-box; }
body { margin:0; font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans CJK SC",sans-serif;
       background:var(--bg); color:var(--fg); }
.wrap { max-width:980px; margin:0 auto; padding:16px 14px 40px; }
h1 { font-size:20px; margin:8px 0 2px; }
h2 { font-size:16px; margin:0 0 8px; }
.muted { color:var(--muted); }
.card { background:var(--card); border:1px solid var(--border); border-radius:12px;
        padding:14px 16px; margin:12px 0; }
.stats { display:flex; gap:12px; flex-wrap:wrap; }
.stat { flex:1 1 140px; background:var(--card); border:1px solid var(--border);
        border-radius:12px; padding:12px 14px; }
.stat .n { font-size:26px; font-weight:700; }
.stat .t { color:var(--muted); font-size:12px; }
.badge { display:inline-block; padding:1px 8px; border-radius:999px; font-size:12px;
         background:var(--chip); color:var(--muted); }
.badge.ok { background:#e6f4ea; color:var(--ok); }
.badge.bad { background:#fce8e6; color:var(--bad); }
table { width:100%; border-collapse:collapse; }
th,td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--border);
        font-variant-numeric: tabular-nums; }
th { color:var(--muted); font-weight:600; font-size:12px; }
code { background:#f1f3f4; border-radius:6px; padding:2px 6px; font-size:13px; }
.token { font-size:13px; word-break:break-all; }
.qr { display:flex; gap:18px; align-items:center; flex-wrap:wrap; }
.qr img { border:1px solid var(--border); border-radius:8px; background:#fff; }
form.inline { display:flex; gap:8px; align-items:center; }
input[type=password],input[type=text] { font-size:14px; padding:8px 10px; border:1px solid var(--border);
        border-radius:8px; width:260px; }
button { font-size:14px; padding:8px 16px; border:0; border-radius:8px; background:var(--accent);
         color:#fff; cursor:pointer; }
.err { color:var(--bad); }
a { color:var(--accent); text-decoration:none; }
</style>
</head>
<body>
"""
_PAGE_FOOT = """</body></html>
"""


def esc(v) -> str:
    return html.escape(str(v if v is not None else ""))


def _rel(iso_ts: str | None) -> tuple[str, str]:
    """返回 (绝对时间文本, 相对时间文本)。"""
    if not iso_ts:
        return "暂无", "尚未完成首次刷新"
    try:
        from datetime import datetime

        dt = datetime.strptime(iso_ts, "%Y-%m-%dT%H:%M:%S%z")
        ts = dt.timestamp()
        delta = int(time.time() - ts)
        if delta < 0:
            rel = "刚刚"
        elif delta < 60:
            rel = f"{delta} 秒前"
        elif delta < 3600:
            rel = f"{delta // 60} 分钟前"
        elif delta < 86400:
            rel = f"{delta // 3600} 小时前"
        else:
            rel = f"{delta // 86400} 天前"
        absolute = dt.strftime("%Y-%m-%d %H:%M:%S %z")
        return absolute, rel
    except Exception:
        return iso_ts, "时间解析失败"


def render_login(error: str | None = None) -> str:
    err_html = f'<p class="err">{esc(error)}</p>' if error else ""
    return (
        _PAGE_HEAD
        + """
<div class="wrap" style="max-width:420px">
  <h1>vlocation · 代理池</h1>
  <p class="muted">管理页，仅自己可见。输入管理密码登录。</p>
  <div class="card">
    <form class="inline" method="post" action="/login">
      <input type="password" name="admin_password" placeholder="管理密码" autofocus autocomplete="current-password">
      <button type="submit">登录</button>
    </form>
    """
        + err_html
        + """
  </div>
</div>
"""
        + _PAGE_FOOT
    )


def render_dashboard(*, token: str, last_update: str | None, total: int,
                     reachable: int, sources: list[dict],
                     groups: list[tuple[str, list[dict]]],
                     base_url: str) -> str:
    abs_ts, rel_ts = _rel(last_update)

    src_rows = ""
    for s in sources:
        ok = bool(s.get("ok"))
        badge = '<span class="badge ok">✓ 正常</span>' if ok else '<span class="badge bad">✗ 失败</span>'
        detail = f'{esc(s.get("candidates", 0))} 个候选' if ok else esc(s.get("error") or "未知错误")
        src_rows += (
            f'<tr><td>{esc(s.get("id"))}</td><td>{badge}</td>'
            f'<td class="muted">{detail}</td></tr>'
        )

    group_html = ""
    for province, rows in groups:
        ok_n = sum(1 for r in rows if r["xhs_reachable"])
        group_html += f'<h2>{esc(province)} <span class="muted">（{ok_n}/{len(rows)} 可达）</span></h2>'
        if not rows:
            group_html += '<p class="muted">暂无节点。</p>'
            continue
        group_html += (
            "<table><thead><tr><th>服务器</th><th>类型</th><th>城市</th>"
            "<th>延迟</th><th>小红书</th></tr></thead><tbody>"
        )
        for r in rows:
            xhs = '<span class="badge ok">✓ 可达</span>' if r["xhs_reachable"] else '<span class="badge bad">✗</span>'
            lat = r["latency_ms"]
            lat_s = f"{lat} ms" if lat is not None else "—"
            group_html += (
                f'<tr><td><code>{esc(r["server"])}:{esc(r["port"])}</code></td>'
                f'<td>{esc(str(r["type"]).upper())}</td>'
                f'<td>{esc(r["city"])}</td>'
                f'<td>{esc(lat_s)}</td>'
                f'<td>{xhs}</td></tr>'
            )
        group_html += "</tbody></table>"

    return (
        _PAGE_HEAD
        + f"""
<div class="wrap">
  <h1>vlocation · 代理池</h1>
  <p class="muted">更新于 {esc(abs_ts)}（{esc(rel_ts)}） · <a href="/logout">退出登录</a></p>

  <div class="stats">
    <div class="stat"><div class="n">{esc(total)}</div><div class="t">当前节点数</div></div>
    <div class="stat"><div class="n">{esc(reachable)}</div><div class="t">小红书可达</div></div>
    <div class="stat"><div class="n">{esc(sum(1 for s in sources if s.get('ok')))}/{esc(len(sources))}</div><div class="t">数据源正常</div></div>
  </div>

  <div class="card">
    <h2>手机 App 配置（扫码配对）</h2>
    <div class="qr">
      <img src="/setup/qr.png" width="176" height="176" alt="配对二维码">
      <div>
        <p class="muted">手机安装 vlocation → 设置页 →「扫码配对」，扫描左侧二维码即自动填入服务器地址与 Token。</p>
        <p>服务器：<code>{esc(base_url)}</code></p>
        <p>API Token（手机 App 用它调用 <code>/api/proxies</code>）：</p>
        <p class="token"><code id="tok">{esc(token)}</code>
          <button onclick="navigator.clipboard.writeText(document.getElementById('tok').innerText)">复制</button></p>
        <p class="muted">Token 仅保存在 NAS 数据库（./data volume），重建容器不变；泄露时清空 volume 重启即重新生成。</p>
      </div>
    </div>
  </div>

  <div class="card">
    <h2>数据源状态</h2>
    <table><tbody>{src_rows or '<tr><td class="muted">暂无（首轮刷新尚未完成）</td></tr>'}</tbody></table>
  </div>

  <div class="card">
    <h2>可用节点（按地区）</h2>
    {group_html or '<p class="muted">首轮刷新进行中，请稍候……</p>'}
  </div>
</div>
"""
        + _PAGE_FOOT
    )
