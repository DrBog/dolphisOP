#!/usr/bin/env python3
"""
Proves a run of the SAME interrupt with genuinely different content each time
(a different friend's name and avatar, say) does not trip the "stuck" guard,
while a run of the interrupt showing the IDENTICAL screen every time still does.

Dokkan can legitimately queue one friend-request confirmation per borrowed
support after a multi-clear. Before this fix, max_interrupt_repeats counted raw
consecutive dismissals of the same-named interrupt - which cannot tell six
DIFFERENT popups, each dismissed successfully, apart from a tap that keeps
missing the same button. The guard exists to catch the latter; it was also
catching the former, which reads to a user as "it recognises the popup, taps
it, and the run still hangs and stops."

This is the Python mirror of android/tools/matcher-parity's
InterruptRepeatCheck.kt (`gradle run --args="repeats"`).

    python3 tools/tests/test_interrupt_repeats.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
import tapper as T  # noqa: E402

RW, RH = 400, 100


def tile(w: int, h: int, phase: int) -> np.ndarray:
    yy, xx = np.mgrid[0:h, 0:w]
    return np.where(((xx // 4 + yy // 4 + phase) % 2 == 0), 20.0, 235.0).astype(np.uint8)


def popup_frame(content_phase: int) -> np.ndarray:
    """The OK button (fixed pattern) plus a 'name and avatar' region that
    differs between occurrences of the SAME popup - standing in for a
    different friend each time. The varying region sits INSIDE the interrupt's
    own ROI, same as a real popup's name/avatar sits inside its modal box:
    the comparison this test exercises only looks within that ROI, not the
    whole screen, precisely because a real name/avatar is a small fraction of
    a 1080x2340 frame and would otherwise dilute into noise."""
    g = np.full((RH, RW), 60, dtype=np.uint8)
    g[0:30, 0:40] = tile(40, 30, 5)           # the button itself - always the same
    g[0:30, 40:80] = tile(40, 30, content_phase)  # varies between occurrences
    return g


def stage_frame() -> np.ndarray:
    g = np.full((RH, RW), 60, dtype=np.uint8)
    g[0:30, 300:340] = tile(40, 30, 9)
    return g


def blank_frame() -> np.ndarray:
    """Neither popup nor stage visible - a plain background frame."""
    return np.full((RH, RW), 60, dtype=np.uint8)


def make_recipe(max_repeats: int, confirm_frames: int = 1) -> T.Recipe:
    popup_gate = T.Gate(name="popup", templates=[tile(40, 30, 5)], roi=(0, 0, 80, 30), tap="center")
    stage_gate = T.Gate(name="stage", templates=[tile(40, 30, 9)], roi=(300, 0, 340, 30), tap="center")
    return T.Recipe(
        name="synthetic", description="", reference_resolution=(RW, RH),
        match_threshold=0.9, min_contrast=5.0, confirm_frames=confirm_frames, poll_interval=0.0,
        tap_jitter=0, resync_on_start=False, max_interrupt_repeats=max_repeats,
        steps=[stage_gate], interrupts=[popup_gate],
    )


def run_scenario(frames: list[np.ndarray], max_repeats: int, debug_dir=None,
                 confirm_frames: int = 1) -> tuple[int, list, T.Tapper]:
    import cv2

    state = {"i": 0}
    taps: list[tuple[int, int]] = []

    class FakeDevice:
        def screenshot(self):
            i = min(state["i"], len(frames) - 1)
            state["i"] += 1
            return cv2.cvtColor(frames[i], cv2.COLOR_GRAY2BGR)

        def tap(self, x, y):
            taps.append((x, y))

        def describe(self):
            return "fake"

    rx = make_recipe(max_repeats, confirm_frames)
    tp = T.Tapper(FakeDevice(), rx, dry_run=False, verbose=False, debug_dir=debug_dir)
    rc = tp.run(1)
    return rc, taps, tp


def main() -> int:
    failed = 0

    # Case 1: 8 DIFFERENT popups in a row, exceeding max_interrupt_repeats(6) -
    # must NOT get stuck, must still reach the stage tap.
    frames = [popup_frame(i) for i in range(8)] + [stage_frame()]
    rc, taps, tp = run_scenario(frames, max_repeats=6)
    ok1 = rc == 0 and len(taps) == 9 and tp.stuck_on is None
    print(f"{'ok  ' if ok1 else 'FAIL'}  8 distinct repeats do not trip the stuck guard "
          f"(rc={rc}, taps={len(taps)}, stuck_on={tp.stuck_on})")
    failed += 0 if ok1 else 1

    # Case 2: 8 IDENTICAL popups in a row - this is the case the guard exists
    # for, and must still trip it, AND leave a screenshot behind naming the
    # stuck interrupt. That screenshot is the whole point: run_step used to
    # return False from inside its polling loop before ever reaching the dump
    # code that lived after the loop, so a run that stopped here left no
    # picture of why - only discovered by writing this assertion.
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        debug_dir = Path(td)
        frames = [popup_frame(0)] * 8 + [stage_frame()]
        rc, taps, tp = run_scenario(frames, max_repeats=6, debug_dir=debug_dir)
        dumped = list(debug_dir.glob("*stuck_popup*"))
        ok2 = rc == 1 and len(taps) == 8 and tp.stuck_on == "popup" and len(dumped) == 1
    print(f"{'ok  ' if ok2 else 'FAIL'}  8 identical repeats still trip the stuck guard, "
          f"and dump a screenshot (rc={rc}, taps={len(taps)}, stuck_on={tp.stuck_on}, "
          f"dumped={[p.name for p in dumped]})")
    failed += 0 if ok2 else 1

    print()
    if failed:
        print(f"FAIL - {failed} case(s) wrong")
        return 1
    print("PASS - repeats of a genuinely changing popup are not mistaken for a stuck tap")

    # Case 3: a one-frame popup match, gone the next frame, must NOT be tapped -
    # the fix for a live run that matched friend_request at 1.000 and tapped
    # the OTHER known layout's button position, on a screen that a moment
    # later was showing a plain single-OK popup with no button there at all.
    # A one-frame compositing artifact between two queued dialogs looks
    # exactly like a real popup for a single poll; a genuine dialog stays up
    # for many. Then the SAME popup reappears and holds for confirm_frames(2)
    # frames - that occurrence must still be dismissed normally.
    popup_tap = (20, 15)   # centre of the 40x30 popup template at roi (0,0,80,30)
    stage_tap = (320, 15)  # centre of the 40x30 stage template at roi (300,0,340,30)
    frames = [
        popup_frame(0),               # transient - one frame only
        blank_frame(),                # gone - resets the confirm counter
        popup_frame(0), popup_frame(0),  # stable - two frames, confirmed
        stage_frame(),
    ]
    rc, taps, tp = run_scenario(frames, max_repeats=6, confirm_frames=2)
    ok3 = rc == 0 and taps == [popup_tap, stage_tap]
    print(f"\n{'ok  ' if ok3 else 'FAIL'}  a one-frame popup is not tapped, a two-frame one is "
          f"(rc={rc}, taps={taps})")
    if not ok3:
        print("FAIL - confirmation guard is not working")
        return 1
    print("PASS - interrupts require the same confirmation steps already do")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
