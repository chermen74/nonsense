import Foundation

/// The whole simulation, with no UIKit in it.
///
/// This is a port of `android/app/src/main/java/com/nonsense/Toy.kt`, kept
/// deliberately line-for-line rather than made idiomatic, so the two can be
/// read side by side and `tools/parity.py` can check that the constants have
/// not drifted apart. Everything here is state and arithmetic; the SwiftUI
/// layer owns input and pixels.
///
/// The one real difference from the Kotlin: `Double` rather than `Float`.
/// CoreGraphics is `Double` on every device that matters, and the physics is
/// nowhere near float-precision-sensitive, so the casting was not worth it.

// MARK: - Small types

public enum Mode: CaseIterable { case ball, dial, bumpers, bolt, glass, paint }

/// The app opens on its own name and a list of what it can do, rather than
/// dropping you into whichever toy you left running.
public enum Screen { case title, play, paywall }

/// Free forever, or bought once. Nothing here talks to a store — `Toy` only
/// ever reads `tier`, which is what lets the same gate serve StoreKit, Play,
/// or a licence file.
public enum Tier { case free, full }

public enum Shape: CaseIterable { case circle, triangle, square, pentagon, hexagon, bar }

public struct Pt: Equatable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
}

/// A push that frees one shape from another: unit normal plus overlap.
public struct Hit {
    public let nx: Double
    public let ny: Double
    public let depth: Double
}

/// A bumper, stored normalised — nx/ny are fractions of the field and size is
/// a fraction of its short edge — so a table built on a phone still looks
/// right on an iPad, and survives a rotation.
public struct Bumper: Equatable {
    public var nx: Double
    public var ny: Double
    public var size: Double
    public var shape: Shape
    public var rot: Double
    /// Its ink, from the same fifty-six as everything else — or
    /// `Toy.followInk`, which is the default, meaning it wears whatever ink the
    /// rest of the app is holding. The tone is its own either way, so a table
    /// that follows the ink is still four shades rather than one flat colour.
    public var family: Int
    public var tone: Int
    /// Two axes rather than one: a bumper that can only grow evenly cannot be
    /// a bar you pull long, and a letter that cannot be pulled is a sticker.
    public var sx: Double
    public var sy: Double
    /// One letter or digit, or empty for one of the six outlines.
    public var glyph: String

    public init(nx: Double, ny: Double, size: Double, shape: Shape, rot: Double,
                family: Int = Toy.followInk, tone: Int = 2,
                sx: Double = 1, sy: Double = 1, glyph: String = "") {
        self.nx = nx; self.ny = ny; self.size = size
        self.shape = shape; self.rot = rot
        self.family = family; self.tone = tone
        self.sx = sx; self.sy = sy; self.glyph = glyph
    }
}

/// A struck bolt: a head travelling in a straight line, and the zigzag it has
/// left behind it.
///
/// The zigzag is laid down as the head travels rather than generated at draw
/// time, for one reason — a bolt redrawn from fresh randomness every frame
/// shimmers like television static instead of hanging in the air. Each kink is
/// displaced once and never moves again.
public final class Bolt {
    public var x: Double
    public var y: Double
    public var vx: Double
    public var vy: Double
    public var rng: Int32
    public var nodes: [Pt]
    /// 1 while it is travelling, then it counts down the glow after the hit.
    public var life = 1.0
    /// Set the moment it reaches a wall: from here it is only cooling.
    public var struck = false
    public var sinceNode = 0.0
    /// How much further this one may travel before it gives out, in pixels.
    /// The bolt your finger threw has no limit — it runs until it hits
    /// something. A fork does: it peels off, goes a little way, and dies.
    /// Without this every fork ran to a wall too, and one flick left forty
    /// full-length streaks, which is a scribble rather than a strike.
    public var reach = Double.greatestFiniteMagnitude
    /// Which way the next kink throws. Carried on the bolt rather than read
    /// off the node count, which stops alternating once the rolling window is
    /// full: a constant side draws a smooth arc instead of a zigzag.
    public var side = 1.0
    /// The ink it was struck in. An etching keeps the colour it arrived in.
    public let argb: UInt32
    /// 0 is the bolt your finger threw; a fork is 1, its own fork is 2.
    public let gen: Int

    public init(x: Double, y: Double, vx: Double, vy: Double, rng: Int32,
                argb: UInt32 = 0, gen: Int = 0) {
        self.x = x; self.y = y; self.vx = vx; self.vy = vy; self.rng = rng
        self.argb = argb; self.gen = gen
        self.nodes = [Pt(x, y)]
    }
}

/// One fracture line. A crack is the same idea as a bolt's zigzag — nodes
/// laid once and never moved — because glass and lightning break the same way.
public struct Crack {
    public let nodes: [Pt]
    public let ring: Bool
    public let depth: Int
}

/// One press. The cracks it made, in the ink it was pressed in.
public struct Break {
    public let x: Double
    public let y: Double
    public let argb: UInt32
    public let cracks: [Crack]
}

/// A bolt that has arrived. The path stays on the scene until it is cleared.
/// It keeps its generation because the bolt your finger threw is drawn a shade
/// heavier than the forks that came off it.
public struct Etched {
    public let nodes: [Pt]
    public let argb: UInt32
    public let gen: Int
}

// MARK: - Letters

/// Block glyphs, five wide and seven tall, one bit per cell. Two hex digits
/// per row, seven rows per glyph, A to Z and then 0 to 9 in order — one
/// literal, so the three ports can be checked against each other by comparing
/// a single string.
public enum Letters {
    public static let gridW = 5
    public static let gridH = 7
    public static let alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    public static let digits = "0123456789"

    /// Everything a bumper can be cut into, in the order the font stores it.
    /// Letters first because they were here first, and a saved table naming
    /// one has to keep meaning it.
    public static let glyphs = alphabet + digits

    public static let font =
        "0e11111f1111111e11111e11111e0e11101010110e1e11111111111e1f10101e10101f1f10101e1010100e11101711110e1111111f1111111f04040404041f0702020202120c111214181412111010101010101f111b1515111111111915131111110e11111111110e1e11111e1010100e11111115120d1e11111e1412110f10100e01011e1f0404040404041111111111110e11111111110a0411111115151b1111110a040a111111110a040404041f01020408101f"
        + "0e11131519110e040c040404040e0e11010204081f0e11010701110e1111111f0101011f101e0101110e0608101e11110e1f0102040808080e11110e11110e0e11110f01020c"

    private static func row(_ letter: Character, _ r: Int) -> Int {
        guard let i = glyphs.firstIndex(of: letter) else { return 0 }
        let at = (glyphs.distance(from: glyphs.startIndex, to: i) * gridH + r) * 2
        let start = font.index(font.startIndex, offsetBy: at)
        let end = font.index(start, offsetBy: 2)
        return Int(font[start..<end], radix: 16) ?? 0
    }

    public static func on(_ letter: Character, _ col: Int, _ r: Int) -> Bool {
        (row(letter, r) >> (gridW - 1 - col)) & 1 == 1
    }

    /// The boxes a letter is made of, in unit space: x and y from -1 to 1.
    /// Horizontal runs, then merged downward where a run repeats — a stem
    /// drawn as seven stacked boxes shows seams, and one box does not.
    ///
    /// These are what a letter is hit as: each box is convex, and a letter,
    /// which is not, is just several of them.
    public static func boxes(_ letter: Character) -> [[Double]] {
        var out: [[Double]] = []
        var taken = Array(repeating: Array(repeating: false, count: gridW), count: gridH)
        for r in 0..<gridH {
            var c = 0
            while c < gridW {
                if !on(letter, c, r) || taken[r][c] { c += 1; continue }
                var end = c
                while end + 1 < gridW && on(letter, end + 1, r) && !taken[r][end + 1] { end += 1 }
                var last = r
                while last + 1 < gridH,
                      (c...end).allSatisfy({ on(letter, $0, last + 1) && !taken[last + 1][$0] }),
                      c == 0 || !on(letter, c - 1, last + 1),
                      end == gridW - 1 || !on(letter, end + 1, last + 1) {
                    last += 1
                }
                for rr in r...last { for cc in c...end { taken[rr][cc] = true } }
                out.append([
                    Double(c) / Double(gridW) * 2 - 1, Double(r) / Double(gridH) * 2 - 1,
                    Double(end + 1) / Double(gridW) * 2 - 1, Double(last + 1) / Double(gridH) * 2 - 1,
                ])
                c = end + 1
            }
        }
        return out
    }

    /// The letter's edge, as closed loops in unit space — the outside wound one
    /// way and the hole in an A or an O the other, so a fill leaves the counter
    /// open.
    ///
    /// The boxes are what a letter is hit as; this is what it is drawn as.
    /// Drawing the boxes leaves a seam on every edge two of them share, and a
    /// letter built of visible bricks does not read as a letter. This has no
    /// internal edges at all: only the cell edges with nothing on the far side.
    public static func outline(_ letter: Character) -> [[Pt]] {
        var edges: [[Int]] = []
        for r in 0..<gridH {
            for c in 0..<gridW {
                if !on(letter, c, r) { continue }
                if r == 0 || !on(letter, c, r - 1) { edges.append([c, r, c + 1, r]) }
                if c == gridW - 1 || !on(letter, c + 1, r) { edges.append([c + 1, r, c + 1, r + 1]) }
                if r == gridH - 1 || !on(letter, c, r + 1) { edges.append([c + 1, r + 1, c, r + 1]) }
                if c == 0 || !on(letter, c - 1, r) { edges.append([c, r + 1, c, r]) }
            }
        }
        var used = Array(repeating: false, count: edges.count)
        var loops: [[Pt]] = []
        for start in edges.indices {
            if used[start] { continue }
            var pts: [Pt] = []
            var cur = start
            while true {
                used[cur] = true
                pts.append(unit(edges[cur][0], edges[cur][1]))
                let ex = edges[cur][2]
                let ey = edges[cur][3]
                if ex == edges[start][0] && ey == edges[start][1] { break }
                // The next edge out of this corner. Two diagonal cells meet at
                // a point and offer two; either closes a loop, and both get
                // walked before this is done.
                guard let next = edges.indices.first(where: {
                    !used[$0] && edges[$0][0] == ex && edges[$0][1] == ey
                }) else { break }
                cur = next
            }
            if pts.count >= 3 { loops.append(pts) }
        }
        return loops
    }

    private static func unit(_ c: Int, _ r: Int) -> Pt {
        Pt(Double(c) / Double(gridW) * 2 - 1, Double(r) / Double(gridH) * 2 - 1)
    }
}

// MARK: - Outlines

public enum Outlines {
    private static func ngon(_ n: Int) -> [Pt] {
        (0..<n).map { i in
            let a = -Double.pi / 2 + Double(i) * 2 * Double.pi / Double(n)
            return Pt(cos(a), sin(a))
        }
    }

    /// Convex outlines on a unit circumradius, so a size means one thing.
    public static let unit: [Shape: [Pt]?] = [
        .circle: nil,
        .triangle: ngon(3),
        .square: [Pt(-0.707, -0.707), Pt(0.707, -0.707), Pt(0.707, 0.707), Pt(-0.707, 0.707)],
        .pentagon: ngon(5),
        .hexagon: ngon(6),
        .bar: [Pt(-1.35, -0.5), Pt(1.35, -0.5), Pt(1.35, 0.5), Pt(-1.35, 0.5)],
    ]

    /// A circle pulled out of round is an ellipse, and an ellipse is not a
    /// circle to bounce off. Sixteen sides at a unit circumradius is close
    /// enough that the seam between what is drawn and what is hit never shows,
    /// and every side of it is still convex.
    public static let ellipse: [Pt] = ngon(16)

    /// How much ground a shape actually covers, as a fraction of its
    /// circumradius — its inradius. A bar is long but narrow and should not
    /// ink a stripe as wide as it is long.
    public static let cover: [Shape: Double] = [
        .circle: 1, .triangle: 0.5, .square: 0.707,
        .pentagon: 0.809, .hexagon: 0.866, .bar: 0.5,
    ]

    /// World-space outline, or nil for a circle.
    public static func points(_ shape: Shape, _ cx: Double, _ cy: Double,
                              _ r: Double, _ rot: Double) -> [Pt]? {
        guard let u = unit[shape] ?? nil else { return nil }
        let c = cos(rot)
        let s = sin(rot)
        return u.map { p in
            let px = p.x * r
            let py = p.y * r
            return Pt(cx + px * c - py * s, cy + px * s + py * c)
        }
    }
}

// MARK: - Geometry

public enum Geom {
    public static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double {
        v < lo ? lo : (v > hi ? hi : v)
    }

    public static func closestOnSegment(_ px: Double, _ py: Double,
                                        _ ax: Double, _ ay: Double,
                                        _ bx: Double, _ by: Double) -> Pt {
        let dx = bx - ax
        let dy = by - ay
        let len2 = dx * dx + dy * dy
        if len2 < 1e-9 { return Pt(ax, ay) }
        let t = clamp(((px - ax) * dx + (py - ay) * dy) / len2, 0, 1)
        return Pt(ax + t * dx, ay + t * dy)
    }

    public static func pointInPoly(_ px: Double, _ py: Double, _ pts: [Pt]) -> Bool {
        var inside = false
        var j = pts.count - 1
        for i in pts.indices {
            let xi = pts[i].x, yi = pts[i].y
            let xj = pts[j].x, yj = pts[j].y
            if (yi > py) != (yj > py),
               px < (xj - xi) * (py - yi) / (yj - yi + 1e-12) + xi {
                inside.toggle()
            }
            j = i
        }
        return inside
    }

    public static func circleVsCircle(_ ax: Double, _ ay: Double, _ ar: Double,
                                      _ bx: Double, _ by: Double, _ br: Double) -> Hit? {
        let dx = ax - bx
        let dy = ay - by
        let d = (dx * dx + dy * dy).squareRoot()
        if d >= ar + br || d <= 0.001 { return nil }
        return Hit(nx: dx / d, ny: dy / d, depth: ar + br - d)
    }

    /// Pushes the circle out of the polygon.
    public static func circleVsPoly(_ cx: Double, _ cy: Double, _ r: Double, _ pts: [Pt]) -> Hit? {
        var q = Pt(0, 0)
        var best = Double.greatestFiniteMagnitude
        for i in pts.indices {
            let a = pts[i]
            let b = pts[(i + 1) % pts.count]
            let p = closestOnSegment(cx, cy, a.x, a.y, b.x, b.y)
            let d = hypot(cx - p.x, cy - p.y)
            if d < best { best = d; q = p }
        }
        let inside = pointInPoly(cx, cy, pts)
        if !inside && best >= r { return nil }
        if best < 1e-6 { return nil }
        let s: Double = inside ? -1 : 1
        return Hit(nx: s * (cx - q.x) / best,
                   ny: s * (cy - q.y) / best,
                   depth: inside ? r + best : r - best)
    }

    private static func project(_ pts: [Pt], _ nx: Double, _ ny: Double) -> (lo: Double, hi: Double) {
        var lo = Double.greatestFiniteMagnitude
        var hi = -Double.greatestFiniteMagnitude
        for p in pts {
            let d = p.x * nx + p.y * ny
            if d < lo { lo = d }
            if d > hi { hi = d }
        }
        return (lo, hi)
    }

    private static func centroid(_ pts: [Pt]) -> Pt {
        var x = 0.0, y = 0.0
        for p in pts { x += p.x; y += p.y }
        return Pt(x / Double(pts.count), y / Double(pts.count))
    }

    /// Separating axis test: the shallowest push that frees A from B.
    public static func satPolyPoly(_ a: [Pt], _ b: [Pt]) -> Hit? {
        var depth = Double.greatestFiniteMagnitude
        var nx = 0.0
        var ny = 0.0
        for pts in [a, b] {
            for i in pts.indices {
                let p0 = pts[i]
                let p1 = pts[(i + 1) % pts.count]
                var ex = -(p1.y - p0.y)
                var ey = p1.x - p0.x
                let len = hypot(ex, ey)
                if len < 1e-9 { continue }
                ex /= len; ey /= len
                let pa = project(a, ex, ey)
                let pb = project(b, ex, ey)
                if pa.hi < pb.lo || pb.hi < pa.lo { return nil }      // a gap
                let o = min(pa.hi - pb.lo, pb.hi - pa.lo)
                if o < depth { depth = o; nx = ex; ny = ey }
            }
        }
        let ca = centroid(a)
        let cb = centroid(b)
        if (ca.x - cb.x) * nx + (ca.y - cb.y) * ny < 0 { nx = -nx; ny = -ny }
        return Hit(nx: nx, ny: ny, depth: depth)
    }
}

// MARK: - Palette

/// Nine families, four tones each. The middle tone of every family is the hand
/// picked colour and the rest are mixed toward white or black from it, so a
/// family holds its hue and nothing turns garish.
public enum Palette {
    /// Fourteen families. The first nine are the originals and keep their
    /// places, because a saved ink is an index — appending is the only change
    /// that does not quietly repaint somebody's bumper table.
    public static let names = [
        "graphite", "bone", "oxblood", "rust", "ochre", "moss", "teal", "slate", "plum",
        "cobalt", "rose", "fern", "umber", "ink",
    ]
    private static let bases: [UInt32] = [
        0x3a3a3c, 0xc9c0ab, 0x702929, 0x9c5b3c, 0xb08940,
        0x5c6e4a, 0x3f6b68, 0x465a78, 0x5c3f5e,
        0x2f4a86, 0xa9596b, 0x3f8248, 0x6d4c33, 0x1d2530,
    ]
    public static let toneMix: [Double] = [0.58, 0.28, 0, -0.38]

    private static func mixChannel(_ v: Int, _ target: Int, _ amount: Double) -> Int {
        min(255, max(0, Int(Double(v) + Double(target - v) * amount)))
    }

    /// `[family][tone]` as opaque ARGB.
    public static let colors: [[UInt32]] = bases.map { base in
        let r = Int((base >> 16) & 0xff)
        let g = Int((base >> 8) & 0xff)
        let b = Int(base & 0xff)
        return toneMix.map { m -> UInt32 in
            let target = m >= 0 ? 255 : 0
            let amt = abs(m)
            let rr = UInt32(mixChannel(r, target, amt))
            let gg = UInt32(mixChannel(g, target, amt))
            let bb = UInt32(mixChannel(b, target, amt))
            return (0xff << 24) | (rr << 16) | (gg << 8) | bb
        }
    }

    public static let alphas: [Double] = [0.15, 0.3, 0.5, 0.75, 1]
    public static let scrims: [Double] = [0, 0.06, 0.12, 0.18, 0.25, 0.34]

    /// What the toy sits on.
    ///
    /// On Android the first one is the whole point: the window is translucent
    /// and the home screen shows through. iOS does not allow that at all, so
    /// here "sheer" means the app's own dark ground rather than your desktop,
    /// and the solid canvases carry the weight the translucency used to.
    public static let canvasNames = ["sheer", "paper", "linen", "sage", "slate", "ink", "black"]
    public static let canvasColors: [UInt32] = [
        0,                    // sheer: never painted
        0xfff4f1ea,           // paper
        0xffe2d9c6,           // linen
        0xffb9c0ab,           // sage
        0xff59636d,           // slate
        0xff23262b,           // ink
        0xff0b0c0e,           // black
    ]

    public static let hapticNames = ["off", "soft", "firm"]
    public static let hapticScales: [Double] = [0, 0.55, 1]

    /// What the toy sounds like. Off first, because a fidget toy that makes
    /// noise the moment it is opened is a fidget toy you put down.
    public static let voiceNames = ["off", "organ", "keys", "drum", "bell", "pluck"]
}

// MARK: - Toy

/// The sound side of the toy: what a hit sounds like, and the arithmetic that
/// turns that into samples.
///
/// The core decides *what* is played and renders it; the platform only pushes
/// the buffer at a speaker. Doing the synthesis here rather than three times
/// over is what keeps the phone, the page and the desktop sounding like the
/// same instrument, and it means the waveform can be tested rather than
/// listened to.
public enum Voices {
    public static let off = 0
    public static let organ = 1
    public static let keys = 2
    public static let drum = 3
    public static let bell = 4
    public static let pluck = 5

    /// A minor pentatonic, in semitones. Any two notes of it played together
    /// are consonant, which is the whole reason a toy that picks its pitches
    /// from what the ball happens to hit does not sound like a wrong-number
    /// tone. Five degrees over three octaves is range enough to tell a hit at
    /// the top of the screen from one at the bottom.
    public static let scale = [0, 3, 5, 7, 10]
    public static let octaves = 3

    /// A above middle C, and the root the scale is built on.
    public static let rootHz = 220.0

    /// Semitones up from `rootHz` for the nth degree of the scale.
    public static func semitone(_ step: Int) -> Int {
        let span = scale.count * octaves
        let n = ((step % span) + span) % span
        return scale[n % scale.count] + 12 * (n / scale.count)
    }

    public static func hz(_ step: Int) -> Double {
        rootHz * pow(2.0, Double(semitone(step)) / 12.0)
    }

    /// The partials each voice is built from: multiples of the fundamental,
    /// and how loud each one is. An organ is the harmonics of a pipe, keys are
    /// a struck string's first two, a bell is deliberately inharmonic — 2.76
    /// and 5.40 are the tuning of a real one and are why it rings rather than
    /// hums — and a pluck is a sawtooth thinned to five terms.
    public static func partials(_ voice: Int) -> [(Double, Double)] {
        switch voice {
        case organ: return [(1, 0.55), (2, 0.28), (3, 0.16), (4, 0.08)]
        case keys:  return [(1, 0.7), (2, 0.22), (5, 0.05)]
        case drum:  return [(1, 0.9)]
        case bell:  return [(1, 0.5), (2.76, 0.3), (5.40, 0.16), (8.93, 0.06)]
        default:    return [(1, 0.5), (2, 0.25), (3, 0.16), (4, 0.12), (5, 0.1)]
        }
    }

    /// How long a note of this voice takes to die away, in seconds.
    public static func decay(_ voice: Int) -> Double {
        switch voice {
        case organ: return 0.42
        case keys:  return 0.55
        case drum:  return 0.20
        case bell:  return 1.35
        default:    return 0.70
        }
    }

    /// How much of a note is noise rather than pitch. A drum is mostly its
    /// skin, glass is mostly its own shattering, and an organ is none.
    public static func grit(_ voice: Int) -> Double {
        voice == drum ? 0.55 : voice == pluck ? 0.12 : 0.02
    }

    /// A drum has no pitch to speak of but it does have a thump: the head
    /// drops this far in the first instants, which is what a struck skin does
    /// and what stops five drum hits sounding like five beeps.
    public static let drumDrop = 0.55
    public static let drumDropTime = 0.035

    /// The attack, in seconds. Short enough to be a hit, long enough not to click.
    public static let attack = 0.004

    /// Nothing is ever louder than this, so a chord of them cannot clip.
    public static let headroom = 0.28
}

/// One sound, decided by the toy and played by the platform. `step` is a
/// degree of the pentatonic rather than a frequency, so a note is a musical
/// choice and the arithmetic that turns it into hertz lives in one place.
public struct Note: Equatable {
    public let voice: Int
    public let step: Int
    public let gain: Double
    /// Extra noise on top of the voice's own: glass is mostly this.
    public let grit: Double
    /// Multiplies the voice's decay: a glancing tap rings shorter.
    public let hold: Double
    /// Seeds the noise, so the same hit sounds the same on every platform.
    public let seed: Int32

    public init(voice: Int, step: Int, gain: Double,
                grit: Double = 0, hold: Double = 1, seed: Int32 = 1) {
        self.voice = voice; self.step = step; self.gain = gain
        self.grit = grit; self.hold = hold; self.seed = seed
    }
}

/// A note, as samples. Additive: a few partials summed under one envelope,
/// plus as much noise as the voice calls for.
public enum Synth {
    /// Long enough for the longest voice, and not a sample longer.
    public static func samples(_ note: Note, _ rate: Int) -> Int {
        let n = Int(Double(rate) * Voices.decay(note.voice) * note.hold * 1.05)
        return min(max(n, 1), rate * 3)
    }

    /// Fills `out` with the note. Returns how many samples were written; the
    /// rest of the buffer is left alone, so a player can hand the same scratch
    /// array to every note it plays.
    @discardableResult
    public static func render(_ note: Note, _ rate: Int, _ out: inout [Float]) -> Int {
        let n = min(samples(note, rate), out.count)
        if note.voice == Voices.off || n <= 0 { return 0 }
        let f0 = Voices.hz(note.step)
        let parts = Voices.partials(note.voice)
        let decay = Voices.decay(note.voice) * note.hold
        let grit = min(max(Voices.grit(note.voice) + note.grit, 0), 1)
        let gain = min(max(note.gain, 0), 1) * Voices.headroom
        var seed = note.seed
        let twoPi = 2.0 * Double.pi

        for i in 0..<n {
            let t = Double(i) / Double(rate)
            // A short attack so a hit is a hit and not a click, then an
            // exponential tail, which is what a struck thing actually does.
            let env = (t < Voices.attack ? t / Voices.attack : 1) * exp(-4.0 * t / decay)
            // The drum's head drops in the first instants.
            let bend = note.voice == Voices.drum
                ? 1 - Voices.drumDrop * (1 - exp(-t / Voices.drumDropTime))
                : 1
            var v = 0.0
            for (mult, amp) in parts { v += amp * sin(twoPi * f0 * mult * bend * t) }
            v *= 1 - grit
            if grit > 0 {
                seed = Toy.nextRand(seed)
                v += grit * Toy.randUnit(seed)
            }
            out[i] = Float(min(max(v * env * gain, -1), 1))
        }
        return n
    }
}

public final class Toy {

    /// Eight steps, a bead through to a grapefruit.
    public static let sizes: [Double] = [0.12, 0.2, 0.3, 0.5, 0.7, 1.0, 1.45, 2.1]
    public static let defaultSize = 5                 // 1.0
    public static let kick = 1.06
    public static let maxSpeed = 6000.0
    /// A bumper can be pulled to a quarter of itself or four times it. Past
    /// that a letter stops being legible in one direction and stops being
    /// hittable in the other, and neither is a shape anyone meant to make.
    /// How head-on a hit on a round surface has to be before it comes off it.
    /// Below this, the ball keeps the speed it had along the surface and only
    /// loses what it had into it — so it follows the curve round instead of
    /// ricocheting off it, and a ball sent past a round bumper at a shallow
    /// angle wraps rather than kicks.
    ///
    /// Measured as |v·n| over |v|: zero is a graze, one is dead-on. At 0.55 a
    /// hit inside about 33 degrees of the surface follows it, and anything
    /// squarer bounces the way it always did.
    /// A bumper's family when it has not been given one of its own: it wears
    /// whatever the ink is. Everything else — the ball, the paint, the
    /// lightning, the glass — is drawn in the current ink already, and a table
    /// that stayed its own five colours whatever you picked was the one thing
    /// standing outside the palette.
    ///
    /// A bumper you have deliberately coloured keeps that colour. This is the
    /// default, not the rule.
    public static let followInk = -1

    /// Beats in a knock, at the hardest. A single click at full strength is
    /// only louder than a soft one — what a hard hit actually feels like is
    /// several things arriving at once, so the harder it lands the more of
    /// them there are.
    public static let bumpsMax = 4

    /// Milliseconds between the beats of one knock. Close enough to read as a
    /// single event with texture rather than as taps you could count; further
    /// apart and a hard hit turns into a stutter.
    public static let bumpGapMs = 17

    /// How much of the first beat the last one keeps. The burst falls away
    /// rather than repeating flat, which is what a thing settling does.
    public static let bumpFalloff = 0.45

    public static let curveBite = 0.55

    public static let minStretch = 0.25
    public static let maxStretch = 4.0

    public static let minBumper = 0.018
    public static let maxBumper = 0.30

    /// Graphite, bone and oxblood — a warm, a cool and a neutral.
    public static let freeFamilies = 3

    /// Sheer and paper: the one that is the point, and one to draw on.
    public static let freeCanvases = 2

    /// The ground the app opens on. Slate rather than sheer — on iOS "sheer"
    /// is only the app's own dark ground anyway, and a ground you can see is
    /// a better first impression. It is free whatever tier you are on,
    /// because a default nobody can use is not a default.
    public static let defaultCanvas = 4

    /// Fast enough to be a real spin, slow enough that the ribs stay ribs.
    /// At 18 ribs this is 40 rib-passes a second, comfortably under the 60Hz
    /// the screen redraws at — push past that and the knurl stops turning and
    /// starts strobing backwards.
    public static let maxDialOmega = 14.0
    public static let dialRibs = 18

    /// How far back a flick is measured, in seconds.
    public static let dialWindow = 0.12

    /// How solid a bumper is, so a painting still shows faintly under it.
    public static let bumperAlpha = 0.72

    // MARK: lightning

    /// Below this a flick is a nudge, not a strike.
    public static let boltMinSpeed = 420.0

    /// A bolt leaves faster than your finger did.
    public static let boltSpeed = 2.0
    public static let boltMaxSpeed = 9000.0

    /// A bolt ends at the wall, not at a stopwatch. This is the failsafe for
    /// one that somehow never gets there, so it is generous.
    public static let boltLife = 1.6

    /// How long a struck bolt stays hot before it is only an etching.
    public static let boltGlow = 0.42

    /// Live bolts, branches included. One flick can make several.
    public static let maxBolts = 40

    /// Chance a kink throws a fork, and how far off the fork leaves.
    public static let boltBranch = 0.19
    public static let boltBranchSpread = 0.62

    /// A fork is slower than what threw it, and can fork twice more.
    public static let boltBranchSpeed = 0.7
    public static let boltMaxGen = 3

    /// How far a first fork gets, as a fraction of the short edge, and how
    /// much of that its own forks keep. This is what "fragment and thin as it
    /// spreads" actually needs: the main stroke reaches the wall, and
    /// everything that leaves it is a spark rather than a second stroke.
    public static let boltReach = 0.5
    public static let boltReachFall = 0.5

    /// How much of the branching chance each generation keeps. Flat, the forks
    /// multiplied — three arms became forty paths, because every fork forked
    /// as eagerly as the stroke that threw it.
    public static let boltBranchFall = 0.45

    /// A strike fans out rather than leaving as one line. Not decoration: a
    /// single line thrown from near an edge met a wall in a tenth of the
    /// screen and died there, so lightning only ever looked like lightning
    /// when it was thrown from the middle.
    public static let boltArc = 1.15

    /// How many arms. The harder you flick the more of them there are, which
    /// is also where the extra knocks come from: every arm that reaches a
    /// wall is its own impact.
    public static let boltArmsMin = 3
    public static let boltArmsMax = 9
    public static let boltArmsFull = 5200.0

    /// How far a strike leans away from a wall it is thrown at. Without this
    /// the fan had to be almost a half-disc to give an edge flick anywhere to
    /// go, and a half-disc reads as a starburst rather than as lightning.
    public static let boltLean = 0.8
    public static let boltLeanReach = 0.42

    /// Etchings kept. A fan lands many more paths than one line did.
    public static let maxEtched = 220

    // MARK: glass

    /// Radial cracks per press. Real glass throws a handful of long fractures
    /// from the point of impact and a few rings around it.
    public static let glassRadialsMin = 7
    public static let glassRadialsMax = 13
    public static let glassRingsMin = 2
    public static let glassRingsMax = 4

    /// Node spacing along a crack, as a fraction of the short edge.
    public static let glassStep = 0.05

    /// How far a crack wanders, as a fraction of that spacing. Lower than
    /// lightning's: a fracture runs nearly straight, and the difference
    /// between nearly and exactly is the whole look of it.
    public static let glassJag = 0.34

    public static let glassRingFirst = 0.1
    public static let glassRingStep = 0.13
    public static let glassRingPoints = 15

    /// Presses kept before the oldest pane is swept up.
    public static let maxBreaks = 14

    public static let paywallLabelsBase = ["subscribe", "restore", "not now"]

    /// The row under the buttons that opens the keypad.
    public static let codePrompt = "have a code?"

    /// What the button says before the store has answered. The real price is
    /// localised and comes from StoreKit; this only stands in on a cold start.
    public static let priceFallback = "$1.99"

    /// The unlock code, stored as a hash rather than as itself, so reading
    /// this file does not hand it over. That is obfuscation and not security:
    /// four digits is ten thousand guesses, the gate is a client-side boolean
    /// either way, and the source is public. It exists so a code can be handed
    /// to a tester or a friend without handing over the app.
    public static let codeHashValue: Int32 = 553159795
    public static let codeLength = 4

    /// FNV-1a over a salted string. Identical in all three ports.
    public static func codeHash(_ entry: String) -> Int32 {
        var h: Int32 = -2128831035
        for u in ("nonsense/" + entry).unicodeScalars {
            h ^= Int32(bitPattern: u.value)
            h = h &* 16777619
        }
        return h
    }

    /// How cool an etching sits under a live strike.
    public static let etchAlpha = 0.62

    /// A live strike is white-hot; a cooled etching keeps its hue, because on
    /// this toy the colour is the point and a near-white core washes it out.
    /// These are how far each is mixed toward white.
    public static let boltCoreHot = 0.9
    public static let boltCoreCool = 0.26

    /// A fork is drawn lighter than what threw it, so the spread reads.
    public static func boltWeight(_ gen: Int) -> Double { 1 - 0.25 * Double(gen) }

    /// How many arms a flick of this speed throws.
    public static func boltArms(_ flick: Double) -> Int {
        let t = Geom.clamp((flick - boltMinSpeed) / max(boltArmsFull - boltMinSpeed, 1), 0, 1)
        return boltArmsMin + Int(Double(boltArmsMax - boltArmsMin) * t)
    }

    /// The shortest signed way round from a to b.
    public static func angleDelta(_ a: Double, _ b: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 2 * Double.pi)
        if d > Double.pi { d -= 2 * Double.pi }
        if d < -Double.pi { d += 2 * Double.pi }
        return d
    }

    /// Spacing of the zigzag's kinks, as a fraction of the short edge.
    public static let boltNode = 0.045

    /// How far each kink throws sideways, as a fraction of that spacing.
    public static let boltJag = 0.9

    /// A bolt runs from your finger to the wall and stops, so its whole path
    /// is the drawing and nothing rolls off the back of it. This is only a
    /// ceiling.
    public static let boltMaxNodes = 96

    /// A plain linear congruential step, identical in Kotlin, Swift and
    /// JavaScript, because the zigzag is part of the simulation rather than
    /// part of the drawing. `&*` and `&+` because Kotlin's Int wraps and
    /// Swift's traps.
    public static func nextRand(_ s: Int32) -> Int32 { s &* 1664525 &+ 1013904223 }

    /// 0 to 1 from a seed.
    public static func rand01(_ s: Int32) -> Double {
        Double((UInt32(bitPattern: s) >> 9) & 0xffff) / 65535
    }

    /// -1 to 1 from a seed.
    public static func randUnit(_ s: Int32) -> Double { rand01(s) * 2 - 1 }

    /// The table out of the box, in the ink you are holding rather than in
    /// five colours of its own. The tones are spread so the pieces still read
    /// as separate things: one family, four shades of it.
    public static func defaultTable() -> [Bumper] {
        [
            Bumper(nx: 0.25, ny: 0.30, size: 0.055, shape: .circle, rot: 0, tone: 1),
            Bumper(nx: 0.75, ny: 0.30, size: 0.055, shape: .circle, rot: 0, tone: 3),
            Bumper(nx: 0.50, ny: 0.50, size: 0.068, shape: .hexagon, rot: 0, tone: 2),
            Bumper(nx: 0.25, ny: 0.72, size: 0.055, shape: .bar, rot: 0, tone: 0),
            Bumper(nx: 0.75, ny: 0.72, size: 0.055, shape: .bar, rot: 0, tone: 3),
        ]
    }

    public init() {}

    // MARK: field

    /// The play field.
    public var w = 0.0
    public var h = 0.0

    /// The whole view, and the home indicator at the foot of it.
    public var viewH = 0.0
    public var insetBottom = 0.0

    /// The status bar, the clock, the camera cutout. The app draws edge to
    /// edge, so anything pinned to the top of the screen lands underneath all
    /// of that unless it is told not to — which is what happened to the edit
    /// toolbar: drawn eight points down, straight under the system icons, and
    /// untappable because the system takes the touch first.
    public var insetTop = 0.0

    public var mode = Mode.ball
    /// Which build this is, printed small under the menu.
    ///
    /// The toy updates by someone downloading it again, and until this was
    /// here there was no way to answer "did it update?" except by hunting for
    /// a feature and hoping. The platform fills it in — the version name on a
    /// phone, the page's own date in a browser — and an empty one prints
    /// nothing rather than a placeholder.
    public var build: String = ""

    public var screen = Screen.title

    public var tier = Tier.free
    public func full() -> Bool { tier == .full }

    /// What the paywall goes back to when it is dismissed.
    private var paywallFrom = Screen.title

    /// Set by the platform once it knows what the unlock costs.
    public var priceText: String?

    // MARK: what costs money

    /// The ball is the free toy. Everything else — the dial, the table,
    /// lightning, paint — is the unlock.
    public func modeLocked(_ m: Mode) -> Bool { !full() && m != .ball }
    public func editLocked() -> Bool { !full() }
    public func familyLocked(_ i: Int) -> Bool { !full() && i >= Toy.freeFamilies }
    public func canvasLocked(_ i: Int) -> Bool {
        !full() && i >= Toy.freeCanvases && i != Toy.defaultCanvas
    }

    /// Anything locked sends you here rather than doing nothing at all.
    public func showPaywall() {
        if case .paywall = screen { return }
        paywallFrom = screen
        screen = .paywall
    }

    public func dismissPaywall() { screen = paywallFrom }

    /// Applied the moment a purchase lands, so the UI can just redraw.
    public func unlock() {
        tier = .full
        if case .paywall = screen { dismissPaywall() }
    }

    /// Pulls anything paid back into reach of the free tier. A refund is the
    /// case that matters: without this, someone who was full keeps the black
    /// canvas and the plum ink they no longer own, because those are just
    /// saved indices.
    ///
    /// The factory bumper colours are left alone on purpose. They ship with
    /// the app rather than being chosen from the palette, and a free tier
    /// whose table is five identical grey shapes is a worse advertisement for
    /// the paid one than a handsome table you cannot yet rearrange.
    public func clampToTier() {
        if full() { return }
        if inkFamily >= Toy.freeFamilies { inkFamily = 0 }
        if canvasLocked(canvasIndex) { canvasIndex = Toy.defaultCanvas }
        if modeLocked(mode) { mode = .ball }
        if editing { editing = false; selected = -1 }
        closeDrawer()
    }

    // MARK: ball

    public var bx = 0.0
    public var by = 0.0
    public var vx = 0.0
    public var vy = 0.0
    public var baseR = 0.0
    public var sizeIndex = Toy.defaultSize
    public var shape = Shape.circle
    public var spin = 0.0
    public var omega = 0.0
    public var dragging = false
    public var mustCatch = false
    private var grabDX = 0.0
    private var grabDY = 0.0
    private var placed = false

    public let friction = 0.55
    public let restitution = 0.82
    public let spinFriction = 0.45

    // MARK: dial

    public var dialAngle = 0.0
    public var dialOmega = 0.0
    public var dialR = 0.0
    public var dialGrab = false

    /// A knurled wheel coasts. The old 0.35 shed two thirds of its speed every
    /// second and was still before you had let go of it, which is why it never
    /// looked like it was spinning at all.
    public let dialFriction = 0.78

    public let dialRibs = Toy.dialRibs

    /// Counts ribs passing the index mark, so the view can click for each.
    public var dialDetent = 0
    private var dialDetentIndex = 0
    private var dialLastAngle = 0.0

    /// Recent drag samples as (turned, seconds), newest last.
    private var dialSamples: [(d: Double, dt: Double)] = []

    // MARK: lightning

    public private(set) var bolts: [Bolt] = []

    /// Every bolt that has arrived, in the order it landed.
    public private(set) var etched: [Etched] = []
    private var boltSeed: Int32 = 0x5eed

    // MARK: bumpers

    public var table: [Bumper] = Toy.defaultTable()
    public var editing = false
    public var selected = -1

    // MARK: ink

    public var inkFamily = 0
    public var inkTone = 2
    // The point of the app is that it is sheer. Defaults that read as opaque
    // hide that, so the ball starts see-through and the tint starts light.
    public var inkAlphaIndex = 3      // 0.75
    public var scrimIndex = 1         // 6%
    public var canvasIndex = Toy.defaultCanvas
    public var paintOnBumpers = true

    /// How hard the phone is allowed to answer. Off, soft, firm.
    public var hapticIndex = 2

    /// Set when a grab found nothing, so the view can mark the spot.
    public var missX = 0.0
    public var missY = 0.0
    public var missAt: TimeInterval = 0

    /// True on the frame the ball stops, so the view can settle its stroke.
    public var justCameToRest = false

    /// Bumped on every reflection; the view fires a haptic when it changes.
    public var bounceCount = 0
    public var lastImpact = 0.0

    /// True when the last impact was a wall rather than a bumper.
    public var lastImpactWall = false

    /// How hard the last impact was, 0 to 1.
    public func impactStrength() -> Double {
        let floorV = 200.0
        let ceiling = 2600.0
        if lastImpact < floorV { return 0 }
        return Geom.clamp((lastImpact - floorV) / (ceiling - floorV), 0, 1)
    }

    /// How many beats the last impact should be felt as. One for anything you
    /// could call a tap; a burst for a hard landing, which is what "the harder
    /// you flick, the more you feel" comes down to — a flick sets the speed,
    /// the speed sets the impact, and the impact sets this.
    ///
    /// A wall gets one beat fewer than a bumper at the same speed: it is a
    /// flat thing to hit, and a bumper throws the ball back.
    public func impactBumps() -> Int {
        let hit = impactStrength()
        if hit <= 0 { return 0 }
        let most = lastImpactWall ? Toy.bumpsMax - 1 : Toy.bumpsMax
        return min(max(1 + Int(hit * Double(most)), 1), most)
    }

    /// How hard the `i`th beat of a knock is, 0 to 1. The first is the impact
    /// itself; the rest fall away towards `bumpFalloff` of it.
    public func bumpLevel(_ i: Int) -> Double {
        let n = impactBumps()
        if n <= 0 || i < 0 || i >= n { return 0 }
        if n == 1 { return impactStrength() }
        let t = Double(i) / Double(n - 1)
        return impactStrength() * (1 - (1 - Toy.bumpFalloff) * t)
    }

    public func inkColor() -> UInt32 { Palette.colors[inkFamily][inkTone] }

    /// True while nothing is painted underneath.
    public func sheer() -> Bool { canvasIndex == 0 }

    /// The ground colour, opaque. Meaningless while `sheer()`.
    public func canvasColor() -> UInt32 { Palette.canvasColors[canvasIndex] }

    public func inkAlpha() -> Double { Palette.alphas[inkAlphaIndex] }
    public func scrim() -> Double { Palette.scrims[scrimIndex] }
    public func hapticScale() -> Double { Palette.hapticScales[hapticIndex] }

    // MARK: sound

    /// Which voice the toy speaks in. On, out of the box.
    ///
    /// This started off silent, on the reasoning that a fidget toy making
    /// noise the moment you open it is one you put down. That was the wrong
    /// call for a feature somebody asked for: a sound you have to go to the
    /// bottom of a drawer to switch on is a sound most people never learn
    /// exists. Keys is the least tiring of the five to have going while you
    /// fidget, and the row that turns it off is where it always was.
    public var voiceIndex = Voices.keys

    /// Notes the toy has decided to play, waiting for the platform to come and
    /// take them. The view drains this every frame.
    public var notes: [Note] = []

    /// Nothing may pile up more than this: a hail of glass is not a siren.
    public let maxNotes = 8

    public var noteSeed: Int32 = 0x7c9d

    func say(_ step: Int, _ gain: Double, grit: Double = 0, hold: Double = 1) {
        if voiceIndex == Voices.off || gain <= 0.02 || notes.count >= maxNotes { return }
        noteSeed = Toy.nextRand(noteSeed)
        notes.append(Note(voice: voiceIndex, step: step, gain: gain,
                          grit: grit, hold: hold, seed: noteSeed))
    }

    /// Hands the waiting notes over and forgets them.
    public func takeNotes() -> [Note] {
        if notes.isEmpty { return [] }
        let out = notes
        notes.removeAll(keepingCapacity: true)
        return out
    }

    /// Where on the field a hit was, as a degree of the scale: up the screen is
    /// up the scale, which is the mapping anyone expects without being told.
    public func stepAt(_ px: Double, _ py: Double) -> Int {
        let range = Voices.scale.count * Voices.octaves
        let up = 1 - Geom.clamp(py / max(h, 1), 0, 1)
        let across = Geom.clamp(px / max(w, 1), 0, 1)
        return min(max(Int(up * Double(range - 1) + (across - 0.5) * 1.5), 0), range - 1)
    }

    /// A bumper's note: the bigger it is the lower it sounds, which is what a
    /// bigger thing does, and its tone nudges it within that so two bumpers of
    /// a size are not in unison.
    public func bumperStep(_ b: Bumper) -> Int {
        let range = Voices.scale.count * Voices.octaves
        let big = Geom.clamp((b.size - Toy.minBumper) / (Toy.maxBumper - Toy.minBumper), 0, 1)
        return min(max(Int((1 - big) * Double(range - 3) + Double(b.tone)), 0), range - 1)
    }

    /// The note the next impact should use, set by whatever is about to be hit
    /// and cleared as soon as it is. A bumper knows its own note; a wall does
    /// not, and takes the one under the ball.
    var nextStep = -1

    public func ballR() -> Double { baseR * Toy.sizes[sizeIndex] }
    public func inkWidth() -> Double { ballR() * 2 * (Outlines.cover[shape] ?? 1) }
    public func ballPoints() -> [Pt]? { Outlines.points(shape, bx, by, ballR(), spin) }

    /// Paint is live in paint mode, and on the bumper table when switched on.
    public func painting() -> Bool {
        if editing { return false }
        return mode == .paint || (mode == .bumpers && paintOnBumpers)
    }

    public func catching() -> Bool { mode == .ball && mustCatch && !editing }

    /// `newViewH` is the whole view; `newInsetBottom` is the home indicator.
    /// The play field is what is left after that and the control rows, so
    /// nothing the ball does — and nothing you have to tap — ever lands under
    /// the gesture bar.
    public func resize(_ newW: Double, _ newViewH: Double, _ newInsetBottom: Double = 0,
                       _ newInsetTop: Double = 0) {
        if newW <= 0 || newViewH <= 0 { return }
        w = newW
        viewH = newViewH
        insetBottom = max(0, newInsetBottom)
        insetTop = max(0, newInsetTop)
        h = max(1, viewH - insetBottom - chromeH())
        // Derived sizes follow the field. Computing them once meant a view
        // that was measured at zero produced a radius of zero.
        baseR = min(w, h) * 0.05
        dialR = min(w, h) * 0.22
        if !placed { bx = w / 2; by = h / 2; placed = true }
    }

    public func bumperPoints(_ b: Bumper) -> [Pt]? {
        if !b.glyph.isEmpty { return nil }
        guard let u = Outlines.unit[b.shape] ?? nil else { return nil }
        return stretched(u, b)
    }

    /// A unit outline, stretched by the bumper's two axes, then turned.
    private func stretched(_ unit: [Pt], _ b: Bumper) -> [Pt] {
        let m = min(w, h)
        let cx = b.nx * w
        let cy = b.ny * h
        let r = b.size * m
        let c = cos(b.rot)
        let si = sin(b.rot)
        return unit.map { p in
            let x = p.x * r * b.sx
            let y = p.y * r * b.sy
            return Pt(cx + x * c - y * si, cy + x * si + y * c)
        }
    }

    /// Every convex piece of a bumper. One for an outline, none for a circle,
    /// and a handful for a letter — a letter is not convex, and the only way
    /// to bounce off one honestly is to bounce off its parts.
    public func bumperParts(_ b: Bumper) -> [[Pt]] {
        if b.glyph.isEmpty {
            if let pts = bumperPoints(b) { return [pts] }
            // A round bumper stays an exact circle until it is pulled; only
            // then is it worth trading that for sixteen flat sides.
            if b.sx != 1 || b.sy != 1 { return [stretched(Outlines.ellipse, b)] }
            return []
        }
        return Letters.boxes(Character(b.glyph)).map { box in
            stretched([Pt(box[0], box[1]), Pt(box[2], box[1]),
                       Pt(box[2], box[3]), Pt(box[0], box[3])], b)
        }
    }

    /// What a bumper is drawn as, rather than what it is hit as: one loop for
    /// an outline, and for a letter its edge — not its boxes, which would show
    /// a seam wherever two of them meet.
    public func bumperLoops(_ b: Bumper) -> [[Pt]] {
        if b.glyph.isEmpty { return bumperParts(b) }
        return Letters.outline(Character(b.glyph)).map { stretched($0, b) }
    }

    public func bumperColor(_ b: Bumper) -> UInt32 {
        Palette.colors[b.family == Toy.followInk ? inkFamily : b.family][b.tone]
    }
    public func bumperCenter(_ b: Bumper) -> Pt { Pt(b.nx * w, b.ny * h) }
    public func bumperRadius(_ b: Bumper) -> Double { b.size * min(w, h) }

    public func encodeTable() -> String {
        table.map {
            "\($0.nx),\($0.ny),\($0.size),\(name(of: $0.shape)),\($0.rot),\($0.family),\($0.tone)," +
            "\($0.sx),\($0.sy),\($0.glyph)"
        }.joined(separator: ";")
    }

    /// Rows written before bumpers had a colour of their own have five fields
    /// rather than seven. They still load, in graphite — a saved table is
    /// someone's arrangement and is not worth throwing away over a new field.
    public func decodeTable(_ raw: String) -> [Bumper] {
        raw.split(separator: ";").compactMap { row -> Bumper? in
            let f = row.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard f.count == 5 || f.count == 7 || f.count == 10 else { return nil }
            guard let nx = Double(f[0]), let ny = Double(f[1]),
                  let size = Double(f[2]), let shape = shape(named: f[3]),
                  let rot = Double(f[4]) else { return nil }
            var family = Toy.followInk
            var tone = 2
            if f.count >= 7 {
                guard let fa = Int(f[5]), let to = Int(f[6]) else { return nil }
                // -1 is a bumper that follows the ink, and it has to survive
                // the round trip: a table saved following the ink and loaded
                // clamped to graphite would leave the palette again.
                family = min(max(fa, Toy.followInk), Palette.names.count - 1)
                tone = min(max(to, 0), Palette.toneMix.count - 1)
            }
            var sx = 1.0
            var sy = 1.0
            var glyph = ""
            if f.count == 10 {
                guard let x = Double(f[7]), let y = Double(f[8]) else { return nil }
                sx = Geom.clamp(x, Toy.minStretch, Toy.maxStretch)
                sy = Geom.clamp(y, Toy.minStretch, Toy.maxStretch)
                if f[9].count == 1, Letters.glyphs.contains(f[9]) { glyph = f[9] }
            }
            return Bumper(nx: Geom.clamp(nx, 0, 1), ny: Geom.clamp(ny, 0, 1),
                          size: Geom.clamp(size, Toy.minBumper, Toy.maxBumper),
                          shape: shape, rot: rot, family: family, tone: tone,
                          sx: sx, sy: sy, glyph: glyph)
        }
    }

    /// Kotlin gets these names from the enum itself; Swift does not, and a
    /// saved table is not the place to discover that.
    public func name(of shape: Shape) -> String {
        switch shape {
        case .circle: return "CIRCLE"
        case .triangle: return "TRIANGLE"
        case .square: return "SQUARE"
        case .pentagon: return "PENTAGON"
        case .hexagon: return "HEXAGON"
        case .bar: return "BAR"
        }
    }

    public func shape(named s: String) -> Shape? {
        Shape.allCases.first { name(of: $0) == s }
    }

    public func pointInBumper(_ px: Double, _ py: Double, _ b: Bumper) -> Bool {
        let parts = bumperParts(b)
        if parts.isEmpty {
            // A circle, and a stretched circle is an ellipse.
            let m = min(w, h)
            let dx = px - b.nx * w
            let dy = py - b.ny * h
            let c = cos(-b.rot)
            let si = sin(-b.rot)
            let lx = (dx * c - dy * si) / max(b.size * m * b.sx, 1e-4)
            let ly = (dx * si + dy * c) / max(b.size * m * b.sy, 1e-4)
            return lx * lx + ly * ly <= 1
        }
        // A letter is grabbed anywhere on it, and a fat target is kinder than
        // asking someone to land on the crossbar of an H.
        if !b.glyph.isEmpty,
           hypot(px - b.nx * w, py - b.ny * h) <= bumperRadius(b) * max(b.sx, b.sy) {
            return true
        }
        return parts.contains { Geom.pointInPoly(px, py, $0) }
    }

    // MARK: catching

    /// Small balls get slack so a bead is not a pixel-hunt; a big one you
    /// simply have to hit.
    public func catchSlack() -> Double { max(0, min(w, h) * 0.04 - ballR() * 0.5) }

    public func withinCatch(_ px: Double, _ py: Double) -> Bool {
        let slack = catchSlack()
        guard let pts = ballPoints() else {
            return hypot(px - bx, py - by) <= ballR() + slack
        }
        if Geom.pointInPoly(px, py, pts) { return true }
        var best = Double.greatestFiniteMagnitude
        for i in pts.indices {
            let a = pts[i]
            let b = pts[(i + 1) % pts.count]
            let p = Geom.closestOnSegment(px, py, a.x, a.y, b.x, b.y)
            let d = hypot(px - p.x, py - p.y)
            if d < best { best = d }
        }
        return best <= slack
    }

    // MARK: input

    /// Returns false when the grab missed a ball that had to be caught.
    @discardableResult
    public func grab(_ px: Double, _ py: Double, _ now: TimeInterval) -> Bool {
        if catching() && !withinCatch(px, py) {
            missX = px; missY = py; missAt = now
            return false
        }
        // Caught, so it stays where it was caught: snapping it to the finger
        // would undo the catch.
        let caught = catching()
        grabDX = caught ? bx - px : 0
        grabDY = caught ? by - py : 0
        dragging = true
        if !caught { bx = px; by = py }
        vx = 0; vy = 0
        omega = 0
        return true
    }

    public func drag(_ px: Double, _ py: Double) {
        guard dragging else { return }
        bx = px + grabDX
        by = py + grabDY
    }

    public func release(_ flingX: Double, _ flingY: Double) {
        guard dragging else { return }
        dragging = false
        vx = flingX
        vy = flingY
        omega = Geom.clamp(omega + vx / max(ballR(), 1) * 0.25, -30, 30)
    }

    public func grabDial(_ px: Double, _ py: Double) {
        dialGrab = true
        dialOmega = 0
        dialLastAngle = angleTo(px, py)
        dialSamples.removeAll()
    }

    /// How fast the wheel has been turning over the last `dialWindow`.
    private func windowOmega() -> Double {
        var turned = 0.0
        var seconds = 0.0
        for s in dialSamples { turned += s.d; seconds += s.dt }
        return seconds <= 0 ? 0 : turned / seconds
    }

    /// `dt` is the time since the previous drag sample, in seconds.
    ///
    /// The speed is taken over a short window rather than from the last sample
    /// alone. A finger nearly always stalls for a frame or two before it
    /// lifts, so reading the final sample meant a hard flick released at
    /// almost no speed — the wheel stopped the instant you let go of it.
    public func dragDial(_ px: Double, _ py: Double, _ dt: Double) {
        guard dialGrab else { return }
        let a = angleTo(px, py)
        var d = a - dialLastAngle
        while d > Double.pi { d -= 2 * Double.pi }
        while d < -Double.pi { d += 2 * Double.pi }
        dialLastAngle = a
        dialAngle += d

        dialSamples.append((d: d, dt: max(dt, 0.001)))
        var held = 0.0
        for s in dialSamples { held += s.dt }
        while held > Toy.dialWindow && dialSamples.count > 1 {
            held -= dialSamples[0].dt
            dialSamples.removeFirst()
        }
        dialOmega = Geom.clamp(windowOmega(), -Toy.maxDialOmega, Toy.maxDialOmega)
        updateDetents()
    }

    /// Letting go of a moving wheel throws it, rather than handing it back.
    public func releaseDial() {
        dialGrab = false
        dialOmega = Geom.clamp(windowOmega() * 1.4, -Toy.maxDialOmega, Toy.maxDialOmega)
        dialSamples.removeAll()
    }

    /// Every rib that crosses the index mark bumps `dialDetent` by one,
    /// whether the wheel is being turned or coasting.
    private func updateDetents() {
        let step = 2 * Double.pi / Double(dialRibs)
        let idx = Int(floor(dialAngle / step))
        if idx != dialDetentIndex {
            dialDetent += abs(idx - dialDetentIndex)
            dialDetentIndex = idx
        }
    }

    /// 0 at rest, 1 at the cap — the view lightens its click as it speeds up.
    public func dialSpeedFraction() -> Double {
        Geom.clamp(abs(dialOmega) / Toy.maxDialOmega, 0, 1)
    }

    public func angleTo(_ px: Double, _ py: Double) -> Double {
        atan2(py - h / 2, px - w / 2)
    }

    // MARK: physics

    /// Every reflection passes through here, so it is also where impacts are
    /// counted — the view watches `bounceCount` to fire a haptic.
    private func impartSpin(_ nx: Double, _ ny: Double, fromWall: Bool) {
        let r = max(ballR(), 1)
        let t = vx * -ny + vy * nx            // velocity along the surface
        omega = Geom.clamp(omega + t / r * 0.5, -30, 30)
        registerImpact(hypot(vx, vy), fromWall: fromWall)
    }

    /// The one place an impact is recorded, whatever hit what. The view
    /// watches `bounceCount` and knows nothing about balls or bolts, so
    /// lightning striking a wall feels like a ball striking a wall without
    /// the view needing a line of new code.
    private func registerImpact(_ speed: Double, fromWall: Bool, speak: Bool = true) {
        bounceCount += 1
        lastImpact = speed
        lastImpactWall = fromWall
        // A wall is a duller, shorter thing to hit than a bumper, and a hit
        // you can barely feel is one you should barely hear.
        let hit = impactStrength()
        if speak && hit > 0 {
            let step = (fromWall || nextStep < 0) ? stepAt(bx, by) : nextStep
            say(step, 0.25 + 0.75 * hit, hold: fromWall ? 0.55 : 1)
        }
        nextStep = -1
    }

    // MARK: lightning

    /// Returns false when the flick was too slow to be a strike.
    @discardableResult
    public func fireBolt(_ px: Double, _ py: Double, _ flingX: Double, _ flingY: Double) -> Bool {
        let flick = hypot(flingX, flingY)
        if flick < Toy.boltMinSpeed { return false }
        var bvx = flingX * Toy.boltSpeed
        var bvy = flingY * Toy.boltSpeed
        let sp = hypot(bvx, bvy)
        if sp > Toy.boltMaxSpeed { bvx *= Toy.boltMaxSpeed / sp; bvy *= Toy.boltMaxSpeed / sp }
        // The ink is read at the strike, not at the drawing, so an etching
        // keeps the colour it was thrown in however you change the palette
        // afterwards.
        let argb = inkColor()
        let arms = Toy.boltArms(flick)
        let aim = boltAim(px, py, bvx, bvy)
        let speed = hypot(bvx, bvy)
        for i in 0..<arms {
            // The first arm goes where you threw it; the rest fan out either
            // side, alternating, so the spread stays centred on the throw.
            let step = i == 0 ? 0 : Double((i + 1) / 2) / (Double(arms - 1) / 2 + 0.001)
            let turn = (i % 2 == 0 ? -1.0 : 1.0) * Geom.clamp(step, 0, 1) * Toy.boltArc
            boltSeed = Toy.nextRand(boltSeed)
            // A little speed apart, so the arms do not all arrive at once and
            // the knocks land as a volley rather than a single thud.
            let f = 0.72 + 0.28 * Toy.rand01(boltSeed)
            let a = aim + turn
            bolts.append(Bolt(x: px, y: py, vx: cos(a) * speed * f, vy: sin(a) * speed * f,
                              rng: boltSeed, argb: argb, gen: 0))
        }
        while bolts.count > Toy.maxBolts { bolts.removeFirst() }
        // The throw is the thunder: low, long and mostly noise, and the harder
        // you threw it the further down it goes. The arms landing are heard
        // separately, as the knocks they already were.
        let hard = Geom.clamp(flick / Toy.boltArmsFull, 0, 1)
        say(Int((1 - hard) * 3) + 1, 0.55 + 0.45 * hard, grit: 0.5, hold: 1.6)
        return true
    }

    /// The throw direction, leaned away from whichever wall is closest.
    public func boltAim(_ px: Double, _ py: Double, _ vx: Double, _ vy: Double) -> Double {
        let short = min(w, h)
        let near = min(px, w - px, py, h - py)
        let lean = Geom.clamp(1 - near / max(short * Toy.boltLeanReach, 1), 0, 1) * Toy.boltLean
        let a0 = atan2(vy, vx)
        let inward = atan2(h / 2 - py, w / 2 - px)
        return a0 + Toy.angleDelta(a0, inward) * lean
    }

    /// How far a fork of this generation may travel before it gives out.
    public func boltReach(_ gen: Int) -> Double {
        if gen <= 0 { return Double.greatestFiniteMagnitude }
        var r = min(w, h) * Toy.boltReach
        for _ in 1..<max(gen, 1) { r *= Toy.boltReachFall }
        return r
    }

    /// How likely a kink of this generation is to throw a fork.
    public func boltBranchChance(_ gen: Int) -> Double {
        var c = Toy.boltBranch
        for _ in 0..<max(gen, 0) { c *= Toy.boltBranchFall }
        return c
    }

    /// A fork, leaving at an angle to whatever threw it.
    private func branch(_ b: Bolt) {
        if b.gen >= Toy.boltMaxGen || bolts.count >= Toy.maxBolts { return }
        b.rng = Toy.nextRand(b.rng)
        // Off to the side the last kink threw, and never by a little: a
        // uniform turn puts most forks within a few degrees of their parent,
        // which draws parallel streaks rather than a tree.
        let turn = b.side * (0.45 + 0.55 * abs(Toy.randUnit(b.rng))) * Toy.boltBranchSpread
        let c = cos(turn), si = sin(turn), f = Toy.boltBranchSpeed
        let fork = Bolt(x: b.x, y: b.y,
                        vx: (b.vx * c - b.vy * si) * f,
                        vy: (b.vx * si + b.vy * c) * f,
                        rng: Toy.nextRand(b.rng), argb: b.argb, gen: b.gen + 1)
        fork.reach = boltReach(fork.gen)
        bolts.append(fork)
    }

    /// Everything that has arrived, wiped.
    public func clearEtched() { etched.removeAll() }

    // MARK: glass

    /// Every pane broken so far, in the order it was pressed.
    public private(set) var breaks: [Break] = []

    private var glassSeed: Int32 = 0x91a5

    public func clearGlass() { breaks.removeAll() }

    /// One crack, walked outward until it leaves the field. Jagged the way a
    /// bolt is jagged — alternating side, random magnitude — because a
    /// fracture that wanders randomly is a scribble and one that does not
    /// wander at all is a ruler line.
    private func crackOut(_ x0: Double, _ y0: Double, _ angle: Double, _ depth: Int) -> Crack {
        let step = max(min(w, h) * Toy.glassStep, 1)
        var nodes = [Pt(x0, y0)]
        var x = x0, y = y0, side = 1.0
        let dx = cos(angle), dy = sin(angle)
        let limit = Int(hypot(w, h) / step) + 2
        for _ in 0..<limit {
            x += dx * step
            y += dy * step
            if x < 0 || x > w || y < 0 || y > h {
                // A crack ends at the edge of the pane.
                nodes.append(Pt(Geom.clamp(x, 0, w), Geom.clamp(y, 0, h)))
                break
            }
            glassSeed = Toy.nextRand(glassSeed)
            side = -side
            let jag = side * (0.4 + 0.6 * abs(Toy.randUnit(glassSeed))) * step * Toy.glassJag
            nodes.append(Pt(Geom.clamp(x + -dy * jag, 0, w), Geom.clamp(y + dx * jag, 0, h)))
        }
        return Crack(nodes: nodes, ring: false, depth: depth)
    }

    /// A ring around the impact, closed, and clipped to the pane.
    private func crackRing(_ x0: Double, _ y0: Double, _ r: Double, _ depth: Int) -> Crack {
        var nodes: [Pt] = []
        for i in 0...Toy.glassRingPoints {
            let a = Double(i) / Double(Toy.glassRingPoints) * 2 * Double.pi
            glassSeed = Toy.nextRand(glassSeed)
            let rr = r * (0.86 + 0.28 * Toy.rand01(glassSeed))
            nodes.append(Pt(Geom.clamp(x0 + cos(a) * rr, 0, w),
                            Geom.clamp(y0 + sin(a) * rr, 0, h)))
        }
        return Crack(nodes: nodes, ring: true, depth: depth)
    }

    /// Break the pane at a point. False only if the field has no size yet.
    @discardableResult
    public func breakGlass(_ px: Double, _ py: Double) -> Bool {
        if w <= 0 || h <= 0 { return false }
        let short = min(w, h)
        glassSeed = Toy.nextRand(glassSeed)
        let radials = Toy.glassRadialsMin
            + Int(Double(Toy.glassRadialsMax - Toy.glassRadialsMin) * Toy.rand01(glassSeed))
        glassSeed = Toy.nextRand(glassSeed)
        let rings = Toy.glassRingsMin
            + Int(Double(Toy.glassRingsMax - Toy.glassRingsMin) * Toy.rand01(glassSeed))

        var cracks: [Crack] = []
        let start = Toy.randUnit(glassSeed) * Double.pi
        for i in 0..<radials {
            glassSeed = Toy.nextRand(glassSeed)
            // Evenly spaced then nudged: even spokes read as a wheel.
            let a = start + Double(i) / Double(radials) * 2 * Double.pi
                + Toy.randUnit(glassSeed) * (Double.pi / Double(radials)) * 0.55
            cracks.append(crackOut(px, py, a, i % 3))
        }
        for k in 0..<rings {
            cracks.append(crackRing(px, py,
                                    short * (Toy.glassRingFirst + Toy.glassRingStep * Double(k)), k))
        }
        breaks.append(Break(x: px, y: py, argb: inkColor(), cracks: cracks))
        // Glass is mostly its own shattering: a high note with a great deal of
        // grit on it, and the more it cracked the brighter it goes.
        say(stepAt(px, py) + radials % 4, 0.9, grit: 0.55, hold: 0.8)
        while breaks.count > Toy.maxBreaks { breaks.removeFirst() }
        // The pane going is a hard, flat knock, and the view already knows how
        // to feel one of those. It is not heard as one: the shatter above is
        // the sound of it, and both at once is just mud.
        registerImpact(2600, fromWall: true, speak: false)
        return true
    }

    private func etch(_ b: Bolt) {
        if b.nodes.count < 2 { return }
        etched.append(Etched(nodes: b.nodes, argb: b.argb, gen: b.gen))
        while etched.count > Toy.maxEtched { etched.removeFirst() }
    }

    /// A kink in the zigzag: at a wall it is exact, in flight it throws.
    private func addNode(_ b: Bolt, _ node: Double, exact: Bool) {
        while b.nodes.count >= Toy.boltMaxNodes { b.nodes.removeFirst() }
        if exact {
            b.nodes.append(Pt(b.x, b.y))
        } else {
            b.rng = Toy.nextRand(b.rng)
            // Alternating, not random. A displacement with a random sign is a
            // random walk: it drifts, and it draws a wobbling rope. Lightning
            // throws to one side and then the other, and only the size of the
            // throw varies.
            b.side = -b.side
            let jag = b.side * (0.45 + 0.55 * abs(Toy.randUnit(b.rng))) * node * Toy.boltJag
            let sp = max(hypot(b.vx, b.vy), 1)
            // Clamped, because the head reflecting off a wall is not the whole
            // story: a kink thrown sideways near an edge lands outside the
            // field, and the field stops where the controls begin.
            b.nodes.append(Pt(Geom.clamp(b.x + -b.vy / sp * jag, 0, w),
                              Geom.clamp(b.y + b.vx / sp * jag, 0, h)))
        }
        b.sinceNode = 0
    }

    public func stepBolts(_ dt: Double) {
        let node = max(min(w, h) * Toy.boltNode, 1)
        // By index, because a fork is appended to this same list as it is
        // walked, and a fork thrown this frame should start travelling the
        // next one rather than in the middle of its parent's step.
        let live = bolts.count
        for bi in 0..<live {
            let b = bolts[bi]
            if b.struck { b.life -= dt / Toy.boltGlow; continue }
            let speed = hypot(b.vx, b.vy)
            // Substepped for the same reason the ball is: at nine thousand
            // pixels a second a bolt would cross the field between frames.
            let steps = min(48, max(1, Int(ceil(speed * dt / max(node * 0.5, 1)))))
            let hStep = dt / Double(steps)
            for _ in 0..<steps {
                if b.struck { break }
                b.x += b.vx * hStep
                b.y += b.vy * hStep
                let travelled = hypot(b.vx, b.vy) * hStep
                b.sinceNode += travelled
                b.reach -= travelled

                // The wall is the end of the journey, not a cushion. A bolt
                // that bounced was a ball with a zigzag drawn on it; this one
                // arrives, knocks, and stays where it landed.
                if b.x <= 0 || b.x >= w || b.y <= 0 || b.y >= h {
                    b.x = Geom.clamp(b.x, 0, w)
                    b.y = Geom.clamp(b.y, 0, h)
                    b.struck = true
                    registerImpact(speed, fromWall: true)
                    addNode(b, node, exact: true)
                    etch(b)
                } else if b.reach <= 0 {
                    // Out of road. A fork does not reach a wall and does not
                    // need to: it stops where it ran out, which is what the
                    // end of a spark looks like.
                    addNode(b, node, exact: true)
                    b.struck = true
                    etch(b)
                } else if b.sinceNode >= node {
                    addNode(b, node, exact: false)
                    b.rng = Toy.nextRand(b.rng)
                    if Toy.rand01(b.rng) < boltBranchChance(b.gen) { branch(b) }
                }
            }
            if !b.struck {
                b.life -= dt / Toy.boltLife
                // Out of road without ever reaching a wall: it still counts as
                // arrived, or a stray fork would simply blink out.
                if b.life <= 0 { etch(b) }
            }
        }
        bolts.removeAll { $0.life <= 0 }
    }

    /// How brightly a bolt still burns. Falls away late rather than evenly.
    public func boltAlpha(_ b: Bolt) -> Double { pow(Geom.clamp(b.life, 0, 1), 0.55) }

    public func step(_ dt: Double) {
        justCameToRest = false
        if w <= 0 || h <= 0 { return }
        if case .title = screen { return }
        if mode == .bolt {
            stepBolts(dt)
            return
        }
        if mode == .dial {
            if !dialGrab {
                dialOmega *= pow(dialFriction, dt)
                if abs(dialOmega) < 0.05 { dialOmega = 0 }
                dialAngle += dialOmega * dt
                updateDetents()
            }
            return
        }

        let frozen = editing && mode == .bumpers
        if !dragging && !frozen {
            let wasMoving = vx != 0 || vy != 0
            let decay = pow(friction, dt)
            vx *= decay
            vy *= decay
            if hypot(vx, vy) < 4 { vx = 0; vy = 0 }

            // Substep, so a quick flick cannot jump a wall or skip a bumper
            // between frames, and contacts are caught shallow — a deep one
            // picks its escape direction badly.
            let reach = hypot(vx, vy) * dt
            let steps = min(32, max(1, Int(ceil(reach / max(ballR() * 0.25, 1)))))
            let hStep = dt / Double(steps)
            for _ in 0..<steps {
                spin += omega * hStep          // turn first, then resolve
                bx += vx * hStep
                by += vy * hStep
                walls()
                if mode == .bumpers { for b in table { bounce(b) } }
            }
            if wasMoving && vx == 0 && vy == 0 { justCameToRest = true }
        } else {
            spin += omega * dt
        }

        if dragging { omega *= pow(0.02, dt) }
        omega *= pow(spinFriction, dt)
        if abs(omega) < 0.02 { omega = 0 }
    }

    /// Walls, against the ball's real outline. Only reverse when the ball is
    /// heading into the wall: a corner that rotates back into contact would
    /// otherwise flip the velocity a second time and pin the ball to the edge.
    public func walls() {
        let r = ballR()
        var lo0: Double, hi0: Double, lo1: Double, hi1: Double
        if let pts = ballPoints() {
            lo0 = Double.greatestFiniteMagnitude; hi0 = -Double.greatestFiniteMagnitude
            lo1 = Double.greatestFiniteMagnitude; hi1 = -Double.greatestFiniteMagnitude
            for p in pts {
                if p.x < lo0 { lo0 = p.x }
                if p.x > hi0 { hi0 = p.x }
                if p.y < lo1 { lo1 = p.y }
                if p.y > hi1 { hi1 = p.y }
            }
        } else {
            lo0 = bx - r; hi0 = bx + r; lo1 = by - r; hi1 = by + r
        }
        if lo0 < 0 { bx -= lo0; if vx < 0 { vx = -vx * restitution; impartSpin(1, 0, fromWall: true) } }
        if hi0 > w { bx -= hi0 - w; if vx > 0 { vx = -vx * restitution; impartSpin(-1, 0, fromWall: true) } }
        if lo1 < 0 { by -= lo1; if vy < 0 { vy = -vy * restitution; impartSpin(0, 1, fromWall: true) } }
        if hi1 > h { by -= hi1 - h; if vy > 0 { vy = -vy * restitution; impartSpin(0, -1, fromWall: true) } }
    }

    /// Reflect the ball off one bumper — any convex shape against any other.
    public func bounce(_ b: Bumper) {
        let r = ballR()
        let bp = ballPoints()
        let parts = bumperParts(b)
        let c = bumperCenter(b)
        let br = bumperRadius(b)

        var hit: Hit?
        if parts.isEmpty {
            if let ball = bp {
                // bumper against ball, then turned around
                hit = Geom.circleVsPoly(c.x, c.y, br, ball).map { Hit(nx: -$0.nx, ny: -$0.ny, depth: $0.depth) }
            } else {
                hit = Geom.circleVsCircle(bx, by, r, c.x, c.y, br)
            }
        } else {
            // A letter is several pieces, and the ball can be inside two of
            // them at once where they meet. The deepest is the one it hit.
            for gp in parts {
                let one = bp == nil ? Geom.circleVsPoly(bx, by, r, gp) : Geom.satPolyPoly(bp!, gp)
                if let one, hit == nil || one.depth > hit!.depth { hit = one }
            }
        }
        guard let h = hit else { return }

        bx += h.nx * h.depth
        by += h.ny * h.depth

        let dot = vx * h.nx + vy * h.ny
        if dot >= 0 { return }
        // The bumper about to be struck names the note; the impact plays it.
        nextStep = bumperStep(b)
        impartSpin(h.nx, h.ny, fromWall: false)

        // A flat side is a flat side: it reflects. A round one is followed
        // when it is barely touched, and reflects when it is hit squarely.
        let curve = isRound(b) ? grip(dot) : 1
        let keep = 1 + (Toy.kick - 1) * curve       // along the surface
        let give = Toy.kick * curve                 // into it
        let nx = dot * h.nx
        let ny = dot * h.ny
        vx = (vx - nx) * keep - nx * give
        vy = (vy - ny) * keep - ny * give
        let sp = hypot(vx, vy)
        if sp > Toy.maxSpeed { vx *= Toy.maxSpeed / sp; vy *= Toy.maxSpeed / sp }
    }

    /// A round bumper, and a round one pulled out of round.
    public func isRound(_ b: Bumper) -> Bool { b.glyph.isEmpty && b.shape == .circle }

    /// How much of a bounce a hit gets, from none at a graze to all of it once
    /// it is square enough. Eased at both ends, because a hard edge between
    /// following the curve and coming off it is a thing you can feel.
    public func grip(_ dot: Double) -> Double {
        let sp = hypot(vx, vy)
        if sp < 1e-4 { return 1 }
        let t = Geom.clamp(abs(dot) / sp / Toy.curveBite, 0, 1)
        return t * t * (3 - 2 * t)
    }

    // MARK: the bottom strip

    public func modeH() -> Double { viewH * 0.062 }
    public func stripH() -> Double { viewH * 0.075 }
    public func chromeH() -> Double { modeH() + stripH() }
    public func modeRowTop() -> Double { h }
    public func stripTop() -> Double { h + modeH() }

    /// The mode row. A phone has no keyboard and a hidden double tap is not a
    /// feature anyone can find, so the five toys are on screen.
    public func modeLabels() -> [String] {
        var labels = ["menu", "ball", "dial", "bumpers", "bolt", "glass", "paint", "ink"]
        if mode == .bumpers { labels.append("edit") }
        if mode == .ball { labels.append("catch") }
        return labels
    }

    public func modeCells() -> [Chip] {
        let labels = modeLabels()
        let cw = w / Double(labels.count)
        return labels.indices.map { Chip(i: $0, x: cw * Double($0), y: modeRowTop(), w: cw, h: modeH()) }
    }

    public func modeHit(_ x: Double, _ y: Double) -> String? {
        if y < modeRowTop() || y > modeRowTop() + modeH() { return nil }
        let labels = modeLabels()
        let i = min(labels.count - 1, max(0, Int((x / w) * Double(labels.count))))
        return labels[i]
    }

    /// The mode a row or menu key names, or nil if it names something else.
    public func modeNamed(_ key: String) -> Mode? {
        switch key {
        case "ball": return .ball
        case "dial": return .dial
        case "bumpers": return .bumpers
        case "bolt": return .bolt
        case "glass": return .glass
        case "paint": return .paint
        default: return nil
        }
    }

    /// One gate for every way into a toy, so none of them can forget it.
    private func openMode(_ m: Mode) {
        if modeLocked(m) { showPaywall() } else { mode = m; editing = false }
    }

    public func tapMode(_ label: String) {
        if let m = modeNamed(label) { openMode(m); return }
        switch label {
        case "menu": screen = .title; drawerOpen = false; editing = false; dragging = false
        case "ink":
            if drawerOpen { closeDrawer() } else { drawerOpen = true }
        case "edit":
            if editLocked() { showPaywall() } else { editing.toggle(); selected = -1 }
        case "catch": mustCatch.toggle()
        default: break
        }
    }

    // MARK: the opening screen

    public struct MenuItem {
        public let key: String
        public let label: String
        public let blurb: String
    }

    /// The front door. Every toy is named here with a line saying what it is,
    /// because none of them announce themselves once you are inside.
    public func menuItems() -> [MenuItem] {
        var items = [
            MenuItem(key: "ball", label: "ball", blurb: "throw it and let it ring"),
            MenuItem(key: "dial", label: "dial", blurb: "a knurled wheel that clicks"),
            MenuItem(key: "bumpers", label: "bumpers", blurb: "a table to bounce through"),
            MenuItem(key: "bolt", label: "lightning", blurb: "a strike that stays etched"),
            MenuItem(key: "glass", label: "glass", blurb: "press it and it breaks"),
            MenuItem(key: "paint", label: "paint", blurb: "a ball that leaves ink"),
            MenuItem(key: "ink", label: "ink & canvas", blurb: "colour, sheerness, ground"),
        ]
        // Named on the front door rather than sprung on you behind a control.
        if !full() {
            items.append(MenuItem(key: "unlock", label: "unlock everything",
                                  blurb: "everything but the ball"))
        }
        return items
    }

    /// Rows the free tier can look at but not use.
    public func menuLocked(_ key: String) -> Bool {
        if let m = modeNamed(key) { return modeLocked(m) }
        return false
    }

    /// Where the wordmark's baseline sits.
    public func titleBaseline() -> Double { viewH * 0.26 }

    /// Rows shrink to fit rather than running off the bottom. Adding
    /// lightning made a seventh row, and at a fixed height seven of them
    /// overflowed a 1080x1920 screen — the sort of thing that is invisible
    /// until someone with a smaller phone cannot reach the last entry.
    public func menuRowH() -> Double {
        let n = Double(menuItems().count)
        let top = titleBaseline() + viewH * 0.075
        let room = max(viewH - insetBottom - top - viewH * 0.02, 1)
        return min(viewH * 0.082, w * 0.16, room / (n + (n - 1) * 0.18))
    }

    public func menuRows() -> [Chip] {
        let items = menuItems()
        let rh = menuRowH()
        let gap = rh * 0.18
        let x = w * 0.11
        let cw = w * 0.78
        let top = titleBaseline() + viewH * 0.075
        return items.indices.map { i in
            Chip(i: i, x: x, y: top + (rh + gap) * Double(i), w: cw, h: rh)
        }
    }

    public func menuHit(_ px: Double, _ py: Double) -> String? {
        let items = menuItems()
        for c in menuRows() where px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h {
            return items[c.i].key
        }
        return nil
    }

    /// Returns true if the tap opened something.
    @discardableResult
    public func tapMenu(_ key: String) -> Bool {
        if let m = modeNamed(key) {
            if modeLocked(m) { showPaywall() } else { mode = m; screen = .play }
            editing = false
            return true
        }
        switch key {
        case "ink": screen = .play; drawerOpen = true
        case "unlock": showPaywall()
        default: return false
        }
        editing = false
        return true
    }

    // MARK: the paywall

    public func paywallLines() -> [String] {
        [
            "the dial, the bumper table and lightning",
            "paint — the ball leaves ink wherever it goes",
            "arrange the table, and colour every bumper",
            "all fifty-six inks and all seven canvases",
            "no account, no ads, nothing collected",
        ]
    }

    /// The terms, on the paywall itself. Not decoration: Apple's guideline
    /// 3.1.2 wants the length of the subscription, what it costs per period,
    /// and that it renews, on the screen that sells it — and rejects the ones
    /// that leave it to the store sheet.
    public func subscriptionTerms() -> [String] {
        ["\(priceText ?? Toy.priceFallback) per month, renews until cancelled",
         "cancel any time in your store account"]
    }

    /// unlock · restore · not now, stacked.
    /// subscribe · restore · not now, stacked.
    public var paywallLabels: [String] { Toy.paywallLabelsBase }

    // MARK: the code

    /// True while the keypad is up instead of the buttons.
    public var codeOpen = false

    /// What has been typed so far. Never longer than the code itself.
    public private(set) var codeEntry = ""

    /// Set for a moment when a wrong code is entered, so the view can say so.
    public private(set) var codeWrong = false

    public func openCode() { codeOpen = true; codeEntry = ""; codeWrong = false }
    public func closeCode() { codeOpen = false; codeEntry = ""; codeWrong = false }

    /// A digit, or "del". Returns true if that was the digit that unlocked it.
    /// Checked on the last digit rather than on a button, because a keypad
    /// with an enter key on it is one more thing to explain.
    @discardableResult
    public func typeCode(_ key: String) -> Bool {
        codeWrong = false
        if key == "del" {
            if !codeEntry.isEmpty { codeEntry.removeLast() }
            return false
        }
        if codeEntry.count >= Toy.codeLength { return false }
        codeEntry += key
        if codeEntry.count < Toy.codeLength { return false }
        if Toy.codeHash(codeEntry) == Toy.codeHashValue {
            closeCode()
            unlock()
            return true
        }
        codeWrong = true
        codeEntry = ""
        return false
    }

    /// 1-9, then a blank, 0, and delete: a phone keypad, minus the letters.
    public func keypadKeys() -> [String] {
        ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del"]
    }

    public func keypadCells() -> [Chip] {
        let cw = min(w * 0.74, min(w, viewH) * 0.9)
        let gap = cw * 0.04
        let bw = (cw - gap * 2) / 3
        let bh = min(bw * 0.62, viewH * 0.082)
        let x = (w - cw) / 2
        let top = viewH - insetBottom - bh * 0.5 - 4 * bh - 3 * gap
        return keypadKeys().indices.map { i in
            Chip(i: i, x: x + (bw + gap) * Double(i % 3),
                 y: top + (bh + gap) * Double(i / 3), w: bw, h: bh)
        }
    }

    public func keypadHit(_ px: Double, _ py: Double) -> String? {
        let keys = keypadKeys()
        for c in keypadCells()
        where !keys[c.i].isEmpty && px >= c.x && px <= c.x + c.w
            && py >= c.y && py <= c.y + c.h {
            return keys[c.i]
        }
        return nil
    }

    /// Where the "have a code?" row sits, under the buttons.
    public func codePromptCell() -> Chip {
        let b = paywallButtons()[0]
        let h = min(viewH * 0.05, b.h * 0.7)
        return Chip(i: 0, x: b.x, y: b.y - h - h * 0.35, w: b.w, h: h)
    }

    public func paywallButtons() -> [Chip] {
        let bh = min(viewH * 0.072, w * 0.145)
        let gap = bh * 0.22
        let cw = min(w * 0.74, min(w, viewH) * 0.9)
        let x = (w - cw) / 2
        let floorY = viewH - insetBottom - bh * 0.5
        let n = Double(paywallLabels.count)
        let top = floorY - n * bh - (n - 1) * gap
        return paywallLabels.indices.map { i in
            Chip(i: i, x: x, y: top + (bh + gap) * Double(i), w: cw, h: bh)
        }
    }

    public func paywallHit(_ px: Double, _ py: Double) -> String? {
        for c in paywallButtons() where px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h {
            return paywallLabels[c.i]
        }
        return nil
    }

    /// What the unlock button reads, once the store has said.
    public func unlockLabel() -> String {
        "subscribe  ·  \(priceText ?? Toy.priceFallback)/mo"
    }

    public func inStrip(_ y: Double) -> Bool { y >= stripTop() && y <= stripTop() + stripH() }

    public struct Zone {
        public let kind: String
        public let x0: Double
        public let x1: Double
        public let count: Int
    }

    /// Lightning has no ball in it, so the sizes and the shapes are controls
    /// for nothing there — but the colour is the whole point of the toy, since
    /// every strike etches in whatever ink it was thrown with. It gets the
    /// full width for the palette instead.
    public func stripZones() -> [Zone] {
        if mode == .bolt || mode == .glass {
            return [Zone(kind: "color", x0: 0, x1: w, count: Palette.names.count)]
        }
        return [
            Zone(kind: "color", x0: 0, x1: w * 0.46, count: Palette.names.count),
            Zone(kind: "size", x0: w * 0.46, x1: w * 0.73, count: Toy.sizes.count),
            Zone(kind: "shape", x0: w * 0.73, x1: w, count: Shape.allCases.count),
        ]
    }

    /// Returns true if the tap was consumed.
    @discardableResult
    public func stripTap(_ x: Double) -> Bool {
        for z in stripZones() {
            if x < z.x0 || x > z.x1 { continue }
            let i = min(z.count - 1, max(0, Int(((x - z.x0) / (z.x1 - z.x0)) * Double(z.count))))
            switch z.kind {
            case "color":
                if familyLocked(i) { showPaywall() }
                else if i == inkFamily { drawerOpen = true }
                else { inkFamily = i }
            case "size": sizeIndex = i
            default: shape = Shape.allCases[i]; spin = 0
            }
            return true
        }
        return false
    }

    // MARK: the palette drawer

    public var drawerOpen = false

    /// Whose colour the grid is setting. Editing a bumper and tapping its
    /// swatch points the whole palette at that bumper; everything else in the
    /// drawer stays global.
    public enum Target { case ink, bumper }
    public var drawerTarget = Target.ink

    public func closeDrawer() {
        drawerOpen = false
        drawerTarget = .ink
    }

    /// The index of the bumper the drawer is currently painting, if any.
    /// What the drawer says it is painting, and which cell it lights. A bumper
    /// that follows the ink lights the ink's own cell at the bumper's tone, so
    /// the grid always shows the colour you are actually looking at on screen.
    public func drawerHeading() -> String {
        guard let i = targetBumperIndex() else { return "INK  ·  \(Palette.names[inkFamily])" }
        if table[i].family == Toy.followInk { return "BUMPER  ·  INK" }
        return "BUMPER  ·  \(Palette.names[table[i].family])"
    }

    public func drawerFamily() -> Int {
        guard let i = targetBumperIndex() else { return inkFamily }
        return table[i].family == Toy.followInk ? inkFamily : table[i].family
    }

    public func drawerTone() -> Int {
        guard let i = targetBumperIndex() else { return inkTone }
        return table[i].tone
    }

    public func targetBumperIndex() -> Int? {
        guard drawerTarget == .bumper, selected >= 0, selected < table.count else { return nil }
        return selected
    }

    public struct Box {
        public let x: Double, y: Double, w: Double, h: Double
        public let cell: Double, gx: Double, gy: Double, gridW: Double, gridH: Double
        public let ay: Double, ky: Double, sy: Double, hy: Double, vy: Double
        public let rowH: Double
    }

    /// Rows below the colour grid, in the order they are drawn.
    public let drawerRows = ["alpha", "canvas", "scrim", "haptic", "sound"]

    public func drawerRowCount(_ kind: String) -> Int {
        switch kind {
        case "alpha": return Palette.alphas.count
        case "canvas": return Palette.canvasNames.count
        case "scrim": return Palette.scrims.count
        case "sound": return Palette.voiceNames.count
        default: return Palette.hapticNames.count
        }
    }

    public func drawerBox() -> Box {
        let cols = Double(Palette.names.count)
        let rows = Double(Palette.toneMix.count)
        let bw = min(w * 0.94, min(w, h) * 1.1)
        let pad = min(w, h) * 0.035
        let label = pad * 0.75
        let rowH = min(h * 0.055, pad * 1.6)
        let gap = pad * 0.5
        let cell = min((bw - pad * 2) / cols, min(w, h) * 0.085)
        let gridW = cell * cols
        let gridH = cell * rows
        let bh = pad + label + gridH + Double(drawerRows.count) * (gap + label + rowH) + pad
        let x = (w - bw) / 2
        let y = Geom.clamp(h - bh - pad * 0.5, 0, viewH)
        let gx = x + (bw - gridW) / 2
        let gy = y + pad + label
        let ay = gy + gridH + gap + label
        let ky = ay + rowH + gap + label
        let sy = ky + rowH + gap + label
        let hy = sy + rowH + gap + label
        let vy = hy + rowH + gap + label
        return Box(x: x, y: y, w: bw, h: bh, cell: cell, gx: gx, gy: gy,
                   gridW: gridW, gridH: gridH, ay: ay, ky: ky, sy: sy, hy: hy, vy: vy,
                   rowH: rowH)
    }

    public func drawerRowY(_ b: Box, _ kind: String) -> Double {
        switch kind {
        case "alpha": return b.ay
        case "canvas": return b.ky
        case "scrim": return b.sy
        case "haptic": return b.hy
        default: return b.vy
        }
    }

    public struct Chip {
        public let i: Int
        public let x: Double, y: Double, w: Double, h: Double
    }

    public func drawerChips(_ y: Double, _ n: Int, _ b: Box) -> [Chip] {
        let step = b.gridW / Double(n)
        let cw = min(step - 6, min(w, h) * 0.16)
        return (0..<n).map { i in
            Chip(i: i, x: b.gx + step * Double(i) + (step - cw) / 2, y: y, w: cw, h: b.rowH)
        }
    }

    public func drawerHit(_ px: Double, _ py: Double) -> String {
        let b = drawerBox()
        if px < b.x || px > b.x + b.w || py < b.y || py > b.y + b.h { return "outside" }
        if px >= b.gx && px <= b.gx + b.gridW && py >= b.gy && py <= b.gy + b.gridH {
            let family = min(Palette.names.count - 1, max(0, Int((px - b.gx) / b.cell)))
            let tone = min(Palette.toneMix.count - 1, max(0, Int((py - b.gy) / b.cell)))
            if familyLocked(family) { showPaywall(); return "locked" }
            if let idx = targetBumperIndex() {
                // Tapping the colour it is already wearing hands it back to
                // the ink — the same repeat-tap the strip uses to open this
                // drawer, and the only way back to following without a button
                // there is nowhere to put.
                if table[idx].family == family && table[idx].tone == tone {
                    table[idx].family = Toy.followInk
                    return "follow"
                }
                table[idx].family = family
                table[idx].tone = tone
                return "bumper"
            }
            inkFamily = family
            inkTone = tone
            return "ink"
        }
        for kind in drawerRows {
            for c in drawerChips(drawerRowY(b, kind), drawerRowCount(kind), b) {
                if px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h { continue }
                if kind == "canvas" && canvasLocked(c.i) { showPaywall(); return "locked" }
                switch kind {
                case "alpha": inkAlphaIndex = c.i
                case "canvas": canvasIndex = c.i
                case "scrim": scrimIndex = c.i
                case "haptic": hapticIndex = c.i
                default: voiceIndex = c.i
                }
                return kind
            }
        }
        return "panel"
    }

    // MARK: the edit toolbar

    public let toolbarLabels = ["add", "shape", "turn", "−", "+", "ink", "del", "reset", "done"]

    public func toolbarButtons() -> [Chip] {
        let n = Double(toolbarLabels.count)
        let pad = min(w, h) * 0.02
        let bw = (w - pad * 2) / n
        let bh = min(h * 0.06, min(w, h) * 0.11)
        return toolbarLabels.indices.map { i in
            Chip(i: i, x: pad + bw * Double(i), y: toolbarTop(), w: bw, h: bh)
        }
    }

    /// Clear of the status bar, whatever the phone puts up there.
    public func toolbarTop() -> Double { insetTop + min(w, h) * 0.02 }

    public func toolbarHit(_ px: Double, _ py: Double) -> String? {
        for c in toolbarButtons() where px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h {
            return toolbarLabels[c.i]
        }
        return nil
    }

    public func doToolbar(_ label: String) {
        let has = selected >= 0 && selected < table.count
        switch label {
        case "add":
            table.append(Bumper(nx: 0.5, ny: 0.4, size: 0.06, shape: .circle, rot: 0))
            selected = table.count - 1
        // The whole set at once. It was a step-forward button and the letters
        // may as well not have existed; see the glyph sheet.
        case "shape":
            if has {
                glyphOpen.toggle()
                if glyphOpen { closeDrawer() }
            }
        case "turn":
            if has { table[selected].rot += Double.pi / 12 }
        // Cycling fourteen families one tap at a time is no way to pick a colour
        // when the whole palette already exists.
        case "ink":
            if has { drawerOpen = true; drawerTarget = .bumper; closeGlyphs() }
        case "−":
            if has { table[selected].size = Geom.clamp(table[selected].size * 0.88, Toy.minBumper, Toy.maxBumper) }
        case "+":
            if has { table[selected].size = Geom.clamp(table[selected].size * 1.14, Toy.minBumper, Toy.maxBumper) }
        // The drawer may be pointed at the bumper that is going away.
        case "del":
            if has { table.remove(at: selected); selected = -1; closeDrawer(); closeGlyphs() }
        case "reset":
            table = Toy.defaultTable(); selected = -1; closeDrawer(); closeGlyphs()
        case "done":
            editing = false; selected = -1; closeDrawer(); closeGlyphs()
        default: break
        }
    }

    /// The two handles, in the bumper's own frame. The red one sits on the
    /// corner of the stretched box and pulls both axes at once, so making a
    /// bar long and thin needs no third handle and no modifier key — neither
    /// of which a phone has anywhere to put.
    public func handles(_ b: Bumper) -> (pull: Pt, rotate: Pt) {
        let c = bumperCenter(b)
        let r = bumperRadius(b)
        let co = cos(b.rot)
        let si = sin(b.rot)
        let hx = r * b.sx
        let hy = r * b.sy
        let reach = max(hx, hy) + min(w, h) * 0.06
        return (Pt(c.x + hx * co - hy * si, c.y + hx * si + hy * co),
                Pt(c.x - reach * si, c.y + reach * co))
    }

    /// Pull a bumper to a point: the drag is taken into the bumper's own
    /// frame, so the axis you pull along is the axis that grows however far
    /// round the thing has been turned.
    public func pullTo(_ i: Int, _ px: Double, _ py: Double) {
        guard i >= 0 && i < table.count else { return }
        let b = table[i]
        let r = max(b.size * min(w, h), 1e-4)
        let co = cos(-b.rot)
        let si = sin(-b.rot)
        let dx = px - b.nx * w
        let dy = py - b.ny * h
        table[i].sx = Geom.clamp(abs(dx * co - dy * si) / r, Toy.minStretch, Toy.maxStretch)
        table[i].sy = Geom.clamp(abs(dx * si + dy * co) / r, Toy.minStretch, Toy.maxStretch)
    }

    // ---- the glyph sheet -------------------------------------------------

    /// Everything a bumper can be cut into, laid out at once.
    ///
    /// This used to be one button that stepped forward: six outlines, then A,
    /// then B, and so on. Thirty-two taps to get back where you started, and
    /// — worse — nothing on screen ever said the letters were there at all,
    /// so for most people they were not. The palette had exactly this problem
    /// and was fixed by showing the whole thing; this is the same fix, and it
    /// is what makes the digits worth adding rather than ten more taps to
    /// bury.
    public var glyphOpen = false

    public let glyphCols = 7

    /// Six outlines, then A to Z, then 0 to 9.
    public func glyphCount() -> Int { Shape.allCases.count + Letters.glyphs.count }

    public func glyphShapeAt(_ i: Int) -> Shape {
        i < Shape.allCases.count ? Shape.allCases[i] : .circle
    }

    public func glyphTextAt(_ i: Int) -> String {
        if i < Shape.allCases.count { return "" }
        return String(Array(Letters.glyphs)[i - Shape.allCases.count])
    }

    /// Which cell a bumper is wearing, so the sheet can mark it.
    public func glyphIndexOf(_ b: Bumper) -> Int {
        if b.glyph.isEmpty { return Shape.allCases.firstIndex(of: b.shape) ?? 0 }
        let all = Array(Letters.glyphs)
        return Shape.allCases.count + (all.firstIndex(of: Character(b.glyph)) ?? 0)
    }

    public struct Sheet {
        public let x: Double, y: Double, w: Double, h: Double
        public let cell: Double, gx: Double, gy: Double
    }

    public func glyphSheet() -> Sheet {
        let cols = Double(glyphCols)
        let rows = Double((glyphCount() + glyphCols - 1) / glyphCols)
        let pad = min(w, h) * 0.035
        let label = pad * 0.75
        let bw = min(w * 0.94, min(w, h) * 1.1)
        let cell = min((bw - pad * 2) / cols, min(w, h) * 0.115)
        let bh = pad + label + cell * rows + pad
        let x = (w - bw) / 2
        let y = Geom.clamp(h - bh - pad * 0.5, 0, viewH)
        return Sheet(x: x, y: y, w: bw, h: bh, cell: cell,
                     gx: x + (bw - cell * cols) / 2, gy: y + pad + label)
    }

    public func glyphCells() -> [Chip] {
        let s = glyphSheet()
        return (0..<glyphCount()).map { i in
            Chip(i: i,
                 x: s.gx + s.cell * Double(i % glyphCols),
                 y: s.gy + s.cell * Double(i / glyphCols),
                 w: s.cell, h: s.cell)
        }
    }

    /// A cell drawn as the bumper it would make, so the sheet is a preview and
    /// not a legend: the same outline code, the same ink, the same everything
    /// but where it sits.
    public func glyphSample(_ i: Int, _ cx: Double, _ cy: Double, _ r: Double) -> Bumper {
        let sel = selected >= 0 && selected < table.count ? table[selected] : nil
        return Bumper(nx: cx / w, ny: cy / h, size: r / min(w, h),
                      shape: glyphShapeAt(i), rot: 0,
                      family: sel?.family ?? Toy.followInk, tone: sel?.tone ?? 2,
                      glyph: glyphTextAt(i))
    }

    public func closeGlyphs() { glyphOpen = false }

    public func glyphHit(_ px: Double, _ py: Double) -> String {
        let s = glyphSheet()
        if px < s.x || px > s.x + s.w || py < s.y || py > s.y + s.h { return "outside" }
        guard selected >= 0 && selected < table.count else { return "panel" }
        for c in glyphCells() {
            if px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h { continue }
            // A glyph covers whatever outline is under it, so leave that
            // alone: pick B over a hexagon, then pick the hexagon back.
            if c.i < Shape.allCases.count {
                table[selected].shape = Shape.allCases[c.i]
                table[selected].glyph = ""
            } else {
                table[selected].glyph = glyphTextAt(c.i)
            }
            return "pick"
        }
        return "panel"
    }

    /// Cycles past anything locked. Landing on a paywall because you
    /// double-tapped mid-fidget would be an ambush; the free tier simply has
    /// one toy in its rotation, so the gesture leaves it on the ball.
    public func cycleMode() {
        editing = false
        var next = mode
        let all = Mode.allCases
        for _ in 0..<all.count {
            next = all[(all.firstIndex(of: next)! + 1) % all.count]
            if !modeLocked(next) { mode = next; return }
        }
    }
}
