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

Before the first loop it **resyncs**: it looks at what is on screen and jumps to
whichever step is already live, rather than assuming you start on the stage
screen. Starting mid-cycle otherwise means idling until the game comes all the
way round — on a real run that cost 83 seconds. Where two gates overlap (the
results banner is still up when the OK button appears) it takes the more advanced
one. Set `"resync_on_start": false` to always start from the top.

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

`--dry-run` and `--loops` both honour the resync, so you can start it with the
game on any screen in the cycle.

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
python3 tools/simulate.py --recipe recipes/<name> --video clip.mp4 --start-at 13.2
```

It replays the recording through the real state machine on a virtual clock and
checks the gates fire in the right order. `--start-at` begins partway through, so
you can check the mid-loop resync picks the right step instead of falling back to
the top.

Use `--ignore-waits` for the ordering regression. A replay has a fixed timeline
that does not respond to taps — in a real run, tapping Fight is what *causes* the
next screen — so any wait the engine adds desyncs it from the recording and the
run fails for reasons that have nothing to do with the gates. Wait durations are
validated separately, by measuring frame motion (see `settle_first`).

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
    "pre_delay": [0.0, 0.0],             // hold after matching, BEFORE tapping
    "post_delay": [1.0, 1.6],            // random wait after tapping
    "nudge": {"point": [601,1710], "every": 1.2}  // optional: tap while waiting
  }],
  "interrupts": [ /* same shape; checked every poll, in any state */ ]
}
```

`tap: "center"` taps the middle of wherever the template *matched*, not a fixed
coordinate — so the tap follows the button if the UI shifts.

### `settle_first` — wait for the screen to stop moving

A fixed delay is a guess about how long a transition takes. `settle_first`
measures it instead: it keeps capturing until the frame stops changing, then
holds, then taps.

```jsonc
"settle_first": {
  "threshold": 3.0,     // mean abs frame difference below this counts as still
  "stable_for": 0.6,    // ...held for this long
  "then_wait": 1.5,     // ...then wait this long before tapping
  "max_wait": 25.0      // give up waiting and tap anyway
}
```

The point is that it needs **no template for the loading screen** — which matters,
because an unrecognised loading screen is exactly the thing that makes a gate fire
early. Whatever is on screen, if it is animating, the frame keeps changing.

Pick `threshold` from measurements, not intuition. On the source recording, at the
0.35s spacing the engine actually polls at:

| phase | frame difference |
| --- | --- |
| battle / loading | 4.7 – 93.9 |
| results reveal | 2.0 – 29.5 |
| transition into next-level | 8.1 – 31.3 |
| **next-level, settled** | **0.05 – 1.45** |
| after the tap, loading | 5.9 – 33.1 |

Settled tops out at 1.45 and anything moving starts at 2.0, so 3.0 sits in a wide
gap. `tap_next_level` uses this.

### Why `pre_delay` exists

A screen can be drawn — and matched — a moment before it will actually accept
input. Where a game's refresh lags its own transition animation, a tap fired the
instant the gate confirms lands in that gap, does nothing, and the loop then
waits out a full timeout for a transition that never started. `pre_delay` holds
after the match and before the tap. `tap_next_level` uses 1.5s for exactly this
reason.

It is not the same as `post_delay`, which waits *after* tapping to let the next
screen come up. A step can need both.

### Multiple templates per gate

A gate can list `"templates": [...]` instead of a single `"template"` - the
best-scoring one wins. This exists because a screen's own look can genuinely
vary: the reward-reveal banner renders visibly brighter for some rarities or
difficulty tiers than others, brighter than plain normalised correlation
shrugs off. A Hell-tier SSR pull scored 0.463 against the plain template and
1.000 against one cut from that exact screenshot. Edge/gradient-based matching
was tried as a more brightness-tolerant alternative and measured no better
(0.417) - the highlight distorts local contrast, not just overall brightness,
so it isn't a lighting-invariance problem a different matching function
solves. A second template, sourced from a real example of the variant look,
is what actually closes the gap.

A gate with one template behaves exactly as it always did; this is additive.

### Why `min_contrast` exists

`TM_CCOEFF_NORMED` is invariant to brightness and contrast. A screen fading in
from black correlates at 0.99 while it is still nearly invisible and not yet
accepting touches. Requiring real contrast in the ROI stops the bot from tapping
a UI that is not live yet. On the source recording this suppressed 48 mid-fade
frames that scored above threshold across the five gates.

---

## Interrupts confirm too, now

`confirm_frames` has always meant a step needs that many consecutive matches
before it acts - exactly so a single stray frame cannot trigger a tap.
Interrupts never had that guard: they acted on the very first match, full
stop.

That gap surfaced on a live run: `friend_request` matched at score 1.000 and
tapped the *other* known button layout's position, on a screen that a moment
later was a plain single-OK popup with no button at that location at all. Two
friend-request dialogs queuing back to back (routine after a multi-clear) can
put a single transitional, compositing frame between them, and that frame can
score just as high as a real dialog - for exactly one poll. A genuine dialog
stays up for many; the fix costs it nothing to wait one more.

Interrupts now require the same `confirm_frames` consecutive matches steps
already did, tracked per interrupt name and reset the moment it stops
matching. `tools/tests/test_interrupt_repeats.py`'s third case is the direct
proof: a popup present for one frame, then gone, then present again for two
frames, must produce exactly one tap - for the *second* occurrence, not the
first. The Kotlin mirror (`gradle run --args="confirm"` in
`android/tools/matcher-parity`) asserts the identical thing.

---

## When a run stalls: the screenshot it leaves behind

A step that times out, or an interrupt the stuck-guard gives up on, has
`--debug-dir` write a screenshot before the run stops - the exact frame that
confused it, so a report can include a picture instead of just log text.

That dump had a real bug of its own: `run_step`'s main polling loop returned
`False` the moment the stuck-guard fired, from *inside* the loop - and the
screenshot code lived *after* the loop, in a branch that early return always
skipped. The run stopped correctly; it just never left a picture behind,
silently, for as long as this has existed. The same gap existed a second time
in `wait_for_settle`, which never even checked whether the guard had fired -
a stuck interrupt appearing mid-settle-wait went unnoticed until `max_wait`
ran out, and a timed-out settle means "tap anyway" by design, which is exactly
wrong for a stuck interrupt.

Both are fixed by routing every place that can trip the guard through one
`check_stuck()` method, which is also what takes the screenshot. Proven with a
scripted run of a genuinely-stuck interrupt asserting a screenshot actually
lands on disk (`tools/tests/test_interrupt_repeats.py`) - confirmed by hand
that reverting the fix makes the assertion fail (`dumped=[]`) while the fix
restores it (`dumped=['..._stuck_popup.png']`).

## Limits — read before running it unattended

- **Resolution is baked in.** Templates were cut at 1080x2340. Other resolutions
  are scaled to that reference, which usually works, but re-cut the templates if
  matching gets flaky.
- **Unknown popups** are handled on the phone by the OCR fallback (see
  [`android/README.md`](android/README.md)); the desktop tool has no equivalent and
  will simply time out. Author and validate recipes here, grind on the phone.
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
