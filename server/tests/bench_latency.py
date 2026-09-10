#!/usr/bin/env python3
"""Latency benchmark against a LIVE chitra server (not a unit test).

Measures what a client actually feels: keep-alive, app time per endpoint
(from the Server-Timing header), a grid of thumbnails fetched browser-style
over 6 persistent connections, and whether a reload is served from cache.

    .venv/bin/python tests/bench_latency.py http://127.0.0.1:8001
    .venv/bin/python tests/bench_latency.py http://192.168.68.74:8001 --strict --json out.json

--strict exits 1 when a threshold is missed (used by the E2E script).
"""
import argparse
import http.client
import json
import statistics
import sys
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

ENDPOINTS = [
    ("health", "/api/health"),
    ("media page 1 (dated)", "/api/media?page=1&per_page=80&dated=1"),
    ("media page 50 (dated)", "/api/media?page=50&per_page=80&dated=1"),
    ("recently uploaded", "/api/media?album=uploads&sort=added&per_page=80"),
    ("undated photos", "/api/media?undated=1&kind=photo&per_page=80"),
    ("timeline", "/api/timeline"),
    ("albums", "/api/albums"),
    ("cameras", "/api/cameras"),
    ("clusters", "/api/clusters"),
    ("memories", "/api/memories"),
    ("faces status", "/api/faces/status"),
]

# Thresholds a healthy server on a LAN should meet (app time, not network).
LIMITS_MS = {
    "media page 1 (dated)": 30,
    "media page 50 (dated)": 40,
    "recently uploaded": 30,
    "health": 60,
    "timeline": 80,
    "albums": 150,
    "cameras": 300,
    "thumb p95": 60,
}


class Conn:
    """One persistent HTTP/1.1 connection; reports whether the server kept it."""

    def __init__(self, base):
        u = urllib.parse.urlsplit(base)
        self.host, self.port = u.hostname, u.port or 80
        self.c = http.client.HTTPConnection(self.host, self.port, timeout=60)
        self.closed_by_server = False

    def get(self, path, headers=None):
        t0 = time.perf_counter()
        try:
            self.c.request("GET", path, headers=headers or {})
            r = self.c.getresponse()
            body = r.read()
        except (http.client.HTTPException, ConnectionError, OSError):
            # Server closed the idle connection: reopen once.
            self.c.close()
            self.c = http.client.HTTPConnection(self.host, self.port, timeout=60)
            self.c.request("GET", path, headers=headers or {})
            r = self.c.getresponse()
            body = r.read()
        wall = (time.perf_counter() - t0) * 1000
        if (r.getheader("Connection") or "").lower() == "close":
            self.closed_by_server = True
        app = None
        st = r.getheader("Server-Timing") or ""
        if "dur=" in st:
            try:
                app = float(st.split("dur=")[1].split(",")[0].split(";")[0])
            except ValueError:
                pass
        return {"status": r.status, "wall": wall, "app": app, "len": len(body),
                "headers": {k.lower(): v for k, v in r.getheaders()}, "body": body}


def bench_endpoints(base, n):
    conn = Conn(base)
    out = []
    for label, path in ENDPOINTS:
        walls, apps, status = [], [], None
        for _ in range(n):
            r = conn.get(path)
            status = r["status"]
            walls.append(r["wall"])
            if r["app"] is not None:
                apps.append(r["app"])
        out.append({
            "label": label, "path": path, "status": status,
            "wall_med": statistics.median(walls), "wall_max": max(walls),
            "app_med": statistics.median(apps) if apps else None,
        })
    return out, not conn.closed_by_server


def bench_thumbs(base, conc):
    page = json.loads(Conn(base).get("/api/media?page=1&per_page=80&dated=1")["body"])
    items = page["items"]
    if not items:
        return None
    local = threading.local()

    def fetch(m, headers=None):
        if not hasattr(local, "conn"):
            local.conn = Conn(base)
        v = m.get("edit_version", 0)
        return local.conn.get(f"/api/media/{m['id']}/thumb?v={v}", headers)

    t0 = time.perf_counter()
    with ThreadPoolExecutor(conc) as ex:
        first = list(ex.map(fetch, items))
    total_first = (time.perf_counter() - t0) * 1000
    lat = sorted(r["wall"] for r in first)
    cc = first[0]["headers"].get("cache-control", "")
    etags = [r["headers"].get("etag") for r in first]

    # Reload with validators: a correct server answers 304 (no body).
    def revalidate(pair):
        m, etag = pair
        return fetch(m, {"If-None-Match": etag} if etag else None)

    t1 = time.perf_counter()
    with ThreadPoolExecutor(conc) as ex:
        second = list(ex.map(revalidate, zip(items, etags)))
    total_second = (time.perf_counter() - t1) * 1000
    return {
        "count": len(items), "conc": conc,
        "total_ms": total_first, "p50": lat[len(lat) // 2],
        "p95": lat[int(len(lat) * 0.95) - 1], "bytes": sum(r["len"] for r in first),
        "cache_control": cc, "immutable": "immutable" in cc,
        "revalidate_304": sum(1 for r in second if r["status"] == 304),
        "revalidate_total_ms": total_second,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("-n", type=int, default=5, help="samples per endpoint")
    ap.add_argument("--conc", type=int, default=6, help="parallel thumb connections (browser default 6)")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    base = a.base.rstrip("/")

    eps, keep_alive = bench_endpoints(base, a.n)
    thumbs = bench_thumbs(base, a.conc)

    fails = []
    print(f"\n{'endpoint':28} {'status':>6} {'app med':>9} {'wall med':>9} {'wall max':>9}")
    for e in eps:
        app = f"{e['app_med']:.1f}ms" if e["app_med"] is not None else "n/a"
        flag = ""
        lim = LIMITS_MS.get(e["label"])
        if lim is not None and e["app_med"] is not None and e["app_med"] > lim:
            flag = f"  > {lim}ms"
            fails.append(f"{e['label']} app {e['app_med']:.1f}ms > {lim}ms")
        if e["status"] != 200:
            flag += f"  HTTP {e['status']}"
            fails.append(f"{e['label']} HTTP {e['status']}")
        print(f"{e['label']:28} {e['status']:>6} {app:>9} {e['wall_med']:>8.1f}ms {e['wall_max']:>8.1f}ms{flag}")
    print(f"\nkeep-alive: {'yes' if keep_alive else 'NO (server sends Connection: close)'}")
    if not keep_alive:
        fails.append("no keep-alive")
    if thumbs:
        print(f"thumbs: {thumbs['count']} over {thumbs['conc']} connections in {thumbs['total_ms']:.0f}ms "
              f"(p50 {thumbs['p50']:.1f}ms, p95 {thumbs['p95']:.1f}ms, {thumbs['bytes'] // 1024} KB)")
        print(f"        Cache-Control: {thumbs['cache_control'] or 'MISSING'}")
        print(f"        reload with ETag: {thumbs['revalidate_304']}/{thumbs['count']} answered 304 "
              f"in {thumbs['revalidate_total_ms']:.0f}ms")
        if thumbs["p95"] > LIMITS_MS["thumb p95"]:
            fails.append(f"thumb p95 {thumbs['p95']:.1f}ms > {LIMITS_MS['thumb p95']}ms")
        if "max-age" not in thumbs["cache_control"]:
            fails.append("thumbs have no Cache-Control max-age")
        if thumbs["revalidate_304"] != thumbs["count"]:
            fails.append("thumb revalidation did not return 304 for every tile")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"base": base, "endpoints": eps, "keep_alive": keep_alive,
                       "thumbs": thumbs, "fails": fails}, f, indent=2)
    if fails:
        print("\nTHRESHOLD MISSES:")
        for f in fails:
            print("  -", f)
    else:
        print("\nall thresholds met")
    if a.strict and fails:
        sys.exit(1)


if __name__ == "__main__":
    main()
