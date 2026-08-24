#!/usr/bin/env python3
"""
Mine tap coordinates out of a screen recording.

Requires Developer options -> "Show taps" to have been on while recording:
Android draws a translucent disc with a pale blue rim at each touch, and this
finds them. Use it to turn "here is me doing the grind once" into coordinates.

    python3 tools/extract_taps.py --video clip.mp4 --out taps.json --verify verify.png

Static circular UI elements look like taps for a frame or two, so detections are
kept only if they are transient AND do not recur at the same spot elsewhere in
the clip. Always eyeball the --verify sheet before trusting the numbers.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

# The indicator appears in two forms: a filled translucent disc with a pale blue
# rim, and, at the start of the ripple, a bright cyan ring. One hue window plus a
# shape test rather than a fill test catches both.
HSV_LO = (88, 30, 95)
HSV_HI = (128, 255, 255)


def candidates(frame: np.ndarray, min_r: int, max_r: int) -> list[tuple]:
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, HSV_LO, HSV_HI)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((11, 11), np.uint8))
    out = []
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for c in cnts:
        area = cv2.contourArea(c)
        per = cv2.arcLength(c, True)
        if per <= 0 or area < 600:
            continue
        (cx, cy), r = cv2.minEnclosingCircle(c)
        if not (min_r <= r <= max_r):
            continue
        circ = 4 * np.pi * area / (per * per)
        if circ < 0.45:
            continue
        x0, y0 = int(max(0, cx - r * 1.4)), int(max(0, cy - r * 1.4))
        x1, y1 = int(min(mask.shape[1], cx + r * 1.4)), int(min(mask.shape[0], cy + r * 1.4))
        sub = mask[y0:y1, x0:x1]
        if sub.size == 0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        lit = sub > 0
        total = lit.sum()
        if total == 0:
            continue
        conc = (lit & (d <= r * 1.05)).sum() / total
        if conc < 0.80:
            continue
        out.append((circ * conc, cx, cy, r))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--out", type=Path, default=Path("taps.json"))
    ap.add_argument("--verify", type=Path, help="write an annotated contact sheet here")
    ap.add_argument("--min-radius", type=int, default=18)
    ap.add_argument("--max-radius", type=int, default=115)
    ap.add_argument("--max-hold", type=float, default=0.9,
                    help="a blob visible longer than this is UI, not a tap")
    args = ap.parse_args()

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0

    raw, i = [], 0
    while True:
        ok, f = cap.read()
        if not ok:
            break
        cs = candidates(f, args.min_radius, args.max_radius)
        if cs:
            b = max(cs)
            raw.append(dict(frame=i, t=i / fps, x=b[1], y=b[2]))
        i += 1
    cap.release()

    # group frame-adjacent detections into one physical touch
    groups = []
    for d in raw:
        if groups and d["frame"] - groups[-1][-1]["frame"] <= 8 \
                and abs(d["x"] - groups[-1][0]["x"]) < 90 and abs(d["y"] - groups[-1][0]["y"]) < 90:
            groups[-1].append(d)
        else:
            groups.append([d])

    # merge the ripple's stages (same place, moments apart)
    merged = []
    for g in groups:
        x = float(np.median([q["x"] for q in g]))
        y = float(np.median([q["y"] for q in g]))
        t0, t1 = g[0]["t"], g[-1]["t"]
        hit = next((m for m in merged
                    if abs(m["x"] - x) < 90 and abs(m["y"] - y) < 90 and t0 - m["t_end"] < 1.5), None)
        if hit:
            hit["t_end"] = max(hit["t_end"], t1)
        else:
            merged.append(dict(x=x, y=y, t=t0, t_end=t1))

    def recurs(e):
        return sum(1 for o in merged if abs(o["x"] - e["x"]) < 45 and abs(o["y"] - e["y"]) < 45)

    taps = [dict(t=round(m["t"], 2), x=int(m["x"]), y=int(m["y"]),
                 held=round(m["t_end"] - m["t"], 2))
            for m in merged if (m["t_end"] - m["t"]) <= args.max_hold and recurs(m) == 1]
    taps.sort(key=lambda z: z["t"])

    print(f"{i} frames, {len(raw)} raw detections -> {len(merged)} blobs -> {len(taps)} taps\n")
    for n, t in enumerate(taps, 1):
        print(f"  {n:2d}. t={t['t']:6.2f}s  ({t['x']},{t['y']})  held {t['held']:.2f}s")
    args.out.write_text(json.dumps(taps, indent=1))
    print(f"\nwrote {args.out}")

    if args.verify and taps:
        cap = cv2.VideoCapture(args.video)
        want = {int(round(t["t"] * fps)): k for k, t in enumerate(taps)}
        shots = [None] * len(taps)
        i = 0
        while True:
            ok, f = cap.read()
            if not ok:
                break
            if i in want:
                k = want[i]
                t = taps[k]
                g = f.copy()
                cv2.circle(g, (t["x"], t["y"]), 75, (0, 0, 255), 8)
                cv2.drawMarker(g, (t["x"], t["y"]), (0, 0, 255), cv2.MARKER_CROSS, 170, 8)
                cv2.rectangle(g, (0, 0), (g.shape[1], 150), (0, 0, 0), -1)
                cv2.putText(g, f"#{k+1} {t['t']:.2f}s ({t['x']},{t['y']})", (20, 105),
                            cv2.FONT_HERSHEY_SIMPLEX, 2.2, (0, 255, 255), 6)
                shots[k] = cv2.resize(g, (300, 650))
            i += 1
        cap.release()
        shots = [s for s in shots if s is not None]
        while len(shots) % 4:
            shots.append(np.zeros_like(shots[0]))
        cv2.imwrite(str(args.verify),
                    np.vstack([np.hstack(shots[r:r + 4]) for r in range(0, len(shots), 4)]))
        print(f"wrote {args.verify} - check every marker lands on the button you meant")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
