#!/usr/bin/env python3
"""Check that the Kotlin and Swift simulations still agree.

The iOS port is a hand transliteration of Toy.kt, and the realistic failure
mode of a hand port is not a compile error — it is a constant typed wrongly.
0.55 for 0.5 in the friction, a colour off by a digit, a missing entry in a
list. The Swift tests catch the ones that change behaviour, but only when
somebody runs them on a Mac, and only for the values that behaviour depends
on visibly.

This runs anywhere, including in CI on Linux, and compares the two files
literal by literal. It knows nothing about either language beyond how to find
a named literal and pull the numbers or strings out of it.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "android/app/src/main/java/com/nonsense/Toy.kt"
SW = ROOT / "ios/Sources/NonsenseCore/Toy.swift"

# (label, kotlin anchor, swift anchor, kind)
#   kind "n" compares every number found in the literal, in order
#   kind "s" compares every double-quoted string, in order
CHECKS = [
    ("ball sizes",        r"val SIZES\s*=",              r"static let sizes:\s*\[Double\]\s*=",   "n"),
    ("default size",      r"const val DEFAULT_SIZE\s*=", r"static let defaultSize\s*=",           "n"),
    ("kick",              r"const val KICK\s*=",         r"static let kick\s*=",                  "n"),
    ("max speed",         r"const val MAX_SPEED\s*=",    r"static let maxSpeed\s*=",              "n"),
    ("min bumper",        r"const val MIN_BUMPER\s*=",   r"static let minBumper\s*=",             "n"),
    ("max bumper",        r"const val MAX_BUMPER\s*=",   r"static let maxBumper\s*=",             "n"),
    ("free families",     r"const val FREE_FAMILIES\s*=", r"static let freeFamilies\s*=",         "n"),
    ("free canvases",     r"const val FREE_CANVASES\s*=", r"static let freeCanvases\s*=",         "n"),
    ("default canvas",    r"const val DEFAULT_CANVAS\s*=", r"static let defaultCanvas\s*=",        "n"),
    ("max dial omega",    r"const val MAX_DIAL_OMEGA\s*=", r"static let maxDialOmega\s*=",        "n"),
    ("dial ribs",         r"const val DIAL_RIBS\s*=",    r"static let dialRibs\s*=",              "n"),
    ("dial window",       r"const val DIAL_WINDOW\s*=",  r"static let dialWindow\s*=",            "n"),
    ("bumper alpha",      r"const val BUMPER_ALPHA\s*=", r"static let bumperAlpha\s*=",           "n"),
    ("bolt min speed",    r"const val BOLT_MIN_SPEED\s*=", r"static let boltMinSpeed\s*=",         "n"),
    ("bolt speed",        r"const val BOLT_SPEED\s*=",   r"static let boltSpeed\s*=",              "n"),
    ("bolt max speed",    r"const val BOLT_MAX_SPEED\s*=", r"static let boltMaxSpeed\s*=",         "n"),
    ("bolt life",         r"const val BOLT_LIFE\s*=",    r"static let boltLife\s*=",               "n"),
    ("bolt glow",         r"const val BOLT_GLOW\s*=",    r"static let boltGlow\s*=",              "n"),
    ("max bolts",         r"const val MAX_BOLTS\s*=",    r"static let maxBolts\s*=",               "n"),
    ("bolt branch",       r"const val BOLT_BRANCH\s*=",  r"static let boltBranch\s*=",             "n"),
    ("bolt branch spread", r"const val BOLT_BRANCH_SPREAD\s*=", r"static let boltBranchSpread\s*=", "n"),
    ("bolt branch speed", r"const val BOLT_BRANCH_SPEED\s*=", r"static let boltBranchSpeed\s*=",   "n"),
    ("bolt max gen",      r"const val BOLT_MAX_GEN\s*=", r"static let boltMaxGen\s*=",             "n"),
    ("bolt arc",          r"const val BOLT_ARC\s*=",     r"static let boltArc\s*=",                "n"),
    ("bolt arms min",     r"const val BOLT_ARMS_MIN\s*=", r"static let boltArmsMin\s*=",           "n"),
    ("bolt arms max",     r"const val BOLT_ARMS_MAX\s*=", r"static let boltArmsMax\s*=",           "n"),
    ("bolt arms full",    r"const val BOLT_ARMS_FULL\s*=", r"static let boltArmsFull\s*=",         "n"),
    ("bolt lean",         r"const val BOLT_LEAN\s*=",    r"static let boltLean\s*=",               "n"),
    ("bolt lean reach",   r"const val BOLT_LEAN_REACH\s*=", r"static let boltLeanReach\s*=",       "n"),
    ("max etched",        r"const val MAX_ETCHED\s*=",   r"static let maxEtched\s*=",              "n"),
    ("glass radials min", r"const val GLASS_RADIALS_MIN\s*=", r"static let glassRadialsMin\s*=",   "n"),
    ("glass radials max", r"const val GLASS_RADIALS_MAX\s*=", r"static let glassRadialsMax\s*=",   "n"),
    ("glass rings min",   r"const val GLASS_RINGS_MIN\s*=", r"static let glassRingsMin\s*=",       "n"),
    ("glass rings max",   r"const val GLASS_RINGS_MAX\s*=", r"static let glassRingsMax\s*=",       "n"),
    ("glass step",        r"const val GLASS_STEP\s*=",   r"static let glassStep\s*=",              "n"),
    ("glass jag",         r"const val GLASS_JAG\s*=",    r"static let glassJag\s*=",               "n"),
    ("glass ring first",  r"const val GLASS_RING_FIRST\s*=", r"static let glassRingFirst\s*=",     "n"),
    ("glass ring step",   r"const val GLASS_RING_STEP\s*=", r"static let glassRingStep\s*=",       "n"),
    ("glass ring points", r"const val GLASS_RING_POINTS\s*=", r"static let glassRingPoints\s*=",   "n"),
    ("max breaks",        r"const val MAX_BREAKS\s*=",   r"static let maxBreaks\s*=",              "n"),
    ("follow ink",        r"const val FOLLOW_INK\s*=",   r"static let followInk\s*=",              "n"),
    # Sound. The scale is the one that keeps a toy picking pitches at random
    # from sounding like a wrong number, so a port that mistypes it is a port
    # that sounds wrong rather than one that crashes.
    ("scale",             r"val SCALE\s*=",             r"static let scale\s*=",                  "n"),
    ("octaves",           r"const val OCTAVES\s*=",      r"static let octaves\s*=",                "n"),
    ("root hz",           r"const val ROOT_HZ\s*=",      r"static let rootHz\s*=",                 "n"),
    ("note attack",       r"const val ATTACK\s*=",       r"static let attack\s*=",                 "n"),
    ("note headroom",     r"const val HEADROOM\s*=",     r"static let headroom\s*=",               "n"),
    ("drum drop",         r"const val DRUM_DROP\s*=",    r"static let drumDrop\s*=",               "n"),
    ("drum drop time",    r"const val DRUM_DROP_TIME\s*=", r"static let drumDropTime\s*=",          "n"),
    ("voice names",       r"val VOICE_NAMES\s*=",       r"static let voiceNames\s*=",             "s"),
    ("curve bite",        r"const val CURVE_BITE\s*=",   r"static let curveBite\s*=",              "n"),
    ("bolt reach",        r"const val BOLT_REACH\s*=",   r"static let boltReach\s*=",              "n"),
    ("bolt reach fall",   r"const val BOLT_REACH_FALL\s*=", r"static let boltReachFall\s*=",         "n"),
    ("bolt branch fall",  r"const val BOLT_BRANCH_FALL\s*=", r"static let boltBranchFall\s*=",        "n"),
    ("bumps max",         r"const val BUMPS_MAX\s*=",    r"static let bumpsMax\s*=",               "n"),
    ("bump gap",          r"const val BUMP_GAP_MS\s*=",  r"static let bumpGapMs\s*=",              "n"),
    ("bump falloff",      r"const val BUMP_FALLOFF\s*=", r"static let bumpFalloff\s*=",            "n"),
    ("min stretch",       r"const val MIN_STRETCH\s*=",  r"static let minStretch\s*=",             "n"),
    ("max stretch",       r"const val MAX_STRETCH\s*=",  r"static let maxStretch\s*=",             "n"),
    ("letter grid",       r"const val GRID_W\s*=",       r"static let gridW\s*=",                  "n"),
    ("letter rows",       r"const val GRID_H\s*=",       r"static let gridH\s*=",                  "n"),
    ("alphabet",          r"const val ALPHABET\s*=",     r"static let alphabet\s*=",               "s"),
    ("digits",            r"const val DIGITS\s*=",       r"static let digits\s*=",                 "s"),
    # The font is one string on purpose: twenty-six letters of bitmap compare
    # in a single line, and a letter drawn wrongly on one platform shows up
    # here rather than in a screenshot nobody took.
    ("letter font",       r"const val FONT\s*=",         r"static let font\s*=",                   "s"),
    ("etch alpha",        r"const val ETCH_ALPHA\s*=",   r"static let etchAlpha\s*=",              "n"),
    ("bolt core hot",     r"const val BOLT_CORE_HOT\s*=", r"static let boltCoreHot\s*=",           "n"),
    ("bolt core cool",    r"const val BOLT_CORE_COOL\s*=", r"static let boltCoreCool\s*=",         "n"),
    ("bolt node",         r"const val BOLT_NODE\s*=",    r"static let boltNode\s*=",               "n"),
    ("bolt jag",          r"const val BOLT_JAG\s*=",     r"static let boltJag\s*=",                "n"),
    ("bolt max nodes",    r"const val BOLT_MAX_NODES\s*=", r"static let boltMaxNodes\s*=",         "n"),
    ("friction",          r"val friction\s*=",           r"let friction\s*=",                     "n"),
    ("restitution",       r"val restitution\s*=",        r"let restitution\s*=",                  "n"),
    ("spin friction",     r"val spinFriction\s*=",       r"let spinFriction\s*=",                 "n"),
    ("dial friction",     r"val dialFriction\s*=",       r"let dialFriction\s*=",                 "n"),
    ("palette bases",     r"private val BASES\s*=",      r"private static let bases:\s*\[UInt32\]\s*=", "n"),
    ("tone mix",          r"val TONE_MIX\s*=",           r"static let toneMix:\s*\[Double\]\s*=",  "n"),
    ("alphas",            r"val ALPHAS\s*=",             r"static let alphas:\s*\[Double\]\s*=",   "n"),
    ("scrims",            r"val SCRIMS\s*=",             r"static let scrims:\s*\[Double\]\s*=",   "n"),
    ("canvas colours",    r"val CANVAS_COLORS\s*=",      r"static let canvasColors:\s*\[UInt32\]\s*=", "n"),
    ("haptic scales",     r"val HAPTIC_SCALES\s*=",      r"static let hapticScales:\s*\[Double\]\s*=", "n"),
    ("shape cover",       r"val COVER:",                 r"static let cover: \[Shape: Double\]",      "n"),
    ("default table",     r"fun defaultTable\(\)",       r"static func defaultTable\(\) -> \[Bumper\]", "n"),
    ("palette names",     r"val NAMES\s*=",              r"static let names\s*=",                  "s"),
    ("canvas names",      r"val CANVAS_NAMES\s*=",       r"static let canvasNames\s*=",            "s"),
    ("haptic names",      r"val HAPTIC_NAMES\s*=",       r"static let hapticNames\s*=",            "s"),
    ("drawer rows",       r"val drawerRows\s*=",         r"let drawerRows\s*=",                    "s"),
    ("toolbar labels",    r"val toolbarLabels\s*=",      r"let toolbarLabels\s*=",                 "s"),
    ("paywall labels",    r"val PAYWALL_LABELS\s*=",    r"let paywallLabelsBase\s*=",             "s"),
    ("code prompt",       r"const val CODE_PROMPT\s*=",  r"static let codePrompt\s*=",             "s"),
    ("price fallback",    r"const val PRICE_FALLBACK\s*=", r"static let priceFallback\s*=",         "s"),
    ("code hash",         r"const val CODE_HASH\s*=",     r"static let codeHashValue: Int32\s*=",   "n"),
    ("code length",       r"const val CODE_LENGTH\s*=",   r"static let codeLength\s*=",             "n"),
    ("paywall lines",     r"fun paywallLines\(\)",       r"func paywallLines\(\) -> \[String\]",      "s"),
    ("mode row labels",   r"fun modeLabels\(\)",         r"func modeLabels\(\) -> \[String\]",        "s"),
    ("menu items",        r"fun menuItems\(\)",          r"func menuItems\(\) -> \[MenuItem\]",       "s"),
]

NUM = re.compile(r"-?\d+\.?\d*(?:[eE]-?\d+)?|0x[0-9a-fA-F]+")
STR = re.compile(r'"([^"\\]*)"')


def literal(text, anchor, label, lang):
    """The text of the named literal: from the anchor to its balanced close.

    A value may sit on the line after the `=` and may be continued across
    several lines with `+`. Both were read as empty by an earlier version of
    this, which is worse than useless: the check still counted as agreeing,
    so the font — the one literal here big enough to hide a typo — was
    compared against nothing at all for as long as it was written that way.
    """
    m = re.search(anchor, text)
    if not m:
        raise SystemExit(f"parity: could not find {label} in the {lang} source")
    rest = text[m.end():]
    opens = "([{"
    closes = ")]}"

    # Where the value actually starts, blank lines after the "=" skipped.
    lead = 0
    while lead < len(rest) and rest[lead] in " \t\n":
        lead += 1
    head_end = rest.find("\n", lead)
    if head_end < 0:
        head_end = len(rest)
    head = rest[lead:head_end]

    at = [head.find(c) for c in opens if head.find(c) >= 0]
    if at:
        first = lead + min(at)
        depth = 0
        for i in range(first, len(rest)):
            ch = rest[i]
            if ch in opens:
                depth += 1
            elif ch in closes:
                depth -= 1
                if depth == 0:
                    return rest[first:i + 1]
        raise SystemExit(f"parity: unbalanced literal for {label} in the {lang} source")

    # A scalar or a string, possibly continued onto further lines. Kotlin
    # trails the "+" and Swift leads with it, so both ends are checked.
    lines = rest[lead:].split("\n")
    out = []
    for i, line in enumerate(lines):
        out.append(line)
        core = re.sub(r"//.*", "", "".join(out)).strip()
        if not core:
            continue
        if core.endswith("+"):
            continue
        nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
        if nxt.startswith("+"):
            continue
        break
    return "\n".join(out)


def numbers(chunk):
    out = []
    # Comments carry numbers that are prose, not values.
    chunk = re.sub(r"//[^\n]*", "", chunk)
    for tok in NUM.findall(chunk):
        if tok.startswith("0x"):
            out.append(float(int(tok, 16) & 0xffffffff))
        else:
            out.append(float(tok))
    return out


def strings(chunk):
    chunk = re.sub(r"//[^\n]*", "", chunk)
    return STR.findall(chunk)


def main():
    kt = KT.read_text()
    sw = SW.read_text()
    bad = []
    for label, ka, sa, kind in CHECKS:
        k = literal(kt, ka, label, "Kotlin")
        s = literal(sw, sa, label, "Swift")
        if kind == "n":
            a, b = numbers(k), numbers(s)
        else:
            a, b = strings(k), strings(s)
        # Two empty lists agree about nothing. A check that cannot see its
        # own literal is a check that will never fail, which is the failure.
        if not a and not b:
            bad.append(f"{label}: nothing to compare — the literal was not read")
            continue
        if a != b:
            bad.append((label, a, b))
    for label, a, b in bad:
        print(f"DRIFT  {label}\n  kotlin: {a}\n  swift:  {b}")
    print(f"{len(CHECKS) - len(bad)}/{len(CHECKS)} literals agree")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
