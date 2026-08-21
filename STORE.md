# Selling Nonsense

What is built, what you have to do yourself, and in what order. Google Play
first, because the Android app already exists and builds; the App Store needs
things this repository cannot produce.

## What is already done

- **The gate.** `Toy.tier` is `FREE` or `FULL`. Everything paid is behind
  `modeLocked` / `editLocked` / `familyLocked` / `canvasLocked`, and every
  locked control opens the paywall instead of doing nothing. Eleven tests
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

## About the App Store

Two things stand between this and an iOS release, and neither is code I can
write here:

1. **There is no iOS app.** `Toy.kt` ports cleanly — it has no `android.*` in
   it, which was the point — but the view layer is a rewrite in Swift, and
   building or submitting it needs a Mac with Xcode plus the $99/year Apple
   Developer Program. I cannot compile or test Swift in this environment, so
   anything I wrote for it would reach you untested.

2. **Guideline 4.2, minimum functionality, is a real risk.** Apple rejects apps
   that read as too slight to justify being native, and a fidget toy in a
   crowded category is squarely in that line of fire. Putting a paywall on it
   makes that scrutiny worse, not better — a reviewer who has to pay to see
   most of the app is a reviewer looking for a reason. If you go for it: ship
   the free tier as a genuinely complete toy (which is how it is built),
   make the haptics and the translucent overlay prominent in the listing since
   those are the native-only parts, and expect at least one rejection round.

Play is materially easier on both counts: no Mac, $25 instead of $99/year, and
review that is largely automated.

## If you would rather skip the stores

`web/index.html` runs everywhere including an iPhone, takes no cut beyond
Stripe's, and needs no review. The catch is that a licence check in a public
repo is bypassable by anyone who opens the console, so it would need a small
server to hold the entitlement — and it gives up store discovery entirely.
Worth it only if you already have an audience to sell to.
