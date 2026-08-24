#!/usr/bin/env python3
"""
Visual-gated auto-tapper for Android over ADB.

Every tap is gated on a template match, so the bot never taps on a timer and
never taps a screen it cannot see. If an expected screen does not appear within
its timeout, the run stops and dumps the screenshot rather than flailing.

    python3 tapper.py --recipe recipes/dokkan_goku_black --dry-run
    python3 tapper.py --recipe recipes/dokkan_goku_black --loops 20
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import struct
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import cv2
import numpy as np

ADB = os.environ.get("ADB_BINARY", "adb")


# ---------------------------------------------------------------- device I/O


class AdbError(RuntimeError):
    pass


class Device:
    """Screen capture and tap injection for one ADB device."""

    def __init__(self, serial: str | None = None, capture_mode: str = "auto"):
        self.serial = serial
        self.capture_mode = capture_mode
        self._raw_works: bool | None = None if capture_mode == "auto" else (capture_mode == "raw")

    def _cmd(self, *args: str) -> list[str]:
        return [ADB] + (["-s", self.serial] if self.serial else []) + list(args)

    def _run(self, *args: str, timeout: float = 25.0) -> bytes:
        p = subprocess.run(self._cmd(*args), capture_output=True, timeout=timeout)
        if p.returncode != 0:
            raise AdbError(f"adb {' '.join(args)} failed: {p.stderr.decode(errors='replace').strip()}")
        return p.stdout

    # -- lifecycle ---------------------------------------------------------

    @staticmethod
    def list_devices() -> list[tuple[str, str]]:
        try:
            out = subprocess.run([ADB, "devices"], capture_output=True, timeout=20).stdout.decode()
        except FileNotFoundError:
            raise AdbError(
                f"'{ADB}' not found on PATH. Install platform-tools, or set ADB_BINARY to its full path."
            )
        found = []
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                found.append((parts[0], parts[1]))
        return found

    def describe(self) -> str:
        model = self._run("shell", "getprop", "ro.product.model").decode().strip()
        rel = self._run("shell", "getprop", "ro.build.version.release").decode().strip()
        return f"{model or 'unknown device'} (Android {rel or '?'}, serial {self.serial or 'default'})"

    # -- capture -----------------------------------------------------------

    @staticmethod
    def _decode_raw(data: bytes) -> np.ndarray | None:
        """Parse `screencap` raw output: uint32 w, h, format, [colorspace], then RGBA."""
        if len(data) < 16:
            return None
        w, h, fmt = struct.unpack("<III", data[:12])
        if not (0 < w <= 8192 and 0 < h <= 8192):
            return None
        for header in (12, 16):  # 16 bytes on API 28+ (adds colorspace)
            if len(data) - header == w * h * 4:
                arr = np.frombuffer(data[header:], dtype=np.uint8).reshape(h, w, 4)
                return cv2.cvtColor(arr, cv2.COLOR_RGBA2BGR)
        return None

    def screenshot(self) -> np.ndarray:
        if self._raw_works is not False:
            try:
                img = self._decode_raw(self._run("exec-out", "screencap"))
                if img is not None:
                    self._raw_works = True
                    return img
            except AdbError:
                pass
            if self._raw_works is None:
                self._raw_works = False  # fall back once, then stay on PNG

        data = self._run("exec-out", "screencap", "-p")
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise AdbError("screencap returned data that could not be decoded as an image")
        return img

    # -- input -------------------------------------------------------------

    def tap(self, x: int, y: int) -> None:
        self._run("shell", "input", "tap", str(int(x)), str(int(y)))

    def foreground_package(self) -> str | None:
        try:
            out = self._run("shell", "dumpsys", "window", "displays").decode(errors="replace")
        except AdbError:
            return None
        m = re.search(r"mCurrentFocus=.*?\s([A-Za-z0-9_.]+)/", out)
        return m.group(1) if m else None


# ------------------------------------------------------------------- recipe


@dataclass
class Gate:
    """A template plus where to look for it and where to tap when it is found."""

    name: str
    template: np.ndarray
    roi: tuple[int, int, int, int]
    tap: Any
    timeout: float = 60.0
    post_delay: tuple[float, float] = (0.5, 0.9)
    nudge: dict | None = None
    optional: bool = False
    note: str = ""

    @property
    def size(self) -> tuple[int, int]:
        h, w = self.template.shape[:2]
        return w, h


@dataclass
class Recipe:
    name: str
    description: str
    reference_resolution: tuple[int, int]
    match_threshold: float
    min_contrast: float
    confirm_frames: int
    poll_interval: float
    tap_jitter: int
    steps: list[Gate]
    interrupts: list[Gate] = field(default_factory=list)

    @classmethod
    def load(cls, folder: Path) -> "Recipe":
        cfg = json.loads((folder / "recipe.json").read_text())
        tdir = folder / "templates"

        def gate(d: dict) -> Gate:
            path = tdir / d["template"]
            img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
            if img is None:
                raise FileNotFoundError(f"template not found: {path}")
            pd = d.get("post_delay", [0.5, 0.9])
            return Gate(
                name=d["name"],
                template=img,
                roi=tuple(d["roi"]),
                tap=d.get("tap", "center"),
                timeout=float(d.get("timeout", 60)),
                post_delay=(float(pd[0]), float(pd[1])),
                nudge=d.get("nudge"),
                optional=bool(d.get("optional", False)),
                note=d.get("note", ""),
            )

        return cls(
            name=cfg["name"],
            description=cfg.get("description", ""),
            reference_resolution=tuple(cfg["reference_resolution"]),
            match_threshold=float(cfg.get("match_threshold", 0.82)),
            min_contrast=float(cfg.get("min_contrast", 18.0)),
            confirm_frames=int(cfg.get("confirm_frames", 2)),
            poll_interval=float(cfg.get("poll_interval", 0.35)),
            tap_jitter=int(cfg.get("tap_jitter", 8)),
            steps=[gate(s) for s in cfg["steps"]],
            interrupts=[gate(s) for s in cfg.get("interrupts", [])],
        )


# ---------------------------------------------------------------- matching


@dataclass
class Match:
    found: bool
    score: float
    contrast: float
    origin: tuple[int, int] = (0, 0)
    point: tuple[int, int] = (0, 0)


def find(frame_gray: np.ndarray, gate: Gate, threshold: float, min_contrast: float) -> Match:
    """Locate `gate` inside its ROI. Returns the tap point in reference coords."""
    x0, y0, x1, y1 = gate.roi
    h, w = frame_gray.shape[:2]
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)
    roi = frame_gray[y0:y1, x0:x1]
    tw, th = gate.size
    if roi.shape[0] < th or roi.shape[1] < tw:
        return Match(False, 0.0, 0.0)

    contrast = float(roi.std())
    res = cv2.matchTemplate(roi, gate.template, cv2.TM_CCOEFF_NORMED)
    _, score, _, loc = cv2.minMaxLoc(res)
    score = float(score)

    # Normalised correlation is invariant to brightness and contrast, so it
    # happily locks onto a screen that is still fading in from black. Requiring
    # real contrast in the ROI keeps us from tapping a UI that is not live yet.
    ok = score >= threshold and contrast >= min_contrast
    ox, oy = loc[0] + x0, loc[1] + y0

    if gate.tap == "center" or gate.tap is None:
        pt = (ox + tw // 2, oy + th // 2)
    elif isinstance(gate.tap, dict) and "offset" in gate.tap:
        pt = (ox + int(gate.tap["offset"][0]), oy + int(gate.tap["offset"][1]))
    else:
        pt = (int(gate.tap[0]), int(gate.tap[1]))

    return Match(ok, score, contrast, (ox, oy), pt)


# ------------------------------------------------------------------- runner


class Tapper:
    def __init__(self, device: Device, recipe: Recipe, *, dry_run: bool = False,
                 debug_dir: Path | None = None, verbose: bool = False):
        self.dev = device
        self.rx = recipe
        self.dry_run = dry_run
        self.debug_dir = debug_dir
        self.verbose = verbose
        self.scale: tuple[float, float] = (1.0, 1.0)
        self.stats = {"loops": 0, "taps": 0, "interrupts": 0, "captures": 0}
        if debug_dir:
            debug_dir.mkdir(parents=True, exist_ok=True)

    # -- frames ------------------------------------------------------------

    def grab(self) -> tuple[np.ndarray, np.ndarray]:
        """Return (bgr_reference_space, gray_reference_space)."""
        raw = self.dev.screenshot()
        self.stats["captures"] += 1
        rw, rh = self.rx.reference_resolution
        h, w = raw.shape[:2]
        if (w, h) != (rw, rh):
            self.scale = (w / rw, h / rh)
            raw = cv2.resize(raw, (rw, rh), interpolation=cv2.INTER_AREA)
        else:
            self.scale = (1.0, 1.0)
        return raw, cv2.cvtColor(raw, cv2.COLOR_BGR2GRAY)

    def to_device(self, pt: tuple[int, int]) -> tuple[int, int]:
        return int(round(pt[0] * self.scale[0])), int(round(pt[1] * self.scale[1]))

    # -- actions -----------------------------------------------------------

    def do_tap(self, pt: tuple[int, int], label: str) -> None:
        j = self.rx.tap_jitter
        px = pt[0] + random.randint(-j, j)
        py = pt[1] + random.randint(-j, j)
        dx, dy = self.to_device((px, py))
        if self.dry_run:
            print(f"      [dry-run] would tap {label} at ({dx},{dy})")
            return
        self.dev.tap(dx, dy)
        self.stats["taps"] += 1
        if self.verbose:
            print(f"      tapped {label} at ({dx},{dy})")

    def dump(self, frame: np.ndarray, tag: str) -> Path | None:
        if not self.debug_dir:
            return None
        p = self.debug_dir / f"{time.strftime('%H%M%S')}_{tag}.png"
        cv2.imwrite(str(p), frame)
        return p

    # -- interrupts --------------------------------------------------------

    def handle_interrupts(self, gray: np.ndarray) -> bool:
        for g in self.rx.interrupts:
            m = find(gray, g, self.rx.match_threshold, self.rx.min_contrast)
            if m.found:
                print(f"    ! interrupt: {g.name} (score {m.score:.3f}) -> dismissing")
                self.do_tap(m.point, g.name)
                self.stats["interrupts"] += 1
                time.sleep(random.uniform(*g.post_delay))
                return True
        return False

    # -- one gated step ----------------------------------------------------

    def run_step(self, gate: Gate) -> bool:
        deadline = time.time() + gate.timeout
        hits = 0
        last_nudge = 0.0
        best = 0.0
        started = time.time()

        while time.time() < deadline:
            frame, gray = self.grab()

            if self.handle_interrupts(gray):
                continue

            m = find(gray, gate, self.rx.match_threshold, self.rx.min_contrast)
            best = max(best, m.score)

            if m.found:
                hits += 1
                if hits >= self.rx.confirm_frames:
                    waited = time.time() - started
                    print(f"    {gate.name:16s} seen (score {m.score:.3f}, {waited:.1f}s)")
                    if gate.tap is not None:
                        self.do_tap(m.point, gate.name)
                    time.sleep(random.uniform(*gate.post_delay))
                    return True
            else:
                hits = 0
                if gate.nudge and time.time() - last_nudge >= float(gate.nudge.get("every", 1.5)):
                    self.do_tap(tuple(gate.nudge["point"]), f"{gate.name}:nudge")
                    last_nudge = time.time()

            time.sleep(self.rx.poll_interval)

        if gate.optional:
            print(f"    {gate.name:16s} not seen within {gate.timeout:.0f}s (optional, skipping)")
            return True

        frame, _ = self.grab()
        p = self.dump(frame, f"timeout_{gate.name}")
        print(f"    {gate.name:16s} TIMEOUT after {gate.timeout:.0f}s (best score {best:.3f}, "
              f"needed {self.rx.match_threshold})")
        if p:
            print(f"      screenshot saved: {p}")
        return False

    # -- main loop ---------------------------------------------------------

    def run(self, loops: int) -> int:
        print(f"\nrecipe : {self.rx.name}")
        print(f"         {self.rx.description}")
        print(f"steps  : {' -> '.join(s.name for s in self.rx.steps)}")
        if self.rx.interrupts:
            print(f"watch  : {', '.join(i.name for i in self.rx.interrupts)} (any time)")
        print(f"mode   : {'DRY RUN (no taps sent)' if self.dry_run else 'LIVE'}\n")

        t0 = time.time()
        for n in range(1, loops + 1):
            print(f"  loop {n}/{loops}")
            for gate in self.rx.steps:
                if not self.run_step(gate):
                    print(f"\n  stopped in loop {n} at step '{gate.name}'.")
                    self.report(t0)
                    return 1
            self.stats["loops"] += 1
            if self.dry_run:
                print("  (dry run: stopping after one pass)\n")
                break
        self.report(t0)
        return 0

    def report(self, t0: float) -> None:
        el = time.time() - t0
        s = self.stats
        print(f"\n  {s['loops']} loop(s) in {el:.0f}s", end="")
        if s["loops"]:
            print(f"  ({el / s['loops']:.1f}s per loop)", end="")
        print(f"\n  {s['taps']} taps, {s['interrupts']} interrupts handled, {s['captures']} screenshots")


# ---------------------------------------------------------------------- cli


def cmd_probe(dev: Device, rx: Recipe, args) -> int:
    """Screenshot once and report every gate's score - the tool for tuning."""
    t = Tapper(dev, rx, dry_run=True, debug_dir=args.debug_dir, verbose=True)
    frame, gray = t.grab()
    print(f"\ncapture: {frame.shape[1]}x{frame.shape[0]} reference space "
          f"(device scale {t.scale[0]:.3f}x{t.scale[1]:.3f})")
    pkg = dev.foreground_package()
    if pkg:
        print(f"foreground: {pkg}")
    print(f"\n{'gate':18s} {'score':>7s} {'contrast':>9s}  {'match?':>7s}  tap point")
    print("  " + "-" * 62)
    for g in list(rx.steps) + list(rx.interrupts):
        m = find(gray, g, rx.match_threshold, rx.min_contrast)
        mark = "YES" if m.found else ("low" if m.score >= rx.match_threshold else "no")
        pt = t.to_device(m.point)
        print(f"{g.name:18s} {m.score:7.3f} {m.contrast:9.1f}  {mark:>7s}  {pt}")
    print(f"\n  threshold {rx.match_threshold}, min contrast {rx.min_contrast}")
    ann = frame.copy()
    for g in list(rx.steps) + list(rx.interrupts):
        m = find(gray, g, rx.match_threshold, rx.min_contrast)
        x0, y0, x1, y1 = g.roi
        cv2.rectangle(ann, (x0, y0), (x1, y1), (90, 90, 90), 2)
        if m.found:
            w, h = g.size
            cv2.rectangle(ann, m.origin, (m.origin[0] + w, m.origin[1] + h), (0, 255, 0), 4)
            cv2.drawMarker(ann, m.point, (0, 0, 255), cv2.MARKER_CROSS, 60, 4)
            cv2.putText(ann, g.name, (m.origin[0], max(24, m.origin[1] - 10)),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 0), 3)
    p = t.dump(ann, "probe")
    if p:
        print(f"  annotated screenshot: {p}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--recipe", type=Path, required=True, help="recipe folder (contains recipe.json + templates/)")
    ap.add_argument("--serial", help="ADB serial, if more than one device is attached")
    ap.add_argument("--loops", type=int, default=1, help="how many times to run the loop (default 1)")
    ap.add_argument("--dry-run", action="store_true", help="match and report, send no taps")
    ap.add_argument("--probe", action="store_true", help="score every gate against one screenshot and exit")
    ap.add_argument("--debug-dir", type=Path, help="save annotated screenshots here")
    ap.add_argument("--threshold", type=float, help="override match threshold")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args()

    try:
        rx = Recipe.load(args.recipe)
    except Exception as e:
        print(f"could not load recipe: {e}", file=sys.stderr)
        return 2
    if args.threshold is not None:
        rx.match_threshold = args.threshold

    try:
        devices = Device.list_devices()
    except AdbError as e:
        print(str(e), file=sys.stderr)
        return 2

    usable = [s for s, st in devices if st == "device"]
    if not usable:
        print("No authorised device. Check:\n"
              "  - USB debugging is on (Developer options)\n"
              "  - the 'Allow USB debugging' prompt on the phone was accepted\n"
              "  - `adb devices` lists it as 'device', not 'unauthorized' or 'offline'",
              file=sys.stderr)
        for s, st in devices:
            print(f"  seen: {s} -> {st}", file=sys.stderr)
        return 2
    if len(usable) > 1 and not args.serial:
        print(f"Multiple devices attached; pass --serial. Found: {', '.join(usable)}", file=sys.stderr)
        return 2

    dev = Device(args.serial or usable[0])
    try:
        print(f"device : {dev.describe()}")
    except AdbError as e:
        print(f"device query failed: {e}", file=sys.stderr)
        return 2

    if args.probe:
        return cmd_probe(dev, rx, args)

    t = Tapper(dev, rx, dry_run=args.dry_run, debug_dir=args.debug_dir, verbose=args.verbose)
    try:
        return t.run(args.loops)
    except KeyboardInterrupt:
        print("\n  interrupted by user")
        t.report(time.time())
        return 130


if __name__ == "__main__":
    sys.exit(main())
