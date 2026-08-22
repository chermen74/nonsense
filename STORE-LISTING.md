# The listing, written out

Copy and paste. Both stores ask for the same handful of things under different
names and different character limits; everything here is inside them.

Anything marked **[fill]** is something only you can supply.

## Google Play

### Store listing

| Field | Limit | Text |
|---|---|---|
| App name | 30 | `Nonsense` |
| Short description | 80 | `A quiet fidget toy. Throw a ball, spin a dial, strike lightning that stays.` |

**Full description** (4000 characters; this is ~1100):

```
Something to do with your hands.

Nonsense is a fidget toy and nothing else. No scores, no sounds, no streaks, no
notifications, no account. Open it, mess about, close it.

THE BALL — free, and complete
Touch anywhere and the ball comes to your finger. Flick it and it coasts,
bounces off the edges and slows. Eight sizes from a bead to a grapefruit, and
six shapes — a bar cartwheels off a wall, a square clatters. Turn on catching
and it stops coming to you: you have to land on it.

LIGHTNING
Flick and a bolt leaves your finger, spreading into forks as it goes. It knocks
when it reaches a wall, and stays there — etched onto the scene in whatever ink
you threw it with. Flick again and the scene builds up. Wipe it whenever you
like.

THE DIAL
A knurled wheel with eighteen ribs and a red index they click past. Spin it and
let go; a hard flick runs down over about twenty seconds.

BUMPERS
The ball plus a table of outline bumpers. Add them, delete them, move them,
reshape them, colour them — and let the ball ink a trail while it plays.

PAINT
The ball leaves ink wherever it goes. Fifty-six colours in fourteen families,
five levels of translucency, seven grounds to draw on.

IT FEELS LIKE SOMETHING
Every wall, every bumper and every rib of the dial is a real haptic knock,
weighted by how hard it hit, using the waveforms your phone uses for its own
clicks. Off, soft or firm.

QUIET BY DESIGN
No account. No ads. No analytics. No trackers. Nothing is collected, because
there is nowhere for it to go — the app makes no network requests of its own.
Everything you make stays on your phone.

The ball is free forever. The dial, the bumper table, lightning and paint are
$1.99 a month.
```

### Subscription

| Field | Value |
|---|---|
| Subscription ID | `nonsense_monthly` |
| Name | `Nonsense, everything` |
| Base plan ID | `monthly`, auto-renewing, 1 month |
| Price | $1.99 |
| Benefits (up to 4) | `The dial, the bumper table and lightning` · `Paint — the ball leaves ink` · `Arrange and colour the bumper table` · `All 56 inks and all 7 grounds` |

### Data safety form

Answer it exactly like this. It is all true, and it is a selling point:

- Does your app collect or share any of the required user data types? **No**
- Is all of the user data collected by your app encrypted in transit? *(not
  asked once you answer No)*
- Do you provide a way for users to request that their data is deleted? **No
  data is collected**

### Content rating

Answer the questionnaire truthfully; it comes out at the lowest rating in every
region. No violence, no language, no user interaction, no sharing of location
or personal information.

### Other required fields

| Field | Value |
|---|---|
| App category | Games → Casual, or Apps → Lifestyle. Casual is the better fit |
| Privacy policy URL | `https://<your-github-username>.github.io/fidget/privacy.html` **[fill]** |
| Contact email | **[fill]** |
| Feature graphic | 1024×500 — **[fill]**, see the note below |
| Phone screenshots | at least 2; use the ones in `store/` |

## Apple App Store

| Field | Limit | Text |
|---|---|---|
| App name | 30 | `Nonsense` |
| Subtitle | 30 | `Something to do with your hands` |
| Promotional text | 170 | `Lightning that spreads, strikes the wall, and stays etched where it landed — in whatever colour you threw it.` |

**Description** — the Play full description above works unchanged.

**Keywords** (100 characters, comma separated, no spaces):

```
fidget,toy,calm,stress,relax,doodle,lightning,ball,haptic,idle,quiet,tactile,offline,drawing
```

### Subscription

| Field | Value |
|---|---|
| Subscription group | `Nonsense` |
| Reference name | `Nonsense everything monthly` |
| Product ID | `com.nonsense.monthly` |
| Duration | 1 month |
| Price | $1.99 (tier as shown) |
| Display name | `Nonsense, everything` |
| Description | `The dial, the bumper table, lightning and paint, plus every ink and every ground.` |

### App Review

- **Sign-in required?** No.
- **Notes for the reviewer** — paste this, it is the thing most likely to save
  a rejection round:

```
Nonsense is a fidget toy. There is no account and no sign-in.

The ball is free and complete — every size, every shape, and catching — and
does not expire. The dial, bumper table, lightning and paint are a $1.99/month
subscription.

To review the paid features without a purchase: on the paywall, tap
"have a code?" and enter 1836. This unlocks everything.

The app makes no network requests of its own. It collects nothing.
```

- **Privacy policy URL**: **[fill]** — same URL as Play.
- **Terms of use (EULA)**: Apple's standard EULA is the default and is fine;
  if you would rather use ours, it is at `docs/terms.html`.
- **App Privacy** ("Data Not Collected"): select **Data Not Collected**. That
  is the whole form.

### Screenshots

Apple requires the 6.7" size; everything else is derived from it. The files in
`store/` are already 1290×2796.

## The feature graphic

Play wants a 1024×500 banner. There isn't one in the repository — it is the one
asset that wants a designer's eye rather than a screenshot. The cheapest good
version: the wordmark NONSENSE on the slate ground, with two or three etched
lightning strikes running behind it in oxblood and ochre. A screenshot of the
lightning screen cropped to 1024×500 with the wordmark placed over it would do.

## What is genuinely blocking

1. A Google Play developer account — $25, and identity verification takes days.
2. An upload key — `STORE.md` §3. **Never commit it.**
3. A privacy policy URL — the page exists at `docs/privacy.html`; turn on
   GitHub Pages and it has a URL.
4. A contact email in both policy pages, where they say `you@example.com`.
5. For iOS only: a Mac with Xcode, and $99/year.
