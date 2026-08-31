# Autotapper for Android — runs on the phone

The tool in the parent directory drives a phone from a PC over ADB. This is the
same state machine and the same validated recipe, running **on the phone itself**
— no computer, no cable, no root.

| | Desktop (`../tapper.py`) | This app |
| --- | --- | --- |
| Needs a PC | yes, tethered | no |
| Needs root | no | no |
| Capture | `adb exec-out screencap` | MediaProjection |
| Taps | `adb shell input tap` | AccessibilityService `dispatchGesture` |
| Survives reboot | n/a | yes, re-grant capture and go |

## Install

Build it (below) or install the APK, then two one-time grants:

1. **Accessibility** — Settings → Accessibility → Autotapper → On.
   This is what lets it tap. Android deliberately offers no way to grant it
   programmatically; you have to flip it yourself. The app shows a warning until
   you do.
2. **Screen capture** — the system dialog appears when you press Start or Probe.
   Android will not let this be permanent; you re-approve it each session.

Then press **Probe** first — see below for the order of operations.

## Loadouts

The **Loadout** dropdown lists every recipe the app can see, from two places:

- **built in** — shipped inside the APK, read-only.
- **saved** — folders under
  `Android/data/dev.autotapper/files/recipes/` on the device. No permission is
  needed to read them, they are visible in any file manager, and they survive an
  app update. A saved loadout shadows a built-in one of the same name, which is
  how you override a shipped default without losing it.

| button | |
| --- | --- |
| **Import** | unpack a `.zip` into saved storage |
| **Export** | zip the selected loadout and share it — mail it to yourself, keep a backup, send it to someone |
| **Delete** | remove a saved loadout (disabled for built-in ones) |

The last loadout you picked is remembered.

A loadout zip is just the recipe folder: `recipe.json` at the root plus a
`templates/` directory. A zip with everything inside one wrapping folder works
too. **Import loads the recipe before accepting it**, so a zip missing a template
fails immediately with a message rather than halfway through a grind.

Add one by hand instead if you prefer — drop the folder straight into
`Android/data/dev.autotapper/files/recipes/` over USB and it appears in the list.

## Use

- **Probe** — takes one screenshot and scores every gate. No taps.

  The screen-capture dialog can only be answered from this app, so at that moment
  *this app* is what is on screen. Capturing straight away would photograph
  Autotapper's own UI. So the flow is: press Probe, approve the dialog, and the
  app minimises itself and counts down (6s by default, adjustable) while you get
  back to the game. Capture happens when the countdown ends.

  With the stage screen up, `tap_fight` should read **YES** near 1.000. The probe
  also prints which app was in the foreground and shows a picture of what it
  actually captured, with each gate's search region drawn on it — if the numbers
  look wrong, that picture usually says why in one glance.
- **Start** — runs the loop for the number of iterations you set.
- **Stop** — also available from the notification, so you do not have to switch
  back to the app.

The log pane shows every gate as it fires with its score, and every tap.

## Build

Needs JDK 17+ and the Android SDK (platform 34, build-tools 34).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is debug-signed on purpose, so it installs without anyone
having to manage a keystore. It is not fit for distribution as-is.

It also builds **arm64-v8a only**. ML Kit ships its OCR pipeline as a ~11MB
native library per ABI, and carrying all four put the APK at 46MB against 18MB
for arm64 alone. Every phone this targets is arm64; remove the `ndk { abiFilters }`
block in `app/build.gradle.kts` for a universal APK.

## How it works

```
MediaProjection ──> ImageReader ──> greyscale ──> Matcher (NCC) ──> Engine
                                                                      │
                              AccessibilityService.dispatchGesture <──┘
```

- `core/Gray.kt` — greyscale image, box downscale, bilinear resize, std-dev.
- `core/Matcher.kt` — normalised cross-correlation, equivalent to OpenCV's
  `TM_CCOEFF_NORMED`.
- `core/Recipe.kt` — loads the same `recipe.json` + PNG templates from assets.
- `core/Engine.kt` — the visual-gated state machine, same rules as the desktop
  tool: resync to the step already on screen before the first loop, confirm over
  consecutive frames, contrast gate, interrupts checked in any state, stop on
  timeout rather than tap blindly.
- `app/TapperService.kt` — foreground service owning the projection and the loop.
- `app/TapService.kt` — the accessibility service that actually taps.

### When nothing matches: OCR fallback

Template gates only know the screens they were cut from. A popup nobody has
templated stalls the loop until someone cuts a new template — which happened on
the very first real run, with a Friend Request layout the source recording never
showed.

So when a step has been waiting far longer than expected, or an interrupt keeps
firing without the screen changing, the app reads the words on screen with ML
Kit's bundled Latin model and dismisses the dialog by its button text. Offline,
free per call, and no screenshot leaves the phone. It costs ~43MB of APK, which
is the whole reason this build is ~48MB rather than ~5MB.

**The safety policy is deliberately not the model's decision.** Recognition
decides what the words *are*; `core/DismissPolicy.kt` — a plain lookup table —
decides what may be touched:

| | |
| --- | --- |
| Refuse outright | BUY, PURCHASE, SHOP, STORE, PAY, SUMMON, RECHARGE, REFILL, CONTINUE, REVIVE, STONE(S), EXCHANGE, PRICE, COST |
| Prefer | CANCEL, CLOSE, NO, LATER, SKIP, DECLINE, BACK |
| Fall back to | OK |

If any forbidden word appears *anywhere* on screen it taps nothing at all and
says why. Declining beats accepting, and OK is only used when the dialog offers
no way out but forward. In Dokkan the failure mode is spending Dragon Stones, so
"refuse and stop" is always the right answer when unsure.

`tools/matcher-parity` table-tests this (`gradle run --args="policy"`) across the
real dialogs — including a stamina refill, a continue-after-loss, a multi-summon
and a shop purchase, all of which must be refused.

Tune it per recipe with `"ocr_unstick"`, `"unstick_after"` (seconds of waiting
before it reads the screen, default 20) and `"unstick_max"` (attempts per step,
default 3).

### Interrupts during a settle-wait

`settle_first` (above) decides "ready" by whether the frame is still changing.
That definition has a hole: a **static** popup is, by that measure, not moving -
so without an explicit check, a popup sitting motionless on screen reads as
SETTLED and gets tapped straight through to whatever is behind it, instead of
being dismissed. The settle-wait now checks interrupts on every poll, exactly
like a normal step, and resets its stability timer whenever one fires.

`tools/matcher-parity`'s `settle` check (`gradle run --args="settle"`) proves it:
a scripted actuator shows a popup for exactly one poll in the middle of a
settle-wait, then removes it, and asserts the popup gate is tapped *before* the
step's own tap. Confirmed this fails against the pre-fix code (taps the step
directly, never touches the popup) and passes against the fix. The desktop tool
has the identical check in `tools/tests/test_settle_interrupt.py`.

### Why the matcher is hand-written

OpenCV's Android SDK is ~100MB of native libraries for one function. `Matcher.kt`
implements `TM_CCOEFF_NORMED` directly instead, which keeps the APK at ~5MB.

That is only safe if the numbers agree, because every threshold in the recipe was
validated against OpenCV. `tools/matcher-parity` checks exactly that — it compiles
the *shipped* `Gray.kt` and `Matcher.kt` on the JVM and compares them to OpenCV on
real frames:

```bash
python3 tools/matcher-parity/export_reference.py \
    --recipe ../recipes/dokkan_goku_black --video clip.mp4 --out /tmp/parity
cd tools/matcher-parity && gradle run --args=/tmp/parity
```

Current result over 420 (gate, frame) cases: **0 threshold disagreements, 0px
position error, worst score delta 0.000003** anywhere near the decision threshold.

Two bugs came out of that test and are worth knowing about if you touch the matcher:

- **A single coarse peak is not enough.** Searching a downscaled copy and refining
  in a ±2 window fails on smooth, low-detail templates — at 1/8 scale the
  correlation surface is nearly flat, the argmax lands in the wrong basin, and the
  refinement cannot walk out. It carries the best 4 peaks down the pyramid instead.
- **Float32 is not enough.** The variance term is a difference of two large, nearly
  equal numbers. On a near-uniform window that cancellation leaves only noise, and
  dividing by its square root produced a score of **3.55** — impossible for a
  normalised correlation. Accumulation is in Double, with a relative variance guard
  and a clamp to [-1, 1].

## Troubleshooting

### “Controlled by Restricted Setting”, or the Accessibility toggle is greyed out

Expected, and not a fault in the app. From Android 13, an app installed outside
an app store cannot be granted Accessibility until you explicitly lift the
restriction — sideloaded apps abusing accessibility is the exact attack this
blocks. Unlock it once:

1. **Samsung only, do this first:** Settings → Security and privacy →
   **Auto Blocker** → off. While Auto Blocker is on it re-applies the block and
   the menu item in step 2 may not even appear.
2. Settings → Apps → Autotapper → **⋮ (top right)** → **Allow restricted
   settings**. The app's *Open app info* button takes you straight there. The
   item only shows up after you have tried to enable the toggle once and been
   refused, which is the dialog that sent you here.
3. Then Settings → Accessibility → Autotapper → On.

If the menu item is missing no matter what, set the flag directly over ADB:

```bash
adb shell appops set dev.autotapper ACCESS_RESTRICTED_SETTINGS allow
```

Or avoid the whole thing: installing with `adb install -r app-release.apk`
never trips the restriction, because it applies to file-manager style sideloads
rather than to installs made through the session-based installer.

### Probe shows every gate at a low score

Look at the contrast column first, and at the preview image.

**Contrast at or near 0** means that region was a single flat colour. No game
screen looks like that, so the capture is of something else — most often
Autotapper itself, if the countdown ran out before you got back to the game.
Raise the lead-in seconds and try again. The "foreground app" line in the probe
output names what was actually on screen.

**Contrast is healthy but scores are low** means it saw a real screen that is not
the one the templates expect. Check you are on the event stage screen, not the
home screen or a different event.

**Scores are low with the right screen up** points at resolution: templates were
cut at 1080x2340. Frames are scaled to that reference so a different size usually
still works, but re-cut the templates from a recording on *your* device if
matching stays unreliable. See `../README.md`.

### Everything scores ~0 and the screen looks black

The app sets `FLAG_SECURE`, which makes MediaProjection capture black. Nothing
can be done about that short of root.

## Memory

A capture-and-match loop at 1080x2340 is easy to write in a way that destroys a
phone, and the first version did. Every `capture()` allocated a fresh
`FloatArray(w * h)` — 9.6MB — straight into the large-object heap, plus a fresh
ROI crop and a fresh matcher pyramid on every poll. Measured: **12.2MB per poll,
~24MB/s sustained, ~29GB churned over a twenty minute run**, all while the app
also held a MediaProjection mirror of a 60fps game. That was enough to take a
whole device down.

Everything on the hot path now refills buffers it already owns:

| | per poll |
| --- | --- |
| allocating (before) | 11.61 MB |
| reusing buffers (after) | **0.01 MB** |

Measure it yourself — `gradle run --args="alloc <reference-data-dir>"` in
`tools/matcher-parity` fails if steady-state allocation exceeds 1MB per poll.

Two things to be careful of if you touch this:

- **Settle detection needs two buffers.** Reuse one and `prev` and the current
  frame become the same array, every difference is zero, and it declares the
  screen settled instantly.
- **`capture()` returns the same object every time.** It is valid until the next
  capture; nothing may retain it.

There is also a heap watchdog: above 85% for five consecutive polls the run stops
rather than pushing the device into a state where stopping is no longer possible.
Each loop logs `heap used/max MB` so a run that drifts is visible in the log.

## Limits

- **Screen capture is per-session.** Android re-prompts after a reboot or when the
  projection is revoked. There is no way around it; it is a privacy guarantee.
- **FLAG_SECURE apps capture as black.** Banking apps and some DRM video will not
  work. Dokkan is fine — you screen-recorded it, which uses the same API.
- **Start anywhere in the cycle.** The first thing it does is work out which step
  the game is already on and resume there, so you do not have to be on the stage
  screen when you press Start.
- **The game must be in the foreground.** The loop taps whatever is on screen. If
  you switch apps mid-run, stop it first.
- **Same recipe limits as the desktop tool** — only the popups it knows about, no
  stamina handling, and it stops on an unexpected screen rather than tapping
  blindly. See `../README.md`.
- Battery: a continuous screen-capture loop is not cheap. Keep it plugged in for
  long runs.
