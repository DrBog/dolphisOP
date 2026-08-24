#!/usr/bin/env python3
"""
Score a recipe's templates against a recording, frame by frame.

A template is only safe if it matches on its own screen and nowhere else. This
prints when each one fires and the highest score it reaches when it should not
be firing - the gap between those two numbers is your safety margin.

    python3 tools/validate.py --recipe recipes/dokkan_goku_black --video clip.mp4
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import tapper as T  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--recipe", type=Path, required=True)
    ap.add_argument("--video", required=True)
    args = ap.parse_args()

    rx = T.Recipe.load(args.recipe)
    gates = list(rx.steps) + list(rx.interrupts)
    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    rw, rh = rx.reference_resolution

    series = {g.name: [] for g in gates}
    i = 0
    while True:
        ok, f = cap.read()
        if not ok:
            break
        if (f.shape[1], f.shape[0]) != (rw, rh):
            f = cv2.resize(f, (rw, rh), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
        for g in gates:
            m = T.find(gray, g, rx.match_threshold, rx.min_contrast)
            series[g.name].append((i / fps, m.score, m.contrast, m.found))
        i += 1
    cap.release()

    print(f"{i} frames at {fps:.1f}fps, threshold {rx.match_threshold}, "
          f"min contrast {rx.min_contrast}\n")
    worst = 1.0
    for g in gates:
        s = series[g.name]
        wins = []
        for t, sc, ct, found in s:
            if not found:
                continue
            if wins and t - wins[-1][1] < 0.30:
                wins[-1][1] = t
                wins[-1][2] = max(wins[-1][2], sc)
            else:
                wins.append([t, t, sc])

        on = [sc for _, sc, _, found in s if found]
        # Frames that are a genuinely different screen: they have real contrast
        # (so they are not a fade) but did not match. Fades are excluded because
        # the contrast gate already suppresses them, and counting them here would
        # make a perfectly safe template look ambiguous.
        fire_spans = [(w[0], w[1]) for w in wins]

        def near_fire(t, pad=1.0):
            return any(a - pad <= t <= b + pad for a, b in fire_spans)

        # Frames on a genuinely unrelated screen: real contrast, no match, and not
        # adjacent to a firing window. The exclusion matters - a template dips below
        # threshold when a finger or an animation briefly covers it, and that dip is
        # on the RIGHT screen, so counting it as a false positive is just wrong.
        off_live = [sc for t, sc, ct, found in s
                    if not found and ct >= rx.min_contrast and not near_fire(t)]
        occluded = [sc for t, sc, ct, found in s
                    if not found and ct >= rx.min_contrast and near_fire(t)]
        saved = sum(1 for _, sc, ct, found in s
                    if not found and ct < rx.min_contrast and sc >= rx.match_threshold)

        lo_on = min(on, default=0.0)
        hi_off = max(off_live, default=0.0)
        margin = (lo_on - hi_off) if wins else 0.0
        if wins:
            worst = min(worst, margin)

        print(f"\u2500\u2500 {g.name}")
        for w in wins:
            print(f"     fires {w[0]:6.2f}s -> {w[1]:6.2f}s   peak {w[2]:.3f}")
        if not wins:
            print("     NEVER FIRES - template or ROI is wrong for this recording")
        thin = "   <-- THIN" if (wins and margin < 0.10) else ""
        print(f"     lowest while firing {lo_on:.3f} | highest on an unrelated screen {hi_off:.3f} "
              f"| margin {margin:+.3f}{thin}")
        if occluded:
            print(f"     {len(occluded)} frame(s) dipped to {min(occluded):.3f} on its own screen "
                  f"(finger or animation covering it) - harmless, confirm_frames rides it out")
        if saved:
            print(f"     contrast gate suppressed {saved} mid-fade frame(s) that scored "
                  f"above threshold")
        print()
    print(f"tightest margin across all gates: {worst:+.3f}"
          f"{'  (comfortable)' if worst >= 0.15 else '  (consider retuning)'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
