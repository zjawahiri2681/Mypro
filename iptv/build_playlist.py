#!/usr/bin/env python3
"""Build a health-checked India + Hindi (worldwide) + Sports M3U playlist.

Pulls public free-to-air playlists, groups every stream URL by channel,
tests each channel's URLs (last known-good first) and keeps the first one
that actually plays. Dead channels are left out until a later run finds
a working link again.

Standard library only. Run:  python3 iptv/build_playlist.py --out dist
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import re
import ssl
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
TIMEOUT = 8
MAX_CANDIDATES = 8
WORKERS = 64
STRICT = False  # True = also download the first video segment before accepting a link
PLAYLIST_NAME = "india_hindi_sports.m3u"
PREVIOUS_URL = "https://github.com/zjawahiri2681/Mypro/releases/download/iptv-playlist/" + PLAYLIST_NAME

# (source tag, url, optional group-title filter). First reachable URL in each tuple list wins.
SOURCES: list[tuple[str, list[str], str | None]] = [
    ("india", ["https://iptv-org.github.io/iptv/countries/in.m3u",
               "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u"], None),
    ("hindi", ["https://iptv-org.github.io/iptv/languages/hin.m3u"], None),
    ("urdu", ["https://iptv-org.github.io/iptv/languages/urd.m3u"], None),
    ("punjabi", ["https://iptv-org.github.io/iptv/languages/pan.m3u"], None),
    ("sports", ["https://iptv-org.github.io/iptv/categories/sports.m3u"], None),
    ("india", ["https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"], "india"),
]

# Subscription networks are never free legitimately; anything matching is an
# unauthorised restream and is dropped whatever the source.
PAID_RE = re.compile(
    r"\b(star\s*(sports|plus|gold|movies|bharat|utsav|maa|vijay|jalsha|pravah|suvarna)|"
    r"sony\s*(ten|six|sports|max|sab|pal|wah|aath|yay|bbc|pix|entertainment|marathi)|"
    r"colors|sports\s*18|jio\s*(cinema|tv|hotstar)|hotstar|willow|sky\s*sports|bein|dazn|"
    r"ten\s*[1-5]\b|eurosport|tnt\s*sports|espn|fox\s*sports|supersport|zee\s*(cinema|tv|anmol|cafe|action|classic|bollywood))",
    re.I)

RES_RE = re.compile(r"\((\d{3,4})p\)")
TAG_RE = re.compile(r"\s*(\(\d{3,4}p\)|\[[^\]]*\])")
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')


@dataclass
class Stream:
    url: str
    name: str
    attrs: dict
    opts: list[str]          # #EXTVLCOPT lines (referrer / user-agent)
    source: str

    @property
    def height(self) -> int:
        m = RES_RE.search(self.name)
        return int(m.group(1)) if m else 0

    def headers(self) -> dict:
        h = {"User-Agent": UA}
        for o in self.opts:
            k, _, v = o.partition("=")
            k = k.replace("#EXTVLCOPT:", "").strip().lower()
            if k == "http-user-agent":
                h["User-Agent"] = v.strip()
            elif k == "http-referrer":
                h["Referer"] = v.strip()
        return h


@dataclass
class Channel:
    key: str
    streams: list[Stream] = field(default_factory=list)
    sources: set = field(default_factory=set)


def fetch_text(url: str, timeout: int = 30) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def parse_m3u(text: str, source: str, group_filter: str | None = None) -> list[Stream]:
    out, info, opts = [], None, []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            info, opts = line, []
        elif line.startswith("#EXTVLCOPT"):
            opts.append(line)
        elif line.startswith("#"):
            continue
        elif info:
            head, _, name = info.rpartition(",")
            attrs = dict(ATTR_RE.findall(head))
            if PAID_RE.search(name):
                pass
            elif not group_filter or group_filter in attrs.get("group-title", "").lower():
                out.append(Stream(line, name.strip(), attrs, opts, source))
            info, opts = None, []
    return out


def channel_key(s: Stream) -> str:
    tid = s.attrs.get("tvg-id", "").split("@")[0].strip().lower()
    if tid:
        return tid
    return re.sub(r"[^a-z0-9]", "", TAG_RE.sub("", s.name).lower())


def clean_name(name: str) -> str:
    return TAG_RE.sub("", name).strip()


# ---------------------------------------------------------------- health check
_CTX = ssl.create_default_context()


def _get(url: str, headers: dict, limit: int = 262144) -> tuple[int, str, bytes, str]:
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT, context=_CTX) as r:
        return r.status, r.headers.get("Content-Type", "").lower(), r.read(limit), r.geturl()


def _first_uri(body: str, base: str) -> str | None:
    for line in body.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return urllib.parse.urljoin(base, line)
    return None


def is_alive(s: Stream) -> bool:
    h = s.headers()
    try:
        status, ctype, data, final = _get(s.url, h)
        if status not in (200, 206):
            return False
        text = data[:65536].decode("utf-8", "ignore").lstrip("﻿ \r\n")
        if not text.startswith("#EXTM3U"):
            # direct TS / MP4 / audio stream
            return bool(data) and (data[:1] == b"\x47" or any(t in ctype for t in ("video", "audio", "octet-stream", "mpegurl")))
        if not STRICT:
            # Relaxed: the channel's playlist answers. Video segments are often
            # region-locked to India, so they are not tested from the US runner.
            return True
        # master playlist -> first variant
        if "#EXT-X-STREAM-INF" in text:
            variant = _first_uri(text.split("#EXT-X-STREAM-INF", 1)[1], final)
            if not variant:
                return False
            status, _, data, final = _get(variant, h)
            text = data.decode("utf-8", "ignore")
            if status not in (200, 206) or "#EXTINF" not in text:
                return False
        elif "#EXTINF" not in text:
            return False
        # media playlist -> first segment must download
        seg = _first_uri(text, final)
        if not seg:
            return False
        status, _, data, _ = _get(seg, h, limit=4096)
        return status in (200, 206) and len(data) > 0
    except Exception:
        return False


def pick_working(ch: Channel, previous: dict[str, str]) -> Stream | None:
    seen, ordered = set(), []
    prev_url = previous.get(ch.key)
    for s in sorted(ch.streams, key=lambda s: (s.url != prev_url, -s.height)):
        if s.url not in seen:
            seen.add(s.url)
            ordered.append(s)
    for s in ordered[:MAX_CANDIDATES]:
        if is_alive(s):
            return s
    return None


# ---------------------------------------------------------------- grouping
def group_for(ch: Channel, s: Stream) -> tuple[int, str]:
    cats = [c.strip() for c in s.attrs.get("group-title", "").split(";") if c.strip()]
    indian = "india" in ch.sources or ch.key.endswith(".in")
    if "sports" in ch.sources or any(c.lower() == "sports" for c in cats):
        return (0, "Sports | India") if indian else (5, "Sports | World")
    cat = next((c for c in cats if c.lower() not in ("undefined", "india")), "General")
    if "punjabi" in ch.sources:
        return (3, f"Punjabi | {cat}")
    if "urdu" in ch.sources and "hindi" not in ch.sources:
        return (4, f"Urdu | {cat}")
    return (1, f"India | {cat}") if indian else (2, f"Hindi World | {cat}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="dist")
    ap.add_argument("--min-ratio", type=float, default=0.4,
                    help="refuse to publish if fewer than this share of last run's channels are alive")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    channels: dict[str, Channel] = {}
    for tag, urls, gfilter in SOURCES:
        for url in urls:
            try:
                streams = parse_m3u(fetch_text(url), tag, gfilter)
            except Exception as e:
                print(f"source failed: {url}: {e}", file=sys.stderr)
                continue
            print(f"{len(streams):5d} streams from {url}")
            for s in streams:
                ch = channels.setdefault(channel_key(s), Channel(channel_key(s)))
                ch.streams.append(s)
                ch.sources.add(tag)
            break

    previous: dict[str, str] = {}
    try:
        for s in parse_m3u(fetch_text(PREVIOUS_URL), "prev"):
            previous[channel_key(s)] = s.url
            # keep last known-good URL as a candidate even if the source dropped it
            if channel_key(s) in channels:
                channels[channel_key(s)].streams.append(s)
    except Exception:
        pass
    print(f"{len(channels)} channels, {len(previous)} in previous playlist")

    alive: list[tuple[Channel, Stream]] = []
    replaced = 0
    with cf.ThreadPoolExecutor(WORKERS) as pool:
        futs = {pool.submit(pick_working, ch, previous): ch for ch in channels.values()}
        for f in cf.as_completed(futs):
            ch, s = futs[f], f.result()
            if s:
                alive.append((ch, s))
                if ch.key in previous and previous[ch.key] != s.url:
                    replaced += 1

    if previous and len(alive) < args.min_ratio * len(previous):
        print(f"only {len(alive)} alive vs {len(previous)} last run - not publishing", file=sys.stderr)
        return 2

    rows = []
    for ch, s in alive:
        order, group = group_for(ch, s)
        rows.append((order, group.lower(), clean_name(s.name).lower(), group, ch, s))
    rows.sort(key=lambda r: r[:3])

    lines = ["#EXTM3U"]
    counts: dict[str, int] = {}
    for _, _, _, group, ch, s in rows:
        attrs = {k: v for k, v in s.attrs.items() if k in ("tvg-id", "tvg-logo", "tvg-name")}
        attrs["group-title"] = group
        attr_txt = " ".join(f'{k}="{v}"' for k, v in attrs.items())
        lines.append(f"#EXTINF:-1 {attr_txt},{clean_name(s.name)}")
        lines.extend(o for o in s.opts)
        lines.append(s.url)
        counts[group] = counts.get(group, 0) + 1
    (out / PLAYLIST_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")

    now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    report = [f"Updated {now}", "",
              f"Working channels: **{len(alive)}** of {len(channels)} checked "
              f"({replaced} switched to a new link this run).", "",
              "| Group | Channels |", "|---|---|"]
    report += [f"| {g} | {n} |" for g, n in sorted(counts.items(), key=lambda kv: (group_order(kv[0]), kv[0]))]
    (out / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))
    return 0


def group_order(g: str) -> int:
    return {"Sports | India": 0, "India": 1, "Hindi World": 2, "Punjabi": 3, "Urdu": 4,
            "Sports | World": 5}.get(
        g if g.startswith("Sports") else g.split(" |")[0], 9)


if __name__ == "__main__":
    sys.exit(main())
