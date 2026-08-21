# Selling Nonsense

What is built, what you have to do yourself, and in what order. Google Play
first, because the Android app already exists and builds; the App Store needs
things this repository cannot produce.

## What is already done

- **The gate.** `Toy.tier` is `FREE` or `FULL`. The ball is the free toy; the
  dial, the bumper table, lightning and paint are the unlock, along with
  editing, thirty-three of the inks and five of the canvases. Everything paid
  is behind `modeLocked` / `editLocked` / `familyLocked` / `canvasLocked`, and
  every locked control opens the paywall instead of doing nothing — every way
  into a toy runs through one gate, so no door can forget it. Twelve tests
  cover it, including that a refund takes back what it paid for.
- **The paywall screen** — what the unlock buys, in words, with the price on
  the button once Play has said what it is.
- **`Billing.kt`** — Play Billing 9, one-time non-consumable, acknowledged and
  never consumed. Wired in `MainActivity`.
- **Release signing** that reads the key from the environment, and a manual
  `Play release bundle` workflow that builds a signed `.aab`.

## What only you can do

### 1. Google Play Console — $25, once

<https://play.google.com/console> → create a developer account. It is a
one-time fee for the life of the account. Google verifies your identity;
budget a few days. Individual accounts created since 2023 also need **12
testers opted in for 14 continuous days** before you can go to production —
this is the single slowest thing in the whole process, so start it early.

### 2. Create the app and the product

Create the app (`com.nonsense`), then **Monetise → In-app products → Create**:

| Field | Value |
|---|---|
| Product ID | `nonsense_full` — **must match `Billing.PRODUCT_ID` exactly** |
| Type | One-time product, non-consumable |
| Price | Your call. £2–4 is the usual band for a paid fidget toy |

A mismatched product ID fails silently: `queryProductDetailsAsync` returns
nothing and the unlock button just sits there. If you change the ID, change
`Billing.kt` with it.

### 3. Make the upload key — and never put it in this repo

The upload key **is** the app's identity on Play forever. A committed one
cannot be taken back.

```bash
keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias nonsense
base64 -w0 upload.jks            # macOS: base64 -i upload.jks
```

Keep `upload.jks` somewhere you will still have it in five years. Then in
**GitHub → Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | the base64 blob above |
| `UPLOAD_KEYSTORE_PASSWORD` | the store password |
| `UPLOAD_KEY_ALIAS` | `nonsense` |
| `UPLOAD_KEY_PASSWORD` | the key password |

Turn on **Play App Signing** when you first upload; Google then holds the real
signing key and your upload key can be reset if you lose it. Without it,
losing the key means you can never update the app again.

### 4. Build the bundle

Actions → **Play release bundle** → Run workflow. Give it a version name and a
version code higher than every previous upload. Download the `.aab` artifact
and upload it to a Play track.

### 5. Test the purchase before anyone can buy it

Billing cannot be tested from a debug build side-loaded off GitHub — Play only
recognises purchases for an app it has a record of.

1. Upload the bundle to **Internal testing** and add your own account as a
   tester.
2. **Setup → Licence testing**: add the same account. Licence testers get real
   billing flows with test cards — no money moves, and refunds are instant.
3. Install from the internal testing link, not the sideloaded APK.
4. Check all four: buy it, kill and relaunch (still unlocked), refund it in the
   Play Console (locks again on the next `onResume`), and press **restore** on
   a fresh install.

`Billing.kt` compiles against the library, but a purchase flow cannot be
exercised without a Play account, so this step is the first time it is really
run. The one thing to watch: **unlock** should open Play's sheet. If it does
nothing, the product ID does not match; if it opens and errors, the likely
cause is `launchBillingFlow` wanting an offer token, and the comment on
`buy()` says where that goes.

### 6. The store listing

Play needs, and this app makes easy:

- **Data safety form** — Nonsense collects nothing, transmits nothing, and has
  no analytics or ads. Declare exactly that. It is worth keeping true; it is a
  genuine selling point for a toy that sits over your home screen.
- **Privacy policy URL** — required even when you collect nothing. A one-page
  "this app collects no data" served from GitHub Pages is enough.
- **Content rating questionnaire** — a fidget toy rates as low as it goes.
- **Screenshots** — at least two phone screenshots. The title screen, the
  coloured bumper table and the dial are the three that show what it is.
- **Feature graphic**, 1024×500.
- A short and a full description.

## The money

Google takes **15%** of the first $1M you earn in a year and 30% above it, via
the Play Console's service fee tiers. Apple's [Small Business
Program](https://developer.apple.com/app-store/small-business-program/) is the
same shape: 15% if you made under $1M in the prior calendar year, 30% above.
Both are applied to your proceeds, and you register for the reduced rate rather
than getting it automatically.

## The App Store

The app exists now — `ios/`, and `ios/README.md` is the guide to it. What is
verified, what is not, and why the split falls where it does is all in there;
the short version is that the simulation is checked twice over and the SwiftUI
layer has never been compiled.

### What only you can do

1. **A Mac with Xcode.** Not optional, and not something this repository can
   provide. `cd ios && swift test` is the first command to run and needs no
   Xcode project; `xcodegen && open Nonsense.xcodeproj` is the second.
2. **Apple Developer Program — $99/year.** Renews, unlike Play's one-off $25.
3. **App Store Connect: create the app and the product.** The product ID must
   match `Store.productID` exactly — `com.nonsense.full` — for the same reason
   it must on Play: a mismatch is silent, and the unlock button just sits
   there.
4. **Test the purchase with a StoreKit configuration file first**, then a
   sandbox account, then TestFlight. Xcode's local StoreKit testing needs no
   App Store Connect entry at all and is the fastest way to find out whether
   `Store.swift` works.

### Guideline 4.2 is a live risk

Apple rejects apps that read as too slight to justify being native, and a
fidget toy in a crowded category is squarely in that line of fire. A paywall
makes that scrutiny worse rather than better — a reviewer who has to pay to see
most of an app is a reviewer looking for a reason.

What helps, in order:

- **The free tier is one toy out of five.** This is the sharp edge of the
  plan. A reviewer who can reach the ball and nothing else is exactly the
  "demo, not an app" that 4.2 exists to reject, and no listing copy argues
  them out of it. If a rejection comes back citing 4.2, the cheapest answer is
  to move one more toy across the line — the dial is the obvious one, being
  the least like the ball — rather than to appeal.
- The free ball is at least a *whole* toy: every size, every shape, catching,
  ink and canvases to play on. That it is complete rather than time-limited or
  crippled is worth saying in the review notes.
- Lead the listing with what only a native app can do: the haptics, and the
  fact that it runs at the refresh rate with no network at all.
- Expect at least one rejection round and budget a week for it.

The one thing that does *not* transfer is the translucent overlay — iOS forbids
drawing over other apps, which is why the solid canvases exist. Do not put it
in the listing.

Play remains materially easier on every count: no Mac, $25 once instead of $99
a year, and review that is largely automated. Shipping there first also means
the iOS listing can point at real reviews.

## If you would rather skip the stores

`web/index.html` runs everywhere including an iPhone, takes no cut beyond
Stripe's, and needs no review. The catch is that a licence check in a public
repo is bypassable by anyone who opens the console, so it would need a small
server to hold the entitlement — and it gives up store discovery entirely.
Worth it only if you already have an audience to sell to.
