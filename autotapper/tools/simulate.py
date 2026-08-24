#!/usr/bin/env python3
"""
Replay a screen recording through a recipe, offline, with no phone attached.

This is the regression test for a recipe: it proves the gates fire, in order,
at the right moments, and that the loop closes. Run it after editing templates
or thresholds.

    python3 tools/simulate.py --recipe recipes/dokkan_goku_black --video clip.mp4
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import tapper as T  # noqa: E402


class VideoDevice:
    """A fake Device whose screen is a recording, driven by a virtual clock."""

    def __init__(self, path: str):
        cap = cv2.VideoCapture(path)
        if not cap.isOpened():
            raise SystemExit(f"cannot open video: {path}")
        self.fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        self.frames: list[np.ndarray] = []
        while True:
            ok, f = cap.read()
            if not ok:
                break
            self.frames.append(f)
        cap.release()
        self.duration = len(self.frames) / self.fps
        self.clock = 0.0
        self.taps: list[tuple[float, int, int]] = []

    def screenshot(self) -> np.ndarray:
        i = min(len(self.frames) - 1, max(0, int(round(self.clock * self.fps))))
        return self.frames[i]

    def tap(self, x: int, y: int) -> None:
        self.taps.append((self.clock, x, y))

    def describe(self) -> str:
        return f"recording ({len(self.frames)} frames, {self.duration:.1f}s @ {self.fps:.1f}fps)"

    def foreground_package(self):
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--recipe", type=Path, required=True)
    ap.add_argument("--video", required=True)
    ap.add_argument("--loops", type=int, default=1)
    ap.add_argument("--start-at", type=float, default=0.0,
                    help="begin the replay this many seconds in, to test mid-loop resync")
    ap.add_argument("--speed", type=float, default=1.0,
                    help="virtual seconds advanced per poll, relative to poll_interval")
    args = ap.parse_args()

    rx = T.Recipe.load(args.recipe)
    dev = VideoDevice(args.video)
    print(f"replaying {dev.describe()}")
    print(f"recipe    {rx.name}\n")

    # Drive tapper's clock from the recording instead of the wall clock.
    state = {"t": args.start_at}
    dev.clock = args.start_at
    if args.start_at:
        print(f"starting replay at {args.start_at:.1f}s into the clip\n")

    def fake_time() -> float:
        return state["t"]

    def fake_sleep(sec: float) -> None:
        state["t"] += max(sec, rx.poll_interval * args.speed)
        dev.clock = min(state["t"], dev.duration)

    T.time.time = fake_time      # type: ignore[assignment]
    T.time.sleep = fake_sleep    # type: ignore[assignment]
    T.random.uniform = lambda a, b: (a + b) / 2.0   # deterministic
    T.random.randint = lambda a, b: 0               # no jitter, so taps are exact

    tp = T.Tapper(dev, rx, dry_run=False, verbose=False)
    fired: list[tuple[str, float, tuple[int, int]]] = []
    orig = tp.do_tap

    def traced(pt, label):
        fired.append((label, state["t"], pt))
        orig(pt, label)

    tp.do_tap = traced  # type: ignore[method-assign]

    rc = tp.run(args.loops)

    print("\n  gate firings (virtual time into the recording):")
    for label, t, pt in fired:
        print(f"    {t:6.2f}s  {label:22s} tap {pt}")

    got = [l for l, _, _ in fired if not l.endswith(":nudge")]
    if args.start_at:
        # Mid-loop: assert it did NOT start from the top, which is the whole point.
        ok = bool(got) and got[0] != "tap_fight"
        print(f"\n  observed order : {got}")
        print(f"  first gate     : {got[0] if got else 'none'} "
              f"({'resynced' if ok else 'fell back to the top of the loop'})")
    else:
        expected = ["tap_fight", "tap_start", "tap_results_ok", "friend_request", "tap_next_level"]
        print(f"\n  expected order : {expected}")
        print(f"  observed order : {got}")
        ok = got[:len(expected)] == expected
    print(f"\n  {'PASS' if ok and rc == 0 else 'FAIL'} - "
          f"{'all gates fired in order' if ok else 'sequence mismatch'}"
          f"{'' if rc == 0 else f' (runner exited {rc})'}")
    return 0 if (ok and rc == 0) else 1


if __name__ == "__main__":
    sys.exit(main())
