# autotapper

A visual-gated auto-tapper for Android, driven over ADB.

Every tap waits for a template match first. Nothing runs on a timer, so the bot
never taps a screen it cannot see, and a slow load or a surprise popup stalls it
instead of sending taps into the void.

The first recipe (`recipes/dokkan_goku_black`) was derived automatically from a
26-second screen recording of the loop being played by hand.

---

## The loop it plays

Dokkan Battle — *Epitome of Sublime Beauty / Goku Black*:

| Step | Waits for | Then |
| --- | --- | --- |
| `tap_fight` | the word **Fight** on the stage screen | taps it |
| `tap_start` | the **Start!** button on team select | taps it |
| `await_results` | the results banner | nothing — the battle auto-resolves |
| `tap_results_ok` | the results **OK** button | taps it |
| `tap_next_level` | **GO TO THE NEXT LEVEL** | taps centre, loop repeats |

Plus one interrupt, checked on *every* poll in *any* state:

| Interrupt | Why |
| --- | --- |
| `friend_request` | The Friend Request popup appears intermittently after a clear and covers the results OK button. Without this the loop would deadlock waiting for a button that is behind a modal. |

Two details that matter:

- **The `tap_fight` template is the word "Fight" alone.** It deliberately excludes
  the `ENEMY LV` digit, which increments on every clear (3 → 4 → 5…). Including
  the digit would make the recipe stop working after one loop.
- **The battle takes no taps in this stage.** It resolves on its own. `await_results`
  taps an inert part of the results panel every 1.2s to skip the reward reveal
  animation, which is what shaves the loop from ~13s to ~9s.

---

## Two ways to run it

| | Desktop (this directory) | On the phone (`android/`) |
| --- | --- | --- |
| Needs a PC | yes, phone tethered over USB | no |
| Capture | `adb exec-out screencap` | MediaProjection |
| Taps | `adb shell input tap` | AccessibilityService |

Both run the same state machine against the same `recipe.json` and the same
templates. The Android app is the one to use for an actual grind session — see
[`android/README.md`](android/README.md). Everything below covers the desktop
tool, which is still where you *author and validate* a recipe.

## Setup

```bash
pip install -r requirements.txt          # opencv + numpy
```

You also need `adb` on PATH (Android platform-tools), and on the phone:
Settings → Developer options → **USB debugging** on. Accept the
*Allow USB debugging* prompt when you plug in.

```bash
adb devices        # must list your phone as "device"
```

## Use

Always probe first. It takes one screenshot, scores every gate, and sends no taps:

```bash
python3 tapper.py --recipe recipes/dokkan_goku_black --probe --debug-dir /tmp/probe
```

Open the stage screen on the phone and you should see `tap_fight` at **YES** with a
score near 1.0. The annotated screenshot in `--debug-dir` shows the search regions
and where it intends to tap.

Then a dry run — full state machine, still no taps:

```bash
python3 tapper.py --recipe recipes/dokkan_goku_black --dry-run
```

Then live:

```bash
python3 tapper.py --recipe recipes/dokkan_goku_black --loops 50 --debug-dir /tmp/run
```

Ctrl-C stops it. If a step times out, the run stops and saves the screenshot that
confused it, so you can see what actually happened.

---

## Retargeting it to another stage or game

The tools that built this recipe are in `tools/`, and the workflow is the one
used here:

**1. Record yourself doing the loop once**, with Developer options → **Show taps**
turned on. One clean pass is enough.

**2. Extract your taps:**

```bash
python3 tools/extract_taps.py --video clip.mp4 --out taps.json --verify verify.png
```

Open `verify.png` and check every marker landed on the button you meant. The
detector finds Android's touch indicator, filters out static circular UI that
looks like a touch, and prints coordinates with timestamps.

**3. Cut templates** for the screens you want to gate on. Crop each from a frame
of the recording into `recipes/<name>/templates/`, and describe them in
`recipe.json`. Prefer a bit of static text or a button face; avoid anything with a
number that changes.

**4. Validate the templates against the recording:**

```bash
python3 tools/validate.py --recipe recipes/<name> --video clip.mp4
```

This is the step that catches bad templates. It reports, per gate, when it fires
and the highest score it reaches on an unrelated screen. Aim for a margin above
+0.15. A gate that never fires has the wrong ROI or template.

**5. Simulate the whole loop offline**, no phone attached:

```bash
python3 tools/simulate.py --recipe recipes/<name> --video clip.mp4
```

It replays the recording through the real state machine on a virtual clock and
checks the gates fire in the right order. Run it after any change to templates or
thresholds.

---

## Recipe format

```jsonc
{
  "reference_resolution": [1080, 2340],  // screenshots are scaled to this; taps scale back
  "match_threshold": 0.82,               // normalised correlation score to accept
  "min_contrast": 18.0,                  // reject low-contrast ROIs (see below)
  "confirm_frames": 2,                   // consecutive matches before acting
  "poll_interval": 0.35,
  "tap_jitter": 8,                       // +/- px, so taps are not pixel-identical

  "steps": [{
    "name": "tap_fight",
    "template": "stage_fight.png",
    "roi": [345, 1465, 755, 1760],       // where to look, in reference coords
    "tap": "center",                     // "center" | [x,y] | {"offset":[dx,dy]}
    "timeout": 90,
    "post_delay": [1.0, 1.6],            // random wait after tapping
    "nudge": {"point": [601,1710], "every": 1.2}  // optional: tap while waiting
  }],
  "interrupts": [ /* same shape; checked every poll, in any state */ ]
}
```

`tap: "center"` taps the middle of wherever the template *matched*, not a fixed
coordinate — so the tap follows the button if the UI shifts.

### Why `min_contrast` exists

`TM_CCOEFF_NORMED` is invariant to brightness and contrast. A screen fading in
from black correlates at 0.99 while it is still nearly invisible and not yet
accepting touches. Requiring real contrast in the ROI stops the bot from tapping
a UI that is not live yet. On the source recording this suppressed 48 mid-fade
frames that scored above threshold across the five gates.

---

## Limits — read before running it unattended

- **Resolution is baked in.** Templates were cut at 1080x2340. Other resolutions
  are scaled to that reference, which usually works, but re-cut the templates if
  matching gets flaky.
- **Only the popups it knows about.** `friend_request` is handled. A daily-login
  banner, a maintenance notice, a stamina-empty dialog, or a network error will
  stall the loop at a timeout. That is the intended failure mode — it stops with a
  screenshot rather than tapping blindly — but it does mean unattended runs end
  early. Add each new popup as an interrupt when you meet it.
- **No stamina handling.** This stage costs 0 STA. A stage that costs stamina will
  stall at `tap_start` once you run out.
- **No recovery.** There is no "navigate back to a known screen" logic; it stops
  instead. Deliberate — blind recovery taps are how bots spend real currency.
- Automating a game client is between you and the game's terms of service. Worth
  a thought before leaving it running for hours on an account you care about.
