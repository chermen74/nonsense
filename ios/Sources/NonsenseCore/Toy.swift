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

public enum Mode: CaseIterable { case ball, dial, bumpers, bolt, paint }

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
    /// Its own ink, from the same thirty-six as everything else.
    public var family: Int
    public var tone: Int

    public init(nx: Double, ny: Double, size: Double, shape: Shape, rot: Double,
                family: Int = 0, tone: Int = 2) {
        self.nx = nx; self.ny = ny; self.size = size
        self.shape = shape; self.rot = rot
        self.family = family; self.tone = tone
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

/// A bolt that has arrived. The path stays on the scene until it is cleared.
/// It keeps its generation because the bolt your finger threw is drawn a shade
/// heavier than the forks that came off it.
public struct Etched {
    public let nodes: [Pt]
    public let argb: UInt32
    public let gen: Int
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
    public static let names = [
        "graphite", "bone", "oxblood", "rust", "ochre", "moss", "teal", "slate", "plum",
    ]
    private static let bases: [UInt32] = [
        0x3a3a3c, 0xc9c0ab, 0x702929, 0x9c5b3c, 0xb08940,
        0x5c6e4a, 0x3f6b68, 0x465a78, 0x5c3f5e,
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
}

// MARK: - Toy

public final class Toy {

    /// Eight steps, a bead through to a grapefruit.
    public static let sizes: [Double] = [0.12, 0.2, 0.3, 0.5, 0.7, 1.0, 1.45, 2.1]
    public static let defaultSize = 5                 // 1.0
    public static let kick = 1.06
    public static let maxSpeed = 6000.0
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
    public static let boltBranch = 0.17
    public static let boltBranchSpread = 0.62

    /// A fork is slower than what threw it, and can fork once itself.
    public static let boltBranchSpeed = 0.74
    public static let boltMaxGen = 2

    /// Etchings kept before the oldest is rubbed out.
    public static let maxEtched = 120

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
    public static func boltWeight(_ gen: Int) -> Double { 1 - 0.22 * Double(gen) }

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

    public static func defaultTable() -> [Bumper] {
        [
            Bumper(nx: 0.25, ny: 0.30, size: 0.055, shape: .circle, rot: 0, family: 2),  // oxblood
            Bumper(nx: 0.75, ny: 0.30, size: 0.055, shape: .circle, rot: 0, family: 7),  // slate
            Bumper(nx: 0.50, ny: 0.50, size: 0.068, shape: .hexagon, rot: 0, family: 4), // ochre
            Bumper(nx: 0.25, ny: 0.72, size: 0.055, shape: .bar, rot: 0, family: 5),     // moss
            Bumper(nx: 0.75, ny: 0.72, size: 0.055, shape: .bar, rot: 0, family: 6),     // teal
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

    public var mode = Mode.ball
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

    public func inkColor() -> UInt32 { Palette.colors[inkFamily][inkTone] }

    /// True while nothing is painted underneath.
    public func sheer() -> Bool { canvasIndex == 0 }

    /// The ground colour, opaque. Meaningless while `sheer()`.
    public func canvasColor() -> UInt32 { Palette.canvasColors[canvasIndex] }

    public func inkAlpha() -> Double { Palette.alphas[inkAlphaIndex] }
    public func scrim() -> Double { Palette.scrims[scrimIndex] }
    public func hapticScale() -> Double { Palette.hapticScales[hapticIndex] }

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
    public func resize(_ newW: Double, _ newViewH: Double, _ newInsetBottom: Double = 0) {
        if newW <= 0 || newViewH <= 0 { return }
        w = newW
        viewH = newViewH
        insetBottom = max(0, newInsetBottom)
        h = max(1, viewH - insetBottom - chromeH())
        // Derived sizes follow the field. Computing them once meant a view
        // that was measured at zero produced a radius of zero.
        baseR = min(w, h) * 0.05
        dialR = min(w, h) * 0.22
        if !placed { bx = w / 2; by = h / 2; placed = true }
    }

    public func bumperPoints(_ b: Bumper) -> [Pt]? {
        let m = min(w, h)
        return Outlines.points(b.shape, b.nx * w, b.ny * h, b.size * m, b.rot)
    }

    public func bumperColor(_ b: Bumper) -> UInt32 { Palette.colors[b.family][b.tone] }
    public func bumperCenter(_ b: Bumper) -> Pt { Pt(b.nx * w, b.ny * h) }
    public func bumperRadius(_ b: Bumper) -> Double { b.size * min(w, h) }

    public func encodeTable() -> String {
        table.map { "\($0.nx),\($0.ny),\($0.size),\(name(of: $0.shape)),\($0.rot),\($0.family),\($0.tone)" }
            .joined(separator: ";")
    }

    /// Rows written before bumpers had a colour of their own have five fields
    /// rather than seven. They still load, in graphite — a saved table is
    /// someone's arrangement and is not worth throwing away over a new field.
    public func decodeTable(_ raw: String) -> [Bumper] {
        raw.split(separator: ";").compactMap { row -> Bumper? in
            let f = row.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard f.count == 5 || f.count == 7 else { return nil }
            guard let nx = Double(f[0]), let ny = Double(f[1]),
                  let size = Double(f[2]), let shape = shape(named: f[3]),
                  let rot = Double(f[4]) else { return nil }
            var family = 0
            var tone = 2
            if f.count == 7 {
                guard let fa = Int(f[5]), let to = Int(f[6]) else { return nil }
                family = min(max(fa, 0), Palette.names.count - 1)
                tone = min(max(to, 0), Palette.toneMix.count - 1)
            }
            return Bumper(nx: Geom.clamp(nx, 0, 1), ny: Geom.clamp(ny, 0, 1),
                          size: Geom.clamp(size, Toy.minBumper, Toy.maxBumper),
                          shape: shape, rot: rot, family: family, tone: tone)
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
        guard let pts = bumperPoints(b) else {
            return hypot(px - b.nx * w, py - b.ny * h) <= bumperRadius(b)
        }
        return Geom.pointInPoly(px, py, pts)
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
    private func registerImpact(_ speed: Double, fromWall: Bool) {
        bounceCount += 1
        lastImpact = speed
        lastImpactWall = fromWall
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
        boltSeed = Toy.nextRand(boltSeed)
        // The ink is read at the strike, not at the drawing, so an etching
        // keeps the colour it was thrown in however you change the palette
        // afterwards.
        bolts.append(Bolt(x: px, y: py, vx: bvx, vy: bvy, rng: boltSeed,
                          argb: inkColor(), gen: 0))
        while bolts.count > Toy.maxBolts { bolts.removeFirst() }
        return true
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
        bolts.append(Bolt(x: b.x, y: b.y,
                          vx: (b.vx * c - b.vy * si) * f,
                          vy: (b.vx * si + b.vy * c) * f,
                          rng: Toy.nextRand(b.rng), argb: b.argb, gen: b.gen + 1))
    }

    /// Everything that has arrived, wiped.
    public func clearEtched() { etched.removeAll() }

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
                b.sinceNode += hypot(b.vx, b.vy) * hStep

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
                } else if b.sinceNode >= node {
                    addNode(b, node, exact: false)
                    b.rng = Toy.nextRand(b.rng)
                    if Toy.rand01(b.rng) < Toy.boltBranch { branch(b) }
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
        let gp = bumperPoints(b)
        let c = bumperCenter(b)
        let br = bumperRadius(b)

        var hit: Hit?
        switch (bp, gp) {
        case (nil, nil):
            hit = Geom.circleVsCircle(bx, by, r, c.x, c.y, br)
        case (nil, .some(let g)):
            hit = Geom.circleVsPoly(bx, by, r, g)
        case (.some(let ball), nil):
            // bumper against ball, then turned around
            hit = Geom.circleVsPoly(c.x, c.y, br, ball).map { Hit(nx: -$0.nx, ny: -$0.ny, depth: $0.depth) }
        case (.some(let ball), .some(let g)):
            hit = Geom.satPolyPoly(ball, g)
        }
        guard let h = hit else { return }

        bx += h.nx * h.depth
        by += h.ny * h.depth

        let dot = vx * h.nx + vy * h.ny
        if dot >= 0 { return }
        impartSpin(h.nx, h.ny, fromWall: false)
        vx = (vx - 2 * dot * h.nx) * Toy.kick
        vy = (vy - 2 * dot * h.ny) * Toy.kick
        let sp = hypot(vx, vy)
        if sp > Toy.maxSpeed { vx *= Toy.maxSpeed / sp; vy *= Toy.maxSpeed / sp }
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
        var labels = ["menu", "ball", "dial", "bumpers", "bolt", "paint", "ink"]
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
            "all thirty-six inks and all seven canvases",
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
        if mode == .bolt {
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
    public func targetBumperIndex() -> Int? {
        guard drawerTarget == .bumper, selected >= 0, selected < table.count else { return nil }
        return selected
    }

    public struct Box {
        public let x: Double, y: Double, w: Double, h: Double
        public let cell: Double, gx: Double, gy: Double, gridW: Double, gridH: Double
        public let ay: Double, ky: Double, sy: Double, hy: Double, rowH: Double
    }

    /// Rows below the colour grid, in the order they are drawn.
    public let drawerRows = ["alpha", "canvas", "scrim", "haptic"]

    public func drawerRowCount(_ kind: String) -> Int {
        switch kind {
        case "alpha": return Palette.alphas.count
        case "canvas": return Palette.canvasNames.count
        case "scrim": return Palette.scrims.count
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
        return Box(x: x, y: y, w: bw, h: bh, cell: cell, gx: gx, gy: gy,
                   gridW: gridW, gridH: gridH, ay: ay, ky: ky, sy: sy, hy: hy, rowH: rowH)
    }

    public func drawerRowY(_ b: Box, _ kind: String) -> Double {
        switch kind {
        case "alpha": return b.ay
        case "canvas": return b.ky
        case "scrim": return b.sy
        default: return b.hy
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
                default: hapticIndex = c.i
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
            Chip(i: i, x: pad + bw * Double(i), y: pad, w: bw, h: bh)
        }
    }

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
        case "shape":
            if has {
                let all = Shape.allCases
                let next = (all.firstIndex(of: table[selected].shape)! + 1) % all.count
                table[selected].shape = all[next]
            }
        case "turn":
            if has { table[selected].rot += Double.pi / 12 }
        // Cycling nine families one tap at a time is no way to pick a colour
        // when the whole palette already exists.
        case "ink":
            if has { drawerOpen = true; drawerTarget = .bumper }
        case "−":
            if has { table[selected].size = Geom.clamp(table[selected].size * 0.88, Toy.minBumper, Toy.maxBumper) }
        case "+":
            if has { table[selected].size = Geom.clamp(table[selected].size * 1.14, Toy.minBumper, Toy.maxBumper) }
        // The drawer may be pointed at the bumper that is going away.
        case "del":
            if has { table.remove(at: selected); selected = -1; closeDrawer() }
        case "reset":
            table = Toy.defaultTable(); selected = -1; closeDrawer()
        case "done":
            editing = false; selected = -1; closeDrawer()
        default: break
        }
    }

    /// Handles on the selected bumper: resize, and rotate.
    public func handles(_ b: Bumper) -> (resize: Pt, rotate: Pt) {
        let c = bumperCenter(b)
        let r = bumperRadius(b)
        let co = cos(b.rot)
        let si = sin(b.rot)
        let reach = r + min(w, h) * 0.06
        return (Pt(c.x + r * co, c.y + r * si),
                Pt(c.x - reach * si, c.y + reach * co))
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
