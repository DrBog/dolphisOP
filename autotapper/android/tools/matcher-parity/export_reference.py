#!/usr/bin/env python3
"""
Export reference data for the matcher-parity test.

The Android app cannot depend on OpenCV without dragging in a ~100MB native SDK,
so Matcher.kt reimplements TM_CCOEFF_NORMED. That is only safe if it produces the
same numbers - otherwise the thresholds validated on the desktop tool mean
nothing on the phone. This dumps frames, templates and OpenCV's own scores so the
Kotlin side can be checked against them.

    python3 export_reference.py --recipe ../../recipes/dokkan_goku_black \
                                --video clip.mp4 --out /tmp/parity
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import cv2
import numpy as np


def write_gray(path: Path, g: np.ndarray) -> None:
    with open(path, "wb") as f:
        f.write(struct.pack("<ii", g.shape[1], g.shape[0]))
        f.write(g.tobytes())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--recipe", type=Path, required=True)
    ap.add_argument("--video", required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--samples", type=int, default=36)
    args = ap.parse_args()

    cfg = json.loads((args.recipe / "recipe.json").read_text())
    gates = cfg["steps"] + cfg.get("interrupts", [])
    (args.out / "tpl").mkdir(parents=True, exist_ok=True)
    (args.out / "frames").mkdir(parents=True, exist_ok=True)

    tpls = {}
    for g in gates:
        im = cv2.imread(str(args.recipe / "templates" / g["template"]))
        if im is None:
            raise SystemExit(f"missing template {g['template']}")
        t = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
        tpls[g["template"]] = t
        write_gray(args.out / "tpl" / f"{g['template']}.bin", t)

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {args.video}")
    frames = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(f)
    cap.release()
    if not frames:
        raise SystemExit("no frames decoded")

    step = max(1, len(frames) // args.samples)
    pick = sorted(set(range(0, len(frames), step)))[: args.samples]

    rw, rh = cfg["reference_resolution"]
    cases = []
    for n in pick:
        f = frames[n]
        if (f.shape[1], f.shape[0]) != (rw, rh):
            f = cv2.resize(f, (rw, rh), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
        write_gray(args.out / "frames" / f"{n:04d}.bin", gray)
        for g in gates:
            x0, y0, x1, y1 = g["roi"]
            roi = gray[y0:y1, x0:x1]
            t = tpls[g["template"]]
            if roi.shape[0] < t.shape[0] or roi.shape[1] < t.shape[1]:
                continue
            res = cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED)
            _, mx, _, loc = cv2.minMaxLoc(res)
            cases.append(dict(frame=n, gate=g["name"], template=g["template"],
                              roi=[x0, y0, x1, y1], score=round(float(mx), 6),
                              x=int(loc[0]) + x0, y=int(loc[1]) + y0,
                              contrast=round(float(roi.std()), 4)))

    (args.out / "expected.json").write_text(
        json.dumps(dict(threshold=cfg.get("match_threshold", 0.82),
                        frames=pick, cases=cases), indent=1))
    print(f"exported {len(pick)} frames and {len(cases)} reference scores to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
