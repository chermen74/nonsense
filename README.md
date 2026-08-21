# Nonsense

A sheer, full-screen surface for messing about. While the app is open, the whole screen is
the toy — flick a matte ball around, spin a dial, or throw lightning at the walls. A tint over a translucent
window means you can still see the screen (and incoming notifications)
underneath; how sheer it is, is yours to set. Exit like any normal app and your
device is back.

No scores. No sounds. No skins. Not for children. Just quiet, mindless flicking.

## The front door

It opens on its own name and a list of what it can do. None of the toys
announce themselves from the inside — a field with a ball in it looks the same
whether or not it will let you paint — so the menu is where they are named,
each with a line saying what it is. **Menu** in the bottom-right corner (and
`Esc` on a keyboard, and the leftmost chip in the Android mode row) goes back
to it. Whichever toy you left running is remembered; you still come back
through the front door.

## Five toys, identical everywhere

- **Ball** — touch anywhere; the ball comes to your finger. Flick to send it
  coasting. It bounces off screen edges (haptic tap on impact, Android only)
  and slows with friction. Eight sizes and six shapes — see below. Turn on
  **catching** and it stops coming to you: you have to land on it.
- **Dial** — a knurled wheel, centre screen. Eighteen ribs, one of them marked
  so a turn stays countable, and a red index at the top that the ribs click
  past. Drag around its centre to spin, release to let it coast — about twenty
  seconds from a hard flick. On desktop the scroll wheel spins it from
  anywhere. See **the dial** below for the two things that used to stop it
  dead.
- **Bumpers** — the ball plus a table of outline bumpers in a loose pinball
  layout. Each hit reflects the ball with a small kick (capped, so it can't
  run away) and a haptic tap. The table is yours to arrange, and the ball can
  paint while it plays — see below.
- **Lightning** — flick anywhere and a bolt leaves your finger in the
  direction you threw it, jagging and ricocheting off the walls until it burns
  out. Each wall strike is a haptic knock, weighted by how hard it hit. Nothing
  to arrange and nothing to hold: it is the one toy that is only ever a throw.
  See **lightning** below.
- **Paint** — the ball leaves a trail wherever it goes, flicked or dragged.
  A quiet strip along the bottom edge: the nine colour families, eight ball
  sizes and six ball shapes. Two-finger tap (Android) or
  `C` (desktop) clears the canvas. Traces float over the sheer scrim, so
  the picture hangs over whatever's behind it.
- **Double-tap** (Android) / **Tab** (desktop) cycles modes; `1`–`5` jumps
  straight to one on desktop (they pick a bumper shape while editing).

## The dial

It used to be a plain disc with a single dot on it, and it never looked like it
was turning, for three reasons — all now fixed:

- **There was nothing on it to watch.** One dot at 0.8r is not motion. It has
  eighteen ribs now, drawn from the hub to the rim, one of them in a darker
  tone so you can count revolutions.
- **The friction was wrong.** `0.35` per second sheds two thirds of the speed
  every second: it was practically still before you had finished letting go.
  It is `0.78` now, which is roughly a twenty-second run-down.
- **The release speed was read from the last drag sample.** A finger nearly
  always stalls for a frame or two before it lifts, so a hard flick was handed
  back a wheel at rest. Speed is taken over a 120ms window instead — the same
  trick the ball's flick already used.

Top speed is capped at 14 rad/s. That is deliberate rather than defensive: at
eighteen ribs it works out to forty rib passes a second against a screen that
redraws sixty times, and any faster and the knurl stops turning and starts
crawling backwards. At speed the ribs are also drawn wider and fainter, so a
fast spin blurs instead of strobing.

## Lightning

A flick throws a bolt. It is the only toy with nothing on screen at rest —
there is no object to grab, so a press that does not travel does nothing at
all, and a flick below 420px/s is a tap rather than a throw.

- **It carries your flick.** Direction and speed both come from the throw, at
  2× the measured velocity and capped at 9000px/s. The same 120ms velocity
  window the ball and the dial use — the last drag sample is a stalled finger,
  not a throw.
- **It ricochets.** Twelve wall bounces or 1.15 seconds, whichever runs out
  first, and it fades as it goes. Fourteen bolts can be in the air at once;
  the oldest is dropped rather than refusing the throw.
- **Every wall strike is a knock**, weighted the same way the ball's is — the
  impact goes through the same path, so the haptics were already tuned.

The zigzag is part of the simulation, not the drawing, which is what makes it
testable and identical on all three builds: a node is laid every 4.5% of the
short edge, displaced perpendicular to travel, and the displacement
**alternates sign**. That last word is the whole trick. A random sign is a
random walk, and a random walk wanders — the first version read as a wobbly
rope rather than lightning. Alternating the side and randomising only the
magnitude gives the sharp back-and-forth a spark actually has.

Nodes are seeded from a small integer generator shared literal-for-literal
across Kotlin, Swift and JavaScript, so a bolt thrown the same way is the same
bolt everywhere. (Kotlin's `Int` wraps on overflow and Swift's traps, so the
Swift port uses `&*` and `&+` — there is a test that says so.) A kink, once
laid, never moves; the path is a rolling window of thirty nodes, about a
screen-height of streak, so a long-lived bolt loses its tail rather than
drawing every crossing it ever made. That window was 400 to begin with, and a
fast bolt kept a dozen crossings on screen at once: a maze rather than a
strike.

Shortening it exposed the alternating sign's one real bug. The side was read
off the node count — which stops changing the moment the window is full. Past
that every kink threw the same way, and a steady side is a curve: long bolts
straightened into smooth arcs. It is carried on the bolt now. No test could
see it, because every node was still off-line and every kink still held its
place; the one that catches it fires straight down a tall field and asks that
each node land on the opposite side of the last.

The bolt is drawn in three passes — a wide dim wash, a darker sheath, then the
near-white filament. The sheath is not decoration: on the paper canvas a white
hairline is invisible, and without something dark immediately around it the
bolt read as a hollow outline.

Lightning is free, not part of the unlock. The paid tier is the studio —
painting, arranging the table, the full palette — and a genuinely complete
free toy is the best answer there is to Apple's minimum-functionality
guideline.

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

And a third thing, which is what it all sits on:

- **Canvas** — sheer, paper, linen, sage, slate, ink, black. **Sheer** is the
  default and the original behaviour: nothing is painted, the window stays
  translucent, and your home screen or your desktop shows through. The other
  six are solid grounds, for a phone that cannot float an app over its home
  screen, or simply for when you want a colour to draw on. The screen tint
  washes over either one, so it means the same thing in both.

Press `,` for the palette drawer — the full grid, plus every row. `A` cycles
translucency, `K` cycles the canvas, `T` cycles the tint, `1`–`4` pick a tone,
`Esc` closes. The strip along the bottom carries one swatch per family at the
tone you're on; tapping the family you're already on opens the drawer. All of
it persists.

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

Press `E` on the bumpers screen — or the **EDIT** button the canvas draws next
to **MENU**, or the **edit** chip in the Android mode row — and the ball
freezes so you can lay the table out:

- **Drag** a bumper to move it. **Red handle** resizes, **blue handle** rotates.
- **shape** cycles through all six outlines, or press `1`–`6`.
- **+ add** drops a new bumper in; **delete** removes the selected one;
  **reset** restores the factory five.
- **ink** — drawn as a swatch of the selected bumper's own colour — points the
  whole thirty-six-colour palette at that bumper. Cycling nine families one tap
  at a time is no way to pick a colour when the grid already exists, so the
  drawer just changes who it is talking to: the heading reads BUMPER instead of
  INK, the highlight follows the bumper, and translucency, canvas and tint stay
  global, because those are not per-bumper things.
- `[` and `]` nudge size, `R` rotates a step, `Esc` leaves edit.

Every bumper carries its own ink, drawn at 72% so a painting still shows
faintly through the table rather than being walled off by it. The factory five
arrive in oxblood, slate, ochre, moss and teal. Colour is paint, not physics —
a recoloured bumper bounces exactly as it did.

Tables saved before bumpers had a colour still load, in graphite: five fields
where there are now seven, and an arrangement someone built is not worth
discarding over a new field. That fallback is in `Toy.kt` rather than in the
view, so it is under test.

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

### Haptics: two versions that ran and could not be felt

The bump when the ball meets a wall is the one thing the desktop build cannot
do, and it took three attempts to make it real.

1. `performHapticFeedback(CLOCK_TICK)` — the lightest constant the platform
   has, meant for scroll ticks, and silenced outright when the system
   touch-feedback switch is off. On plenty of devices it renders as nothing.
2. `createOneShot` for nine to twenty-four milliseconds. A nine millisecond
   pulse is shorter than the time a linear actuator takes to reach full
   travel, so on a modern phone it moves almost nothing.
3. What is there now: `VibrationEffect.startComposition()` with the
   `PRIMITIVE_CLICK` / `PRIMITIVE_TICK` waveforms the platform uses for its
   own clicks (API 30+), falling back to `createPredefined` (API 29) and then
   to a one-shot long enough to actually move an actuator. Every one carries
   **game** usage attributes, so this reads as a toy making a noise rather
   than a button being pressed, and the touch-feedback switch does not apply
   to it.

Weight scales with impact: nothing below 200px/s, full weight by 2600. A wall
is a flat knock; a bumper adds a second, softer beat a few milliseconds later,
because a bumper throws the ball back. The dial clicks once per rib, lighter
the faster it spins, and clicks arriving closer than 26ms apart are dropped —
forty firm clicks a second is a buzz, not a knurl.

**HAPTICS** in the palette drawer is off / soft / firm, firm by default. On the
web build the row only appears where the browser can actually do something:
desktop Chrome has `navigator.vibrate` and ignores it, so the presence of the
function is not enough — it has to be a touch screen too.

Tuning knobs are all constants at the top of `NonsenseView.kt` and `Toy.kt`:
`friction`, `restitution`, `impactStrength`'s floor and ceiling, `dialFriction`,
`MAX_DIAL_OMEGA`, `DIAL_RIBS`, `DIAL_WINDOW`, scrim alpha, ball/dial radii.
Tune on a real device; haptics don't exist in the emulator.

### The icon

A penny under a red ban — an adaptive icon, so the launcher masks it into
whatever shape it likes. Everything sits inside the 66dp safe circle: the ban's
outer edge lands at 32.8dp from centre against the 33dp the mask is guaranteed
to keep. The bar runs top-left to bottom-right, which is the way round ISO 3864
draws it, and the bust faces into it so the bar passes behind the head rather
than across the face. There is a monochrome layer for themed icons.

### Verifying it without an Android SDK

`dl.google.com` is not always reachable, which means no local SDK and no
`./gradlew`. That does not have to mean pushing to find out whether it
compiles:

```
# Kotlin compiler and JUnit from Maven Central; the framework classes from
# Robolectric's android-all jar, which is on Central too.
kotlinc -d out-main  android/app/src/main/java/com/nonsense/Toy.kt
kotlinc -d out-view  -cp android-all.jar:out-main  NonsenseView.kt MainActivity.kt
kotlinc -d out-test  -cp out-main:junit.jar        ToyTest.kt
java -cp out-main:out-test:junit.jar:hamcrest.jar org.junit.runner.JUnitCore com.nonsense.ToyTest
```

The one gap is `androidx`, which is only published on Google's Maven; twenty
lines of stub for `WindowInsetsCompat` and `WindowCompat` covers what this app
uses. It found a smart-cast error in ten seconds that CI had taken two minutes
to report and then buried under six hundred lines of Gradle stack trace.

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

## web/ — the page, and the iPhone

`web/index.html` is generated from `desktop/renderer.html`, so the page and the
app cannot drift:

```
node web/build.mjs            # renderer.html + web/index.src.html -> web/index.html
```

`index.src.html` is the page around the toy — the DOM chips that stand in for a
keyboard, and the soft blocks that stand in for the desktop a transparent
window would show. `.github/workflows/web.yml` rebuilds it on every push and
fails if the committed `index.html` does not match, which is the only thing
keeping a hand-edit from going unnoticed.

### iOS

An iPhone cannot float an app over its home screen — Apple does not allow
drawing over other apps — so the sheer overlay, which is the whole reason the
Android build exists, cannot be built there. **The solid canvases are what
takes its place**, and they are now in both builds.

What works on an iPhone today is the web build: open the page in Safari and it
runs. `Share → Add to Home Screen` gives it its own icon and a full screen with
no browser chrome, since the page carries `apple-mobile-web-app-capable` and an
`apple-touch-icon`. Two honest caveats: iOS Safari has no `navigator.vibrate`,
so there are no haptics there at all (the control hides itself rather than
lying about it), and this needs the page served from somewhere Safari can
reach — GitHub Pages pointed at `web/` would do it; the raw file URL will not,
because GitHub serves it as `text/plain`.

A native iOS app now exists in `ios/` — the simulation ported to Swift and the
view layer rewritten in SwiftUI. `ios/README.md` says exactly what is verified
and what is not. `.github/workflows/ios.yml` does the parts that need a Mac on
GitHub's macOS runners: `swift test`, then `xcodebuild` and a simulator that is
booted, launched and **photographed**, so every screen can be looked at without
owning one. Shipping it still needs a Mac and $99/year — see `STORE.md`.

## Roadmap candidates (unbuilt, in rough order of value)

1. On-device physics tuning until the ball feels like an object, not a cursor.
   Spin decay and restitution per shape are the obvious knobs.
2. Desktop tray icon + global hotkey to summon/dismiss instead of launching.
3. On-device tuning of the Android port. The simulation is under test, but
   feel — haptic weight, catch tolerance under a thumb, how big the strip
   wants to be — can only be judged on hardware.
4. Android Quick Settings tile for one-swipe entry.
5. ~~User-adjustable scrim~~ — done, as **screen tint** in the palette drawer.
