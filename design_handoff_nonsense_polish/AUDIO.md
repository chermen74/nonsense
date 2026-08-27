# Audio: making the toy feel physical

Companion to the visual handoff. Everything here is in **`ios/Sources/NonsenseCore/Toy.swift`** (`Voices` and `Synth`) plus two lines in `ios/App/Speaker.swift`. No audio files are involved and none should be added — the shared synth is why the phone, the page, and the desktop are the same instrument, and `tools/parity.py` checks that sample-for-sample.

**Reported problem:** every voice reads thin and tinny, and hits have no impact. **Target:** you feel the mass of the thing.

**Important:** whoever implements this can hear it; the person who wrote it could not. Treat every number below as a starting point to audition, not a spec. Change one group at a time and listen — the ordering below is by expected payoff.

---

## Why it sounds thin — five findings in the current code

**1. The instrument has no bottom octave.** `rootHz = 220.0` (A3) and `octaves = 3` counting *upward*, so every note the toy can play lives between 220Hz and ~1760Hz. Physical mass is heard at 60–160Hz. There is nothing down there at all. This is the largest single cause of "no impact."

**2. Every partial decays at the same rate.** `Synth.render` computes one envelope — `exp(-4t/decay)` — and applies it to all partials equally. Real struck objects shed their high partials first: a hit is bright for 30ms and dark for the rest. Holding the brightness constant across the whole tail is what "tinny" sounds like. This is the largest single cause of "thin."

**3. There is no transient.** Impact is a wideband click 1–5ms long, layered *under* the tone. Only `drum` has anything like it (`drumDrop`). Every other voice begins with a 4ms linear ramp into a steady tone, which reads as a beep starting rather than a thing being struck.

**4. The noise is full-band white.** `Toy.randUnit` unfiltered means `drum` (grit 0.55) and glass are hiss, not thud. Unfiltered white noise has most of its energy above 5kHz — it actively works against mass.

**5. Level is conservative and clipping is hard.** `headroom = 0.28`, and both `Synth.render` and the mixer in `Speaker.swift` clamp with `min(max(v,-1),1)`. Perceived impact is partly just loudness, and hard clipping on the occasional chord is harsh in exactly the way that reads as cheap.

---

## Change 1 — give it a bottom octave

```swift
public static let rootHz = 110.0   // was 220.0 — A2
public static let octaves = 4      // was 3 — keeps the same top, adds a bottom
```

Four octaves from 110Hz tops out around where three from 220 did, so nothing that used to be reachable is lost. `semitone()` and `hz()` need no changes.

If the high hits then feel too sparse, prefer `rootHz = 138.6` (C#3) over going back up — the point is that the low end exists.

**Also add a sub-partial to the sustaining voices**, at half the fundamental. A pipe and a struck string both have real energy an octave down, and it is the cheapest possible body:

```swift
case organ: return [(0.5, 0.30), (1, 0.55), (2, 0.20), (3, 0.09), (4, 0.03)]
case keys:  return [(0.5, 0.22), (1, 0.70), (2, 0.16), (5, 0.03)]
```

Note the upper partials came *down* as the sub went in. Total energy stays roughly constant; the balance moves downward. Do not just add the sub and leave the top alone — you will get a note that is both boomy and tinny.

## Change 2 — decay the high partials faster (the "thin" fix)

Move the envelope inside the partial loop and scale each partial's decay by its multiple. In `Synth.render`, replace the single `env`/partial-sum block with:

```swift
let env = t < Voices.attack ? t / Voices.attack : 1
var v = 0.0
for (mult, amp) in parts {
    // Higher partials die first, which is what a struck thing does.
    // 0.65 is the exponent to audition: 0 = old behaviour (all equal),
    // 1 = very dark very fast.
    let d = decay / pow(max(mult, 0.5), 0.65)
    v += amp * sin(twoPi * f0 * mult * bend * t) * exp(-4.0 * t / d)
}
v *= env
```

At `mult = 4` and exponent 0.65 the fourth partial's decay is about 40% of the fundamental's, so a note is bright at the strike and warm by 100ms. This one change does more for "not tinny" than everything else here combined. Audition the exponent between 0.4 and 0.9.

The sub-partial at `mult = 0.5` gets a *longer* decay from the same formula, which is correct — low energy is what lingers in a real object.

## Change 3 — put a transient on the front of every voice

A short noise burst, steeply enveloped, independent of the voice's sustained `grit`. Add to `Voices`:

```swift
/// The click of contact: how loud, and how fast it is gone.
public static func strike(_ voice: Int) -> Double {
    switch voice {
    case organ: return 0.10   // air, not impact
    case keys:  return 0.30   // hammer felt
    case drum:  return 0.45   // stick on head
    case bell:  return 0.35   // metal on metal
    default:    return 0.28
    }
}
public static let strikeTime = 0.006
```

and inside the sample loop, after the partial sum:

```swift
let st = Voices.strike(note.voice)
if st > 0 && t < Voices.strikeTime * 6 {
    seed = Toy.nextRand(seed)
    v += st * Toy.randUnit(seed) * exp(-t / Voices.strikeTime) * (0.5 + 0.5 * note.gain)
}
```

Scaling by `note.gain` matters: a hard hit should be *sharper*, not merely louder. That relationship is most of what "physical" means, and the current code has none of it.

**Also give every voice a small pitch drop, not just the drum.** Any struck object's frequency falls slightly as the initial deformation relaxes. Generalise `drumDrop`:

```swift
public static func drop(_ voice: Int) -> Double {
    voice == drum ? 0.55 : 0.06   // subtle everywhere else
}
public static func dropTime(_ voice: Int) -> Double {
    voice == drum ? 0.035 : 0.012
}
```

6% over 12ms is inaudible as pitch and very audible as weight.

## Change 4 — filter the noise

One-pole lowpass, two lines of state, turns hiss into thud:

```swift
// before the loop
var lp = 0.0
let lpA = 1 - exp(-2.0 * Double.pi * 1400.0 / Double(rate))   // ~1.4kHz
// where noise is generated
seed = Toy.nextRand(seed)
lp += lpA * (Toy.randUnit(seed) - lp)
v += grit * lp * 1.8   // the filter costs amplitude; put it back
```

Use the filtered noise for the sustained `grit`. Leave the Change-3 strike burst **unfiltered** — a contact click should be wideband. That contrast (bright click, dark body) is most of the realism.

Glass is the one voice to check carefully here, since it is mostly noise by design and 1.4kHz may dull it. If so, give glass its own cutoff around 3.5kHz.

## Change 5 — level and soft clipping

```swift
public static let headroom = 0.38   // was 0.28
```

and replace both hard clamps with a soft knee. In `Synth.render`:

```swift
out[i] = Float(tanh(v * env * gain * 1.15))
```

and in `Speaker.swift`'s render block:

```swift
for i in 0..<n { out[i] = Float(tanh(Double(out[i]))) }   // was min(max(...))
```

`tanh` is transparent below about 0.6 and compresses gracefully above it, so a five-note chord thickens instead of crackling. Re-check level after this; the sum of Changes 1–4 is louder than what it replaces and the headroom bump may prove unnecessary.

---

## The two new voices

Both are impact-first, which is what the current set lacks — every existing voice except drum is a *tone* with an onset.

**`wood`** — a struck block. Nearly all transient, minimal ring, slightly inharmonic. This will likely become the default for bumpers.

```swift
case wood: return [(1, 0.55), (2.4, 0.24), (4.1, 0.10)]   // partials
case wood: return 0.13                                     // decay
// grit 0.06, strike 0.55, drop 0.10 over 0.010s
```

**`mallet`** — soft head on a tuned bar; marimba-adjacent. Strong sub, fast high rolloff, real body. This is the "mass" voice.

```swift
case mallet: return [(0.5, 0.34), (1, 0.62), (3.9, 0.12), (9.2, 0.04)]
case mallet: return 0.85
// grit 0.03, strike 0.22, drop 0.05 over 0.014s
```

The 3.9 and 9.2 are the roughly-inharmonic bar modes that make a marimba sound like wood rather than a sine.

Add both to `Voices` as cases 6 and 7 and append to `voiceNames`:

```swift
public static let voiceNames = ["off", "organ", "keys", "drum", "bell", "pluck", "wood", "mallet"]
```

**UI consequence:** the drawer's SOUND row goes from 6 chips to 8. Per the visual handoff, chips must not shrink below 38pt — let the row wrap to two lines or scroll horizontally. Do not compress them to fit.

Anything persisting the voice as an index is unaffected, since the new cases are appended.

---

## The pitch tradeoff you asked to see

Today every hit snaps to a minor pentatonic degree (`scale = [0,3,5,7,10]`), so any two hits are consonant. The code's own comment is right about why: without it, a toy picking pitches from wherever the ball landed sounds like a wrong number.

But quantised pitch is also part of the "no impact" complaint. A real object's pitch is set by its size and doesn't change when it moves across a table. Snapping pitch to position tells the ear "synthesiser," and no amount of envelope work overrides that.

**Recommendation: split it by voice, rather than choosing globally.**

- **Tonal voices** — organ, keys, bell, pluck, mallet — keep the pentatonic exactly as it is. These are instruments; consonance is the point, and `mallet` will play chords nicely.
- **Impact voices** — drum, wood — leave the scale. Derive pitch from the *object*: the ball's size index, the bumper's size. Then add a few percent of continuous variation from impact speed so no two hits are identical, without quantising.

```swift
// impact voices: pitch is a property of the thing, not of where it is
let f0 = Voices.isTonal(note.voice)
    ? Voices.hz(note.step)
    : Voices.objectHz(note.step) * (0.97 + 0.06 * note.gain)
```

where `objectHz` maps the size index across roughly 70–260Hz — big things low, small things high, the mapping the ear already expects. `step` keeps its type, so `Note` doesn't change; only its meaning does, per voice. Document that.

The cost is that drum and wood hits no longer harmonise with the tonal voices. In practice that reads as a rhythm section under a melody, which is the correct relationship anyway.

If you would rather not split: keep quantisation everywhere and settle for Changes 1–5, which address thinness and impact on their own. Pitch is the smaller half of the problem.

---

## Order to work in, and how to judge

1. **Change 2** (per-partial decay) alone. Listen. This should be the moment "tinny" goes away.
2. **Change 1** (bottom octave + subs). Listen. This should be the moment "no impact" goes away.
3. **Change 3** (strike transient + general pitch drop). Listen for whether hard hits now feel *sharper* rather than just louder.
4. **Change 4** (noise filter), checking drum and glass specifically.
5. **Change 5** (level/soft clip), then re-level everything once against the originals.
6. Add `wood` and `mallet`.
7. Decide the pitch split last, with everything else settled.

Judge with the phone's own speaker first, not headphones. A tiny speaker reproduces almost nothing below 200Hz, so Change 1 will be less dramatic there than on desktop — but it is where the toy is actually used, and the reason to check is that overdoing the sub costs nothing on speakers and muds badly everywhere else.

## Tests and parity

`ios/Tests/NonsenseCoreTests/` and `tools/parity.py` compare rendered samples across platforms. Every change here alters the waveform, so golden values must be regenerated once the tuning is settled — **not** per iteration. `.github/workflows/parity.yml` will fail until they are.

Two invariants worth keeping under test: no single note exceeds 1.0 after `tanh`, and voice `off` still renders zero samples.

## Files
| File | What changes |
| --- | --- |
| `ios/Sources/NonsenseCore/Toy.swift` | `Voices` constants and tables; `Synth.render` envelope, transient, noise filter |
| `ios/App/Speaker.swift` | mixer hard clamp → `tanh` (2 lines) |
| `web/index.src.html` | shares the synth; verify it picks up the same constants |
| `ios/Tests/NonsenseCoreTests/`, `tools/parity.py` | regenerate goldens once, at the end |
| `ios/App/ToyView.swift`, `web/index.src.html` | SOUND row: 6 chips → 8 |

`Haptics.swift` is untouched. Worth noting for later: haptics and audio currently fire independently, and aligning the haptic to the audio transient is a further large gain in perceived impact — out of scope here.
