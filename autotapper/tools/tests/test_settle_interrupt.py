#!/usr/bin/env python3
"""
Proves a popup appearing during a settle-wait gets dismissed instead of
ignored - a defect found and fixed after Fusion Zamasu's "Friend request
sent." popup went unrecognised.

wait_for_settle looks at whether the FRAME is changing. A static popup is, by
that measure, "not moving" - so without an explicit interrupt check inside the
settle loop, a popup sitting motionless on screen reads as SETTLED and gets
tapped through to whatever is behind it, rather than dismissed.

This is the Python mirror of android/tools/matcher-parity's SettleInterruptCheck.kt
(`gradle run --args="settle"`) - same construction, same assertion, so both
implementations are held to the identical proof.

    python3 tools/tests/test_settle_interrupt.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
import tapper as T  # noqa: E402

W, H = 400, 200


def pattern(w: int, h: int, phase: int) -> np.ndarray:
    """A checkerboard-ish pattern: high contrast, clearly not a flat fill."""
    yy, xx = np.mgrid[0:h, 0:w]
    return np.where(((xx // 4 + yy // 4 + phase) % 2 == 0), 20.0, 235.0).astype(np.float32)


def frame(popup_present: bool) -> np.ndarray:
    """Stage pattern fixed at x0..40,y0..30. Popup pattern at x100..140,y0..30."""
    g = np.full((H, W), 60.0, dtype=np.float32)
    g[0:30, 0:40] = pattern(40, 30, 0)
    if popup_present:
        g[0:30, 100:140] = pattern(40, 30, 1)
    return g


class FakeGate:
    def __init__(self, name, roi_x, tpl, settle):
        self.name = name
        h, w = tpl.shape
        self.templates = [tpl.astype(np.uint8)]
        self.roi = (roi_x, 0, roi_x + w, h)
        self.tap = "center"
        self.timeout = 5.0
        self.pre_delay = (0.0, 0.0)
        self.settle = settle
        self.post_delay = (0.0, 0.0)
        self.nudge = None
        self.optional = False
        self.note = ""


def main() -> int:
    import cv2

    class FakeRecipe:
        reference_resolution = (W, H)
        match_threshold = 0.9
        min_contrast = 5.0
        confirm_frames = 1
        poll_interval = 0.0
        tap_jitter = 0
        resync_on_start = False
        max_interrupt_repeats = 10
        interrupts: list = []
        steps: list = []

    stage_tpl = pattern(40, 30, 0).astype(np.uint8)
    popup_tpl = pattern(40, 30, 1).astype(np.uint8)
    settle_cfg = T.Settle(threshold=3.0, stable_for=0.0, then_wait=0.0, max_wait=2.0)
    stage_gate = FakeGate("stage", 0, stage_tpl, settle_cfg)
    popup_gate = FakeGate("popup", 100, popup_tpl, None)

    rx = FakeRecipe()
    rx.steps = [stage_gate]
    rx.interrupts = [popup_gate]

    # frame 0: stage visible, no popup - satisfies the step match.
    # frame 1: popup appears mid settle-wait - must be dismissed, not ignored.
    # frame 2+: popup gone, screen unchanged - settle-wait now completes.
    frames = [frame(False), frame(True), frame(False), frame(False), frame(False)]
    state = {"i": 0}

    class FakeDevice:
        def screenshot(self):
            i = min(state["i"], len(frames) - 1)
            state["i"] += 1
            bgr = cv2.cvtColor(frames[i].astype(np.uint8), cv2.COLOR_GRAY2BGR)
            return bgr

        def tap(self, x, y):
            taps.append((x, y))

        def describe(self):
            return "fake"

    taps: list[tuple[int, int]] = []
    tp = T.Tapper(FakeDevice(), rx, dry_run=False, verbose=False)
    ok = tp.run_step(stage_gate)

    print(f"taps recorded: {taps}")
    print(f"step result  : {ok}")

    popped_first = taps == [(120, 15), (20, 15)]
    print()
    if popped_first and ok:
        print("PASS - the popup was dismissed during the settle-wait, before the stage tap")
        return 0
    print('FAIL - expected exactly [popup tap, stage tap]; a popup during settle-wait was '
          'ignored (wait_for_settle read a static popup as "settled" and tapped through it)')
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
