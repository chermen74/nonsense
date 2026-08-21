# Nonsense for iOS

Two commands, on a Mac:

```bash
cd ios && swift test          # verifies the simulation port — no Xcode needed
brew install xcodegen         # once
cd ios && xcodegen && open Nonsense.xcodeproj
```

## What is verified and what is not

Be clear-eyed about this, because it decides where to spend the first hour.

**`Sources/NonsenseCore/Toy.swift` — the simulation — is verified twice over,
but neither check ran a Swift compiler.** It was written on a machine with no
Swift toolchain (`download.swift.org` is unreachable from it), so:

- `Tests/NonsenseCoreTests/ToyTests.swift` is the Kotlin suite ported
  alongside it — the collision, the wall containment, the catch geometry, the
  dial's flick window, the paywall's every gate. `swift test` runs it in a
  couple of seconds and is the first thing to run. If the port is unfaithful,
  this says so before you have opened Xcode.
- `tools/parity.py` compares the Kotlin and Swift sources literal by literal —
  every constant, palette entry, label and blurb. It runs in CI on every push
  that touches either file. A hand port's realistic failure is a mistyped
  constant, not a mistyped keyword, and that is what this catches.

**`App/` — the SwiftUI layer, StoreKit and the haptics — has never been
compiled.** Expect a handful of build errors on the first `⌘B`: a renamed
SwiftUI argument, a `GraphicsContext` overload. They will be shallow and Xcode
will point at each one. What is *not* shallow, and worth reading before
trusting, is `Store.swift` — a purchase flow cannot be exercised without App
Store Connect, so that is genuinely first-run code.

## How it is laid out

| | |
|---|---|
| `Sources/NonsenseCore/Toy.swift` | the whole simulation; no UIKit anywhere in it |
| `Tests/NonsenseCoreTests/` | the ported test suite |
| `App/ToyView.swift` | one `Canvas`, one `TimelineView`, all the drawing and input |
| `App/Store.swift` | StoreKit 2. The only file that knows there is a shop |
| `App/Haptics.swift` | deliberately short — see below |
| `project.yml` | XcodeGen spec instead of a checked-in `.xcodeproj` |

The split is the same one that made the Android port testable: `Toy` is state
and arithmetic with no platform in it, so the parts most likely to be wrong run
under plain XCTest instead of being eyeballed on a device.

## Why this is a port and not a shared module

Kotlin Multiplatform would compile `Toy.kt` into a framework and delete the
duplication outright. It was not worth it here: it needs a Mac to produce the
framework at all, it complicates the Android build for a solo hobby app, and it
would have made this port impossible to write on a machine with no Apple
toolchain. `tools/parity.py` buys most of the same safety for a hundred lines
of Python that runs anywhere.

If this ever grows past one person, that trade flips.

## Three things iOS does differently, on purpose

**There is no translucent window.** The Android build's whole reason to exist
is that it floats over your home screen; iOS does not allow drawing over other
apps, full stop. So "sheer" here means the app's own dark ground, and the six
solid canvases carry the weight the see-through used to. That is not a
compromise bolted on — it is why the canvases were built.

**Clearing a painting is a button, not a gesture.** Android clears on a
two-finger tap. A hidden gesture is not a feature anyone finds, and there was
room in the corner.

**No double-tap to cycle modes.** It fights the drag gesture that throws the
ball, and the mode row already names every toy on screen.

## Haptics

`Haptics.swift` is forty lines and should stay that way. The Android build
spent two attempts learning that a hand-rolled vibration envelope cannot be
felt — nine milliseconds is shorter than a linear actuator takes to reach full
travel. `UIImpactFeedbackGenerator` is the tuned waveform the system uses for
its own knocks and takes an intensity, so the temptation to invent a duration
is the bug. A wall is `.rigid`, a bumper is rounder, and the dial's clicks are
rate-limited to 26ms apart because forty firm clicks a second is a buzz rather
than a knurl.

## Shipping it

See `../STORE.md`. In short: $99/year, a Mac, a product ID matching
`Store.productID`, and Guideline 4.2 — minimum functionality — is a live risk
for a fidget toy. The free tier is built to be a genuinely complete toy partly
for that reason: a reviewer who has to pay to see most of an app is a reviewer
looking for a reason to reject it.
