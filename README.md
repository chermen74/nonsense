# Fidget

A sheer, full-screen fidget surface. While the app is open, the whole screen is
the toy — flick a matte ball around or spin a dial. A 12% tint over a
translucent window means you can still see the screen (and incoming
notifications) underneath. Exit like any normal app and your device is back.

No scores. No sounds. No skins. Not for children. Just quiet, mindless flicking.

## Four toys, identical everywhere

- **Ball** — touch anywhere; the ball comes to your finger. Flick to send it
  coasting. It bounces off screen edges (haptic tap on impact, Android only)
  and slows with friction.
- **Dial** — solid disc, center screen. Drag around its center to spin,
  release to let it coast. Detents every 12° tick through your thumb
  (Android). On desktop the scroll wheel spins it from anywhere.
- **Bumpers** — the ball plus five fixed outline bumpers in a loose pinball
  layout. Each hit reflects the ball with a small kick (capped, so it can't
  run away) and a haptic tap.
- **Paint** — the ball leaves a trail wherever it goes, flicked or dragged.
  A quiet strip along the bottom edge: six muted colors (graphite, oxblood,
  slate, moss, ochre, bone) and three sizes. Two-finger tap (Android) or
  `C` (desktop) clears the canvas. Traces float over the sheer scrim, so
  the picture hangs over whatever's behind it.
- **Double-tap** (Android) / **Tab** (desktop) cycles modes; `1`–`4` jumps
  straight to one on desktop.

## android/ — the flagship

Translucent activity (`Theme.Fidget.Sheer`): the launcher stays visible under
the scrim, notification banners drop in on top as normal, and every touch
belongs to the fidget while it's foregrounded. Back gesture exits.

Build: open `android/` in Android Studio (or point Claude Code at it) and run
on a device. minSdk 26, no dependencies beyond core-ktx. Nothing here has been
compiled yet — expect Claude Code to spend its first session getting a clean
build and then tuning feel on-device.

Tuning knobs are all constants at the top of `FidgetView.kt`:
`friction`, `restitution`, `bounceHapticMinV`, `detentRad`, `dialFriction`,
scrim alpha, ball/dial radii. Tune on a real device; haptics don't exist in
the emulator.

Known limitation: a translucent activity shows the *previous* screen behind
it. The launcher renders fine; an app behind it is paused (static). For the
home-screen use case this is exactly right.

## desktop/ — the glass pane

Electron: frameless, transparent, always-on-top window sized to the primary
display. Your Zoom call and email stay visible **and live** under the tint.
Click-drag to flick the ball, wheel to spin the dial, Tab to toggle,
Esc to quit.

```
cd desktop
npm install
npm start
```

Windows note: `fullscreen: true` kills transparency in Electron, which is why
`main.js` sizes a frameless window to display bounds instead. Don't "fix" that.

## iOS

Deliberately absent. Apple does not allow drawing over other apps or the home
screen, so the sheer overlay cannot exist there. If an iOS build ever happens,
it's the diluted version: solid background, same two toys, native notification
banners still drop in from the top.

## Roadmap candidates (unbuilt, in rough order of value)

1. On-device physics tuning until the ball feels like an object, not a cursor.
2. Desktop tray icon + global hotkey to summon/dismiss instead of launching.
3. Android Quick Settings tile for one-swipe entry.
4. User-adjustable scrim (0–25%) — the only setting this app should ever have.
