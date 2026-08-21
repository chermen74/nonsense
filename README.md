# Nonsense

A sheer, full-screen surface for messing about. While the app is open, the whole screen is
the toy — flick a matte ball around or spin a dial. A tint over a translucent
window means you can still see the screen (and incoming notifications)
underneath; how sheer it is, is yours to set. Exit like any normal app and your
device is back.

No scores. No sounds. No skins. Not for children. Just quiet, mindless flicking.

## Four toys, identical everywhere

- **Ball** — touch anywhere; the ball comes to your finger. Flick to send it
  coasting. It bounces off screen edges (haptic tap on impact, Android only)
  and slows with friction. Eight sizes and six shapes — see below. Turn on
  **catching** and it stops coming to you: you have to land on it.
- **Dial** — solid disc, center screen. Drag around its center to spin,
  release to let it coast. Detents every 12° tick through your thumb
  (Android). On desktop the scroll wheel spins it from anywhere.
- **Bumpers** — the ball plus a table of outline bumpers in a loose pinball
  layout. Each hit reflects the ball with a small kick (capped, so it can't
  run away) and a haptic tap. The table is yours to arrange, and the ball can
  paint while it plays — see below.
- **Paint** — the ball leaves a trail wherever it goes, flicked or dragged.
  A quiet strip along the bottom edge: the nine colour families, eight ball
  sizes and six ball shapes. Two-finger tap (Android) or
  `C` (desktop) clears the canvas. Traces float over the sheer scrim, so
  the picture hangs over whatever's behind it.
- **Double-tap** (Android) / **Tab** (desktop) cycles modes; `1`–`4` jumps
  straight to one on desktop (they pick a bumper shape while editing).

## Catching (ball mode)

Off by default, because the ball coming to your finger is the toy's resting
character. Press `G`, or the **catch** button in the browser build, and ball
mode turns into something you have to be accurate at:

- A press only grabs the ball if it lands on it. Miss and the ball carries on;
  a ring fades where you reached. No sound, no score, no penalty — just enough
  to tell you the ball wasn't there.
- A catch **holds the ball where you caught it**. It does not snap to the
  cursor: that would undo the catch you just made. Drag carries it by the
  offset you grabbed it at, and release throws it as always.
- Catching is measured against the ball's real outline, so a bar is caught
  along the bar and missed across its narrow side at the same reach.
- Small balls get slack, big ones don't: the tolerance is
  `max(0, 20 − radius × 0.35)` px, so a bead is a forgiving 18px target and a
  grapefruit you simply have to hit.

Only ball mode. Bumpers and paint still bring the ball to your finger — the
table and the canvas are about the ball's path, not about grabbing it.

## The ball: eight sizes, six shapes

The ball is no longer just a medium graphite circle. It comes in eight sizes —
0.12× to 2.1× the base radius, a bead through to a grapefruit — and six shapes:
**circle, triangle, square, pentagon, hexagon, bar**. Both apply in every mode
that has a ball (ball, bumpers, paint), not just in paint.

- `[` and `]` step the size down and up. The two smallest are properly small;
  motion substepping is scaled to the radius so even a bead at 4000px/s still
  registers a bumper.
- `S` cycles the shape, `Shift+S` goes back.
- The scroll wheel resizes the ball in ball and bumpers (it still spins the
  dial in dial mode).
- In paint, the bottom strip carries all three: colours, sizes, shapes. The
  size chips are drawn in whatever shape the ball is currently wearing.
- Everywhere else the strip stays hidden — it fades in for a couple of seconds
  when you change size or shape, then leaves the field empty again. The app
  keeps its no-interface character at rest.

A non-round ball **tumbles**. Every impact imparts spin from the tangential
part of the blow, so a bar cartwheels off a wall and a square clatters through
the bumpers. Spin decays on its own and stops dead while you're holding it.

Shapes are convex outlines on a unit circumradius, shared with the bumpers, so
"size" means the same thing whatever shape is wearing it. Collision handles
every pairing: circle/circle analytically, circle/polygon by nearest edge, and
polygon/polygon by separating axis. Motion is substepped — a fast flick is
resolved in up to sixteen slices per frame — so nothing tunnels through a wall
or skips a bumper, and contacts are caught shallow enough to bounce sensibly.

Wall bounces use the ball's real outline rather than a circle drawn round it,
and only reverse the velocity when the ball is actually heading into the wall.
Without that second test a corner that rotates back into contact flips the
velocity twice and pins the ball to the edge.

When painting, the trail width follows how wide the shape actually is (its
inradius) rather than its circumradius — otherwise a bar inks a stripe as wide
as it is long. A circle at 1.0 paints exactly as it always did.

Size and shape persist in `localStorage` alongside the bumper table.

## Colour, ink and tint

The palette is thirty-six inks: **nine families** — graphite, bone, oxblood,
rust, ochre, moss, teal, slate, plum — in **four tones** each. The middle tone
of every family is hand-picked (the original six are all still in there, in
their own columns) and the rest are mixed toward white or black from it, so a
family holds its hue across the range and nothing turns garish.

Two separate kinds of see-through, and they are the point of the app rather
than decoration:

- **Translucency** — how solid the ink is: 15/30/50/75/100%. It applies to the
  ball, the dial and the trail, so a 30% ball lets the painting show through
  itself.
- **Screen tint** — how sheer the whole pane is over your desktop:
  0/6/12/18/25/34%. 12% is the original. This used to be a CSS background on
  the body; the canvas paints it now, which makes it adjustable in one place
  and behaves identically over a transparent Electron window and inside a page.

Press `,` for the palette drawer — the full grid, plus both rows. `A` cycles
translucency, `T` cycles the tint, `1`–`4` pick a tone, `Esc` closes. The strip
along the bottom carries one swatch per family at the tone you're on; tapping
the family you're already on opens the drawer. All of it persists.

### Why translucent ink needs a second layer

Stroking a translucent line segment by segment does not work. Each segment is
composited separately, so every round cap overlaps the last one and re-darkens
it — a 15% trail comes out looking solid, and beaded along its length.

So the stroke being laid down right now goes to its own scratch layer at full
opacity, where overlapping itself costs nothing. When the stroke finishes — the
ball comes to rest, or the ink changes — that layer is composited onto the
trail once, at its alpha. Separate strokes still build up where they cross,
which is what ink should do: two 30% strokes read 51%, not 60%.

## Arranging the bumper table

Press `E` on the bumpers screen (or the **edit** button in the browser build)
and the ball freezes so you can lay the table out:

- **Drag** a bumper to move it. **Red handle** resizes, **blue handle** rotates.
- **shape** cycles through all six outlines, or press `1`–`6`.
- **+ add** drops a new bumper in; **delete** removes the selected one;
  **reset** restores the factory five.
- `[` and `]` nudge size, `R` rotates a step, `Esc` leaves edit.

Bumpers are stored as fractions of the field rather than pixels, so a table
you build on a laptop still looks right on a monitor, and survives a resize.
Layouts persist in `localStorage` — blocked storage just means the table
resets next launch, which is not worth an error dialog in a fidget toy.

Collision is one routine: circles reflect off the centre normal, and every
other shape is treated as a convex polygon, reflecting off the nearest edge.
A ball that ends up inside a bumper is ejected along the nearest face rather
than left to rattle.

**Paint on the bumper screen** — press `P` to let the ball ink a trail while
it bounces. The palette and size strip along the bottom work exactly as they
do in paint mode, `C` clears, and the trail draws under the bumper outlines
so the table stays readable. Editing hides the strip so it can't eat your
edit taps.

## android/ — the flagship

Translucent activity (`Theme.Nonsense.Sheer`): the launcher stays visible under
the scrim, notification banners drop in on top as normal, and every touch
belongs to the fidget while it's foregrounded. Back gesture exits.

Build: open `android/` in Android Studio and run on a device, or just
`cd android && ./gradlew assembleDebug`. minSdk 26, no dependencies beyond
core-ktx. The Gradle wrapper is pinned to 8.7, the version AGP 8.5.2 is tested
against.

CI builds it too — `.github/workflows/android.yml` runs the unit tests, then
`assembleDebug` on every push that touches `android/`, and publishes the APK as
the `android-debug` prerelease.

### Why there is a keystore in the repo

`app/debug.keystore` is checked in deliberately. Without it a fresh CI runner
has no `~/.android/debug.keystore`, so Gradle makes one during the build and
every APK ends up with a different signer. Android will not install one over
the last, and Play Protect meets a brand new unknown app every time. The
published APK proved it: its certificate's `notBefore` was the minute the build
ran.

It is a debug key with the conventional `android` / `androiddebugkey`
credentials. It cannot publish to Play, and it is public by design in the same
way the keystore shipped inside the Android SDK is. The cost of that choice is
that anyone holding it can build an APK a phone would accept as an update to
`com.nonsense` — they would still have to persuade you to sideload it. For a
personal sideloaded toy that is the usual trade; for anything distributed more
widely, move the key into an Actions secret instead.

`tools/apk-signer.py` reads the certificate back out of a built APK — the
signature is v2/v3, which `keytool` cannot read — and CI fails the build if it
is not the committed key, so this cannot come back quietly. That release asset is a direct `.apk` link, which
is what makes it installable from a phone browser; a workflow artifact arrives
as a zip and is awkward to open on a device. It is a debug-key build, so
Android will ask you to allow installs from whatever app you downloaded it
with.

The two clients are level now. Android has the editable bumper table, the six
ball shapes and eight sizes, the thirty-six ink palette, translucency, screen
tint and catching.

The port is split in two on purpose. `Toy.kt` holds the whole simulation and
imports no `android.*` anywhere, so the parts most likely to be wrong — the
collision, the wall containment, the catch geometry, the layout arithmetic —
run under plain JUnit on CI instead of being eyeballed on a device.
`NonsenseView.kt` is only input and pixels. CI runs the tests before it builds,
so a physics regression fails the build rather than shipping an APK.

Two deliberate differences, both because a phone has no keyboard:

- **Long press** does what a key does on desktop: on the bumper table it
  toggles editing, in ball mode it toggles catching.
- **The bottom strip stays visible** in every mode but the dial. Desktop hides
  it because `[`, `]` and `S` can reach size and shape without it; here it is
  the only way in.

Tuning knobs are all constants at the top of `NonsenseView.kt`:
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
   Spin decay and restitution per shape are the obvious knobs.
2. Desktop tray icon + global hotkey to summon/dismiss instead of launching.
3. On-device tuning of the Android port. The simulation is under test, but
   feel — haptic weight, catch tolerance under a thumb, how big the strip
   wants to be — can only be judged on hardware.
4. Android Quick Settings tile for one-swipe entry.
5. ~~User-adjustable scrim~~ — done, as **screen tint** in the palette drawer.
