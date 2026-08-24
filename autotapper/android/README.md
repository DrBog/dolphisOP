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

Then open the game, switch to Autotapper, and press **Probe** first.

## Use

- **Probe** — takes one screenshot and scores every gate. No taps. Run it with
  the game's stage screen open: `tap_fight` should read **YES** near 1.000.
  This is how you tell "it cannot see the screen" apart from "it cannot tap".
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
  tool: confirm over consecutive frames, contrast gate, interrupts checked in any
  state, stop on timeout rather than tap blindly.
- `app/TapperService.kt` — foreground service owning the projection and the loop.
- `app/TapService.kt` — the accessibility service that actually taps.

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

The app is capturing something other than the game — usually the launcher,
because you probed without switching back to the game first. Open the game, then
switch to Autotapper and probe.

If scores are low with the right screen up, the resolution likely differs from
the 1080x2340 the templates were cut at. Frames are scaled to that reference, so
it usually still works, but re-cut the templates from a recording on *your*
device if matching is unreliable. See `../README.md`.

### Everything scores ~0 and the screen looks black

The app sets `FLAG_SECURE`, which makes MediaProjection capture black. Nothing
can be done about that short of root.

## Limits

- **Screen capture is per-session.** Android re-prompts after a reboot or when the
  projection is revoked. There is no way around it; it is a privacy guarantee.
- **FLAG_SECURE apps capture as black.** Banking apps and some DRM video will not
  work. Dokkan is fine — you screen-recorded it, which uses the same API.
- **The game must be in the foreground.** The loop taps whatever is on screen. If
  you switch apps mid-run, stop it first.
- **Same recipe limits as the desktop tool** — only the popups it knows about, no
  stamina handling, and it stops on an unexpected screen rather than tapping
  blindly. See `../README.md`.
- Battery: a continuous screen-capture loop is not cheap. Keep it plugged in for
  long runs.
