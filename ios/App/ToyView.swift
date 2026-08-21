import SwiftUI
import UIKit
import NonsenseCore

// MARK: - small helpers

/// SwiftUI has a `Shape` protocol of its own, and an unqualified `Shape` in a
/// file that imports both is ambiguous rather than merely shadowed. The toy's
/// enum keeps its name — it matches the Kotlin, and tools/parity.py compares
/// the two by name — so it is the reference here that has to be explicit.
private typealias ToyShape = NonsenseCore.Shape

extension Color {
    /// The palette stores opaque ARGB, the same as the Kotlin does, so the two
    /// can be compared literal for literal by tools/parity.py.
    init(argb: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xff) / 255,
                  green: Double((argb >> 8) & 0xff) / 255,
                  blue: Double(argb & 0xff) / 255,
                  opacity: alpha)
    }
}

private func path(_ pts: [Pt]) -> Path {
    var p = Path()
    guard let first = pts.first else { return p }
    p.move(to: CGPoint(x: first.x, y: first.y))
    for q in pts.dropFirst() { p.addLine(to: CGPoint(x: q.x, y: q.y)) }
    p.closeSubpath()
    return p
}

private func outline(_ shape: ToyShape, _ cx: Double, _ cy: Double, _ r: Double, _ rot: Double) -> Path {
    if let pts = Outlines.points(shape, cx, cy, r, rot) { return path(pts) }
    return Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
}

/// A settled stroke. Translucent ink has to be composited once per stroke, or
/// every round cap overlaps the last and re-darkens it — a 15% trail comes out
/// solid and beaded. On Android that needed a second bitmap; here one `Path`
/// stroked once at its alpha does it for free, which is the whole reason the
/// trail is stored this way rather than as line segments.
private struct Stroke {
    var path: Path
    var argb: UInt32
    var alpha: Double
    var width: Double
}

// MARK: - the view

struct ToyView: View {
    @StateObject private var store = Store()
    @State private var toy = Toy()
    @State private var haptics = Haptics()

    @State private var strokes: [Stroke] = []
    @State private var live = Path()
    @State private var liveStarted = false
    @State private var lastInk = Pt(0, 0)

    @State private var lastFrame: Date?
    @State private var lastBounce = 0
    @State private var lastDetent = 0
    @State private var lastDetentAt: Date = .distantPast
    @State private var dialLastSample: Date?
    @State private var dragSamples: [(p: CGPoint, t: Date)] = []
    @State private var editDrag: String?
    @State private var previewing = false
    @State private var grabD = CGSize.zero

    /// Enough for a long session; the oldest go first so a runaway painting
    /// cannot grow without limit.
    private let maxStrokes = 600

    /// A GeometryReader that ignores the safe area reports its insets as
    /// zero — which is precisely how the Android build once came to draw its
    /// controls underneath the navigation bar, where the system ate every
    /// tap. The canvas does want the whole screen, so the inset has to come
    /// from the window rather than from the geometry.
    private var bottomInset: Double {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        return Double(window?.safeAreaInsets.bottom ?? 0)
    }

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation) { timeline in
                Canvas { ctx, size in
                    tick(size: size, inset: bottomInset, now: timeline.date)
                    draw(ctx, size)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { onDrag($0) }
                    .onEnded { onEnd($0) }
            )
        }
        .ignoresSafeArea()
        .background(Color(argb: 0xff16181c))
        .statusBarHidden()
        .task {
            load()
            haptics.prepare()
            await store.start()
        }
        .onChange(of: store.tier) { _ in applyTier() }
        .onChange(of: store.price) { _ in toy.priceText = store.price }
    }

    // MARK: frame

    private func tick(size: CGSize, inset: Double, now: Date) {
        toy.resize(size.width, size.height, inset)
        let dt = min(0.05, max(0, now.timeIntervalSince(lastFrame ?? now)))
        lastFrame = now
        toy.step(dt)

        if toy.painting() { layTrail() }
        if toy.justCameToRest { settleStroke() }

        if toy.bounceCount != lastBounce {
            haptics.knock(toy.impactStrength() * toy.hapticScale(), sharp: toy.lastImpactWall)
            lastBounce = toy.bounceCount
        }
        if toy.dialDetent != lastDetent {
            // The actuator cannot keep up with a fast wheel; dropping the
            // clicks that arrive too close together keeps the rhythm instead
            // of smearing them into one hum.
            if now.timeIntervalSince(lastDetentAt) >= 0.026 {
                haptics.tick((0.95 - 0.55 * toy.dialSpeedFraction()) * toy.hapticScale())
                lastDetentAt = now
            }
            lastDetent = toy.dialDetent
        }
    }

    private func layTrail() {
        if !liveStarted {
            lastInk = Pt(toy.bx, toy.by)
            live = Path()
            live.move(to: CGPoint(x: toy.bx, y: toy.by))
            liveStarted = true
            return
        }
        if toy.bx == lastInk.x && toy.by == lastInk.y { return }
        live.addLine(to: CGPoint(x: toy.bx, y: toy.by))
        lastInk = Pt(toy.bx, toy.by)
    }

    private func settleStroke() {
        guard liveStarted, !live.isEmpty else { return }
        strokes.append(Stroke(path: live, argb: toy.inkColor(),
                              alpha: toy.inkAlpha(), width: toy.inkWidth()))
        if strokes.count > maxStrokes { strokes.removeFirst(strokes.count - maxStrokes) }
        live = Path()
        liveStarted = false
    }

    private func clearTrail() {
        strokes.removeAll()
        live = Path()
        liveStarted = false
    }

    // MARK: input

    private func onDrag(_ g: DragGesture.Value) {
        let p = g.location
        if dragSamples.isEmpty { onDown(p) }
        dragSamples.append((p, Date()))
        while dragSamples.count > 2, Date().timeIntervalSince(dragSamples[0].t) > 0.1 {
            dragSamples.removeFirst()
        }
        onMove(p)
    }

    private func onDown(_ p: CGPoint) {
        let x = Double(p.x), y = Double(p.y)

        switch toy.screen {
        case .paywall:
            switch toy.paywallHit(x, y) {
            case "unlock": haptics.tick(0.5 * toy.hapticScale()); Task { await store.buy() }
            case "restore": haptics.tick(0.5 * toy.hapticScale()); Task { await store.refresh() }
            case "not now": haptics.tick(0.5 * toy.hapticScale()); toy.dismissPaywall()
            default: break
            }
            return

        case .title:
            if let key = toy.menuHit(x, y) {
                toy.tapMenu(key)
                liveStarted = false
                haptics.tick(0.5 * toy.hapticScale())
                save()
            }
            return

        case .play:
            break
        }

        if toy.drawerOpen {
            let before = toy.inkFamily
            switch toy.drawerHit(x, y) {
            case "outside": toy.closeDrawer()
            case "ink", "alpha":
                // the stroke must settle before the ink under it changes
                if before != toy.inkFamily { settleStroke() }
                haptics.tick(0.5 * toy.hapticScale())
            case "locked", "bumper", "canvas", "scrim": haptics.tick(0.5 * toy.hapticScale())
            case "haptic": haptics.knock(0.85 * toy.hapticScale(), sharp: false)
            default: break
            }
            save()
            return
        }

        if toy.painting(), clearButton().contains(p) {
            clearTrail()
            haptics.tick(0.5 * toy.hapticScale())
            return
        }

        if toy.editing && toy.mode == .bumpers {
            if let hit = toy.toolbarHit(x, y) {
                toy.doToolbar(hit)
                haptics.tick(0.5 * toy.hapticScale())
                save()
                return
            }
            if toy.selected >= 0 && toy.selected < toy.table.count {
                let hs = toy.handles(toy.table[toy.selected])
                let reach = min(toy.w, toy.h) * 0.05
                if hypot(x - hs.resize.x, y - hs.resize.y) < reach { editDrag = "resize"; return }
                if hypot(x - hs.rotate.x, y - hs.rotate.y) < reach { editDrag = "rotate"; return }
            }
            for i in toy.table.indices.reversed() where toy.pointInBumper(x, y, toy.table[i]) {
                toy.selected = i
                editDrag = "move"
                grabD = CGSize(width: x - toy.table[i].nx * toy.w,
                               height: y - toy.table[i].ny * toy.h)
                return
            }
            toy.selected = -1
            return
        }

        if let label = toy.modeHit(x, y) {
            let wasPainting = toy.painting()
            toy.tapMode(label)
            if wasPainting && !toy.painting() { settleStroke() }
            liveStarted = false
            haptics.tick(0.5 * toy.hapticScale())
            save()
            return
        }

        if stripVisible() && toy.inStrip(y) {
            let before = toy.inkFamily
            if toy.stripTap(x) {
                if before != toy.inkFamily { settleStroke() }
                haptics.tick(0.5 * toy.hapticScale())
                save()
                return
            }
        }

        if toy.mode == .bolt {
            return                      // nothing to grab: a bolt is thrown, not carried
        }

        if toy.mode == .dial {
            toy.grabDial(x, y)
            dialLastSample = Date()
        } else {
            if !toy.grab(x, y, Date().timeIntervalSince1970) {
                haptics.tick(0.4 * toy.hapticScale())      // reached, and it was not there
                return
            }
            if toy.painting() {
                lastInk = Pt(toy.bx, toy.by)
                live = Path()
                live.move(to: CGPoint(x: toy.bx, y: toy.by))
                liveStarted = true
            }
        }
    }

    private func onMove(_ p: CGPoint) {
        guard case .play = toy.screen else { return }
        let x = Double(p.x), y = Double(p.y)
        if toy.drawerOpen { return }
        if toy.mode == .bolt { return }        // the drag samples are all it needs

        if toy.editing && toy.mode == .bumpers {
            guard let drag = editDrag, toy.selected >= 0, toy.selected < toy.table.count else { return }
            switch drag {
            case "move":
                toy.table[toy.selected].nx = Geom.clamp((x - grabD.width) / toy.w, 0, 1)
                toy.table[toy.selected].ny = Geom.clamp((y - grabD.height) / toy.h, 0, 1)
            case "resize":
                let b = toy.table[toy.selected]
                let d = hypot(x - b.nx * toy.w, y - b.ny * toy.h)
                toy.table[toy.selected].size = Geom.clamp(d / min(toy.w, toy.h), Toy.minBumper, Toy.maxBumper)
            default:
                let b = toy.table[toy.selected]
                toy.table[toy.selected].rot = atan2(y - b.ny * toy.h, x - b.nx * toy.w) - Double.pi / 2
            }
            return
        }

        if toy.mode == .dial {
            let now = Date()
            let dt = now.timeIntervalSince(dialLastSample ?? now)
            toy.dragDial(x, y, max(dt, 0.001))
            dialLastSample = now
        } else if toy.dragging {
            toy.drag(x, y)
            if toy.painting() { layTrail() }
        }
    }

    private func onEnd(_ g: DragGesture.Value) {
        defer { dragSamples.removeAll(); editDrag = nil }
        guard case .play = toy.screen else { return }
        if toy.editing && toy.mode == .bumpers { save(); return }
        if toy.mode == .dial { toy.releaseDial(); return }

        if toy.mode == .bolt {
            if let first = dragSamples.first, let last = dragSamples.last {
                let dt = max(0.001, last.t.timeIntervalSince(first.t))
                // A missed strike gets the same faint tap as a missed catch:
                // you reached, and nothing happened, and you should know which.
                if !toy.fireBolt(Double(last.p.x), Double(last.p.y),
                                 Double(last.p.x - first.p.x) / dt,
                                 Double(last.p.y - first.p.y) / dt) {
                    haptics.tick(0.4 * toy.hapticScale())
                }
            }
            return
        }

        guard toy.dragging else { return }

        // A finger stalls before it lifts, so the fling comes from a short
        // window rather than from the last sample — the same reason the dial
        // measures its flick over 120ms.
        var vx = 0.0, vy = 0.0
        if let first = dragSamples.first, let last = dragSamples.last {
            let dt = max(0.001, last.t.timeIntervalSince(first.t))
            vx = Double(last.p.x - first.p.x) / dt
            vy = Double(last.p.y - first.p.y) / dt
        }
        toy.release(vx, vy)
    }

    private func stripVisible() -> Bool {
        if case .play = toy.screen {} else { return false }
        return toy.mode != .dial && !toy.drawerOpen && !(toy.editing && toy.mode == .bumpers)
    }

    /// Two-finger tap is an Android gesture; here clearing is a drawn button,
    /// because a hidden gesture is not a feature anyone finds.
    private func clearButton() -> CGRect {
        let w = min(toy.w * 0.2, 92.0)
        let h = min(toy.viewH * 0.045, 34.0)
        return CGRect(x: toy.w - w - 14, y: toy.h - h - 14, width: w, height: h)
    }

    // MARK: persistence

    private func save() {
        let d = UserDefaults.standard
        d.set(toy.encodeTable(), forKey: "table")
        d.set(toy.sizeIndex, forKey: "sizeIndex")
        d.set(toy.name(of: toy.shape), forKey: "shape")
        d.set(toy.mustCatch, forKey: "mustCatch")
        d.set(toy.paintOnBumpers, forKey: "paintOnBumpers")
        d.set(toy.inkFamily, forKey: "inkFamily")
        d.set(toy.inkTone, forKey: "inkTone")
        d.set(toy.inkAlphaIndex, forKey: "inkAlpha")
        d.set(toy.scrimIndex, forKey: "scrim")
        d.set(toy.canvasIndex, forKey: "canvas")
        d.set(toy.hapticIndex, forKey: "haptic")
    }

    private func load() {
        let d = UserDefaults.standard
        if let raw = d.string(forKey: "table"), !raw.isEmpty {
            let parsed = toy.decodeTable(raw)
            if !parsed.isEmpty { toy.table = parsed }
        }
        if d.object(forKey: "sizeIndex") != nil {
            toy.sizeIndex = min(Toy.sizes.count - 1, max(0, d.integer(forKey: "sizeIndex")))
            toy.inkFamily = min(Palette.names.count - 1, max(0, d.integer(forKey: "inkFamily")))
            toy.inkTone = min(Palette.toneMix.count - 1, max(0, d.integer(forKey: "inkTone")))
            toy.inkAlphaIndex = min(Palette.alphas.count - 1, max(0, d.integer(forKey: "inkAlpha")))
            toy.scrimIndex = min(Palette.scrims.count - 1, max(0, d.integer(forKey: "scrim")))
            toy.canvasIndex = min(Palette.canvasNames.count - 1, max(0, d.integer(forKey: "canvas")))
            toy.hapticIndex = min(Palette.hapticNames.count - 1, max(0, d.integer(forKey: "haptic")))
            toy.mustCatch = d.bool(forKey: "mustCatch")
            toy.paintOnBumpers = d.bool(forKey: "paintOnBumpers")
        }
        if let s = d.string(forKey: "shape"), let shape = toy.shape(named: s) { toy.shape = shape }
        // The opening screen is the opening screen: what you were playing with
        // is remembered, but you still come back through the front door.
        toy.screen = .title
        applyTier()
        applyPreviewArguments()
    }

    /// Opens straight onto a named screen, so CI can photograph the ones that
    /// would otherwise need a finger — the paywall in particular, which is the
    /// screen nobody can reach without either paying or a tap.
    ///
    /// Debug only, and it takes a launch argument rather than a build flag so
    /// that the shipped binary has no path into it at all.
    private func applyPreviewArguments() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-uiPreview"), i + 1 < args.count else { return }
        previewing = true
        switch args[i + 1] {
        case "paywall": toy.tier = .free; toy.screen = .play; toy.showPaywall()
        case "bumpers": toy.tier = .full; toy.mode = .bumpers; toy.screen = .play
        case "dial": toy.tier = .full; toy.mode = .dial; toy.screen = .play; toy.dialOmega = 9
        case "paint": toy.tier = .full; toy.mode = .paint; toy.screen = .play
        case "drawer": toy.tier = .full; toy.screen = .play; toy.drawerOpen = true
        case "bolt":
            toy.tier = .full; toy.mode = .bolt; toy.screen = .play
            // struck across the field so the picture has something in it
            toy.fireBolt(toy.w * 0.2, toy.h * 0.65, 1500, -800)
            toy.fireBolt(toy.w * 0.8, toy.h * 0.35, -1300, 900)
        default: previewing = false
        }
        #endif
    }

    private func applyTier() {
        if previewing { return }
        toy.tier = store.tier
        toy.priceText = store.price
        toy.clampToTier()
        if store.tier == .full, case .paywall = toy.screen { toy.dismissPaywall() }
    }

    // MARK: drawing

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        let whole = CGRect(origin: .zero, size: size)

        // The ground. iOS cannot float an app over the home screen, so "sheer"
        // is the app's own dark ground rather than your desktop — which is
        // exactly why the solid canvases exist.
        ctx.fill(Path(whole), with: .color(toy.sheer() ? Color(argb: 0xff16181c)
                                                       : Color(argb: toy.canvasColor())))
        if toy.scrim() > 0 {
            ctx.fill(Path(whole), with: .color(.black.opacity(toy.scrim())))
        }

        switch toy.screen {
        case .paywall: drawPaywall(ctx); return
        case .title: drawTitle(ctx); return
        case .play: break
        }

        if toy.mode == .bolt {
            drawBolts(ctx)
            if stripVisible() { drawStrip(ctx) }
            drawModeRow(ctx)
            if toy.drawerOpen { drawDrawer(ctx) }
            return
        }

        if toy.mode == .dial {
            drawDial(ctx)
            drawModeRow(ctx)
            if toy.drawerOpen { drawDrawer(ctx) }
            return
        }

        if toy.painting() {
            for s in strokes {
                ctx.stroke(s.path, with: .color(Color(argb: s.argb, alpha: s.alpha)),
                           style: StrokeStyle(lineWidth: s.width, lineCap: .round, lineJoin: .round))
            }
            if liveStarted {
                ctx.stroke(live, with: .color(Color(argb: toy.inkColor(), alpha: toy.inkAlpha())),
                           style: StrokeStyle(lineWidth: toy.inkWidth(), lineCap: .round, lineJoin: .round))
            }
        }

        if toy.mode == .bumpers {
            for b in toy.table {
                let p = outline(b.shape, b.nx * toy.w, b.ny * toy.h, toy.bumperRadius(b), b.rot)
                ctx.fill(p, with: .color(Color(argb: toy.bumperColor(b), alpha: Toy.bumperAlpha)))
                ctx.stroke(p, with: .color(.black.opacity(0.35)), lineWidth: 2)
            }
        }

        let ball = outline(toy.shape, toy.bx, toy.by, toy.ballR(), toy.spin)
        ctx.fill(ball, with: .color(Color(argb: toy.inkColor(), alpha: toy.inkAlpha())))
        ctx.stroke(ball, with: .color(.black.opacity(0.35)), lineWidth: 2)

        if toy.editing && toy.mode == .bumpers { drawEditUI(ctx) } else if stripVisible() { drawStrip(ctx) }
        if toy.painting() { drawClearButton(ctx) }
        drawModeRow(ctx)
        if toy.drawerOpen { drawDrawer(ctx) }
    }

    private var sceneInk: Color {
        toy.sheer() ? Color(argb: 0xffeeeae2) : contrastOn(toy.canvasColor())
    }

    /// Text that stays readable on whatever it is sitting on.
    private func contrastOn(_ argb: UInt32) -> Color {
        let r = Double((argb >> 16) & 0xff), g = Double((argb >> 8) & 0xff), b = Double(argb & 0xff)
        let lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return lum > 0.55 ? Color(argb: 0xff3a3a3c) : Color(argb: 0xfff2efe8)
    }

    private func drawTitle(_ ctx: GraphicsContext) {
        if toy.sheer() {
            ctx.fill(Path(CGRect(x: 0, y: 0, width: toy.w, height: toy.viewH)),
                     with: .color(Color(argb: 0xff0c0c0e).opacity(0.62)))
        }
        let ink = sceneInk
        ctx.draw(Text("NONSENSE")
                    .font(.system(size: min(toy.w * 0.13, 64), weight: .semibold, design: .default))
                    .kerning(min(toy.w * 0.02, 10))
                    .foregroundColor(ink),
                 at: CGPoint(x: toy.w / 2, y: toy.titleBaseline()), anchor: .center)
        ctx.draw(Text("something to do with your hands")
                    .font(.system(size: min(toy.w * 0.031, 15), design: .monospaced))
                    .foregroundColor(ink.opacity(0.55)),
                 at: CGPoint(x: toy.w / 2, y: toy.titleBaseline() + toy.viewH * 0.035), anchor: .center)

        let items = toy.menuItems()
        for c in toy.menuRows() {
            let item = items[c.i]
            let rect = CGRect(x: c.x, y: c.y, width: c.w, height: c.h)
            let round = c.h * 0.24
            ctx.fill(Path(roundedRect: rect, cornerRadius: round), with: .color(ink.opacity(0.1)))
            ctx.stroke(Path(roundedRect: rect, cornerRadius: round), with: .color(ink.opacity(0.2)), lineWidth: 1)

            let tx = c.x + c.h * 0.4
            ctx.draw(Text(item.label.uppercased())
                        .font(.system(size: c.h * 0.28, weight: .medium))
                        .foregroundColor(ink),
                     at: CGPoint(x: tx, y: c.y + c.h * 0.36), anchor: .leading)
            ctx.draw(Text(item.blurb)
                        .font(.system(size: c.h * 0.185, design: .monospaced))
                        .foregroundColor(ink.opacity(0.55)),
                     at: CGPoint(x: tx, y: c.y + c.h * 0.7), anchor: .leading)

            let gr = c.h * 0.24
            let gx = c.x + c.w - c.h * 0.52
            let gy = c.y + c.h / 2
            if toy.menuLocked(item.key) {
                drawLock(ctx, gx, gy, gr, ink.opacity(0.6))
            } else if item.key == "unlock" {
                drawLock(ctx, gx, gy, gr, Color(argb: 0xff702929))
            } else {
                let glyph: ToyShape = item.key == "bumpers" ? .hexagon
                    : item.key == "paint" ? .bar
                    : item.key == "ink" ? .square : .circle
                let p = outline(glyph, gx, gy, gr, 0)
                ctx.fill(p, with: .color(item.key == "ink" ? Color(argb: toy.inkColor())
                                                           : ink.opacity(0.65)))
                // Graphite ink on a dark ground is a hole rather than a
                // swatch, so the one that shows your colour gets an edge.
                if item.key == "ink" { ctx.stroke(p, with: .color(ink.opacity(0.45)), lineWidth: 1.5) }
                if item.key == "dial" {
                    for i in 0..<8 {
                        let a = Double(i) * Double.pi / 4
                        var tick = Path()
                        tick.move(to: CGPoint(x: gx + cos(a) * gr * 0.45, y: gy + sin(a) * gr * 0.45))
                        tick.addLine(to: CGPoint(x: gx + cos(a) * gr * 0.95, y: gy + sin(a) * gr * 0.95))
                        ctx.stroke(tick, with: .color(Color.black.opacity(0.75)),
                                   lineWidth: max(1, gr * 0.13))
                    }
                }
            }
        }
    }

    private func drawPaywall(_ ctx: GraphicsContext) {
        if toy.sheer() {
            ctx.fill(Path(CGRect(x: 0, y: 0, width: toy.w, height: toy.viewH)),
                     with: .color(Color(argb: 0xff0c0c0e).opacity(0.62)))
        }
        let ink = sceneInk
        ctx.draw(Text("UNLOCK EVERYTHING")
                    .font(.system(size: min(toy.w * 0.062, 30), weight: .semibold))
                    .kerning(2)
                    .foregroundColor(ink),
                 at: CGPoint(x: toy.w / 2, y: toy.viewH * 0.19), anchor: .center)

        let lines = toy.paywallLines()
        let buttons = toy.paywallButtons()
        let top = toy.viewH * 0.28
        let lx = max(toy.w * 0.14, toy.w / 2 - 220)

        // Measured, not assumed. At a fixed size the longer promises ran off
        // the right edge of the phone they are being sold on, which is a poor
        // advertisement for care — the same bug, and the same fix, as the
        // other two builds.
        let room = toy.w - lx - max(toy.w * 0.06, 16)
        func widest(_ px: Double) -> Double {
            lines.map { line in
                Double(ctx.resolve(Text(line).font(.system(size: px, design: .monospaced)))
                    .measure(in: CGSize(width: 10_000, height: 10_000)).width)
            }.max() ?? 0
        }
        var lineSize = min(toy.w * 0.03, 14)
        let measured = widest(lineSize)
        if measured > room { lineSize = max(lineSize * room / measured, 8) }

        let step = min(lineSize * 2.4, (buttons[0].y - top) / Double(lines.count))
        for (i, line) in lines.enumerated() {
            let y = top + step * (Double(i) + 0.5)
            ctx.fill(Path(ellipseIn: CGRect(x: lx - lineSize * 0.9, y: y - lineSize * 0.17,
                                            width: lineSize * 0.34, height: lineSize * 0.34)),
                     with: .color(ink.opacity(0.5)))
            ctx.draw(Text(line)
                        .font(.system(size: lineSize, design: .monospaced))
                        .foregroundColor(ink.opacity(0.85)),
                     at: CGPoint(x: lx, y: y), anchor: .leading)
        }

        for c in buttons {
            let primary = toy.paywallLabels[c.i] == "unlock"
            let rect = CGRect(x: c.x, y: c.y, width: c.w, height: c.h)
            let round = c.h * 0.22
            ctx.fill(Path(roundedRect: rect, cornerRadius: round),
                     with: .color(primary ? Color(argb: 0xff702929) : ink.opacity(0.1)))
            if !primary {
                ctx.stroke(Path(roundedRect: rect, cornerRadius: round),
                           with: .color(ink.opacity(0.22)), lineWidth: 1)
            }
            ctx.draw(Text(primary ? toy.unlockLabel() : toy.paywallLabels[c.i])
                        .font(.system(size: c.h * 0.3, weight: .medium, design: .monospaced))
                        .foregroundColor(primary ? Color(argb: 0xfff0ece4) : ink.opacity(0.75)),
                     at: CGPoint(x: c.x + c.w / 2, y: c.y + c.h / 2), anchor: .center)
        }
    }

    private func drawLock(_ ctx: GraphicsContext, _ cx: Double, _ cy: Double, _ r: Double, _ colour: Color) {
        ctx.fill(Path(roundedRect: CGRect(x: cx - r * 0.62, y: cy - r * 0.1,
                                          width: r * 1.24, height: r * 0.88),
                      cornerRadius: r * 0.18), with: .color(colour))
        var shackle = Path()
        shackle.addArc(center: CGPoint(x: cx, y: cy - r * 0.1), radius: r * 0.38,
                       startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        ctx.stroke(shackle, with: .color(colour), lineWidth: max(1.5, r * 0.24))
    }

    /// Lightning: a wide dim wash of the ink under a hairline of near-white.
    /// One stroke reads as a wire; it is the pair that reads as a glow, and
    /// the pair costs two strokes rather than a blur filter.
    private func drawBolts(_ ctx: GraphicsContext) {
        let short = min(toy.w, toy.h)
        let ink = toy.inkColor()
        let core = mix(ink, 0xffffff, 0.78)
        for b in toy.bolts where b.nodes.count >= 2 {
            let a = toy.boltAlpha(b)
            var p = Path()
            p.move(to: CGPoint(x: b.nodes[0].x, y: b.nodes[0].y))
            for n in b.nodes.dropFirst() { p.addLine(to: CGPoint(x: n.x, y: n.y)) }
            p.addLine(to: CGPoint(x: b.x, y: b.y))   // the head runs ahead of its last kink

            ctx.stroke(p, with: .color(Color(argb: ink, alpha: a * 0.31)),
                       style: StrokeStyle(lineWidth: short * 0.026, lineCap: .round, lineJoin: .round))
            ctx.stroke(p, with: .color(Color(argb: core, alpha: a * 0.92)),
                       style: StrokeStyle(lineWidth: short * 0.006, lineCap: .round, lineJoin: .round))
            let hr = short * 0.011 * a
            ctx.fill(Path(ellipseIn: CGRect(x: b.x - hr, y: b.y - hr, width: hr * 2, height: hr * 2)),
                     with: .color(Color(argb: core, alpha: a)))
        }
    }

    /// A knurled wheel. Eighteen ribs, one marked so a turn stays countable,
    /// and a red index they click past. At speed they are drawn wider and
    /// fainter, so a fast spin blurs instead of crawling backwards.
    private func drawDial(_ ctx: GraphicsContext) {
        let cx = toy.w / 2, cy = toy.h / 2, r = toy.dialR
        let a = toy.inkAlpha()
        let ink = toy.inkColor()
        let fast = toy.dialSpeedFraction()

        ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)),
                 with: .color(Color(argb: ink, alpha: a)))

        let step = 2 * Double.pi / Double(toy.dialRibs)
        let inner = r * 0.54, outer = r * 0.94
        for i in 0..<toy.dialRibs {
            let ang = toy.dialAngle + step * Double(i)
            let marked = i == 0
            var p = Path()
            p.move(to: CGPoint(x: cx + cos(ang) * inner, y: cy + sin(ang) * inner))
            p.addLine(to: CGPoint(x: cx + cos(ang) * outer, y: cy + sin(ang) * outer))
            let shade = marked ? mix(ink, 0x000000, 0.55) : mix(ink, 0xffffff, 0.5)
            let alpha = (marked ? 0.95 : 0.8 - 0.3 * fast) * a
            ctx.stroke(p, with: .color(Color(argb: shade, alpha: alpha)),
                       style: StrokeStyle(lineWidth: r * (0.085 + 0.075 * fast), lineCap: .round))
        }

        let hub = r * 0.46
        ctx.fill(Path(ellipseIn: CGRect(x: cx - hub, y: cy - hub, width: hub * 2, height: hub * 2)),
                 with: .color(Color(argb: mix(ink, 0x000000, 0.28), alpha: a)))
        ctx.stroke(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)),
                   with: .color(.black.opacity(0.35)), lineWidth: 2)

        // the index the ribs click past
        var mark = Path()
        let tip = cy - r * 1.02
        mark.move(to: CGPoint(x: cx, y: tip + r * 0.09))
        mark.addLine(to: CGPoint(x: cx - r * 0.055, y: tip - r * 0.02))
        mark.addLine(to: CGPoint(x: cx + r * 0.055, y: tip - r * 0.02))
        mark.closeSubpath()
        ctx.fill(mark, with: .color(Color(argb: 0xff702929)))
    }

    private func mix(_ c: UInt32, _ target: UInt32, _ t: Double) -> UInt32 {
        func ch(_ shift: UInt32) -> UInt32 {
            let a = Double((c >> shift) & 0xff)
            let b = Double((target >> shift) & 0xff)
            return UInt32(max(0, min(255, a + (b - a) * t)))
        }
        return (0xff << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)
    }

    private func drawModeRow(_ ctx: GraphicsContext) {
        let cells = toy.modeCells()
        let labels = toy.modeLabels()
        let size = min(min(toy.w, toy.viewH) * 0.028, (cells.first?.w ?? toy.w) * 0.155)
        for c in cells {
            let label = labels[c.i]
            let on: Bool
            switch label {
            case "ball": on = toy.mode == .ball
            case "dial": on = toy.mode == .dial
            case "bumpers": on = toy.mode == .bumpers
            case "bolt": on = toy.mode == .bolt
            case "paint": on = toy.mode == .paint
            case "ink": on = toy.drawerOpen
            case "edit": on = toy.editing
            case "catch": on = toy.mustCatch
            default: on = false
            }
            let rect = CGRect(x: c.x + 2, y: c.y + 2, width: c.w - 4, height: c.h - 4)
            ctx.fill(Path(rect), with: .color(on ? Color(argb: 0xff3a3a3c)
                                                 : Color(argb: 0xffe2dccd).opacity(0.6)))
            ctx.draw(Text(label.uppercased())
                        .font(.system(size: size, weight: .medium, design: .monospaced))
                        .foregroundColor(on ? Color(argb: 0xffe8e4dc) : Color(argb: 0xff3a3a3c).opacity(0.75)),
                     at: CGPoint(x: c.x + c.w / 2, y: c.y + c.h / 2), anchor: .center)
        }
    }

    private func drawStrip(_ ctx: GraphicsContext) {
        let sh = toy.stripH()
        let cy = toy.stripTop() + sh / 2
        let chipR = sh * 0.28
        for z in toy.stripZones() {
            let step = (z.x1 - z.x0) / Double(z.count)
            for i in 0..<z.count {
                let cx = z.x0 + step * (Double(i) + 0.5)
                switch z.kind {
                case "color":
                    let locked = toy.familyLocked(i)
                    let c = Palette.colors[i][toy.inkTone]
                    ctx.fill(Path(ellipseIn: CGRect(x: cx - chipR, y: cy - chipR,
                                                    width: chipR * 2, height: chipR * 2)),
                             with: .color(Color(argb: c, alpha: locked ? 0.3 : 1)))
                    if locked { drawLock(ctx, cx, cy, chipR * 0.62, Color(argb: 0xff3a3a3c).opacity(0.8)) }
                    if i == toy.inkFamily {
                        ctx.stroke(Path(ellipseIn: CGRect(x: cx - chipR - 5, y: cy - chipR - 5,
                                                          width: (chipR + 5) * 2, height: (chipR + 5) * 2)),
                                   with: .color(.black.opacity(0.5)), lineWidth: 2)
                    }
                case "size":
                    let rr = chipR * (0.3 + 0.85 * (Toy.sizes[i] / Toy.sizes.last!))
                    ctx.fill(outline(toy.shape, cx, cy, rr, 0),
                             with: .color(Color(argb: 0xff3a3a3c).opacity(0.55)))
                default:
                    let s = ToyShape.allCases[i]
                    ctx.fill(outline(s, cx, cy, chipR * 0.92, 0),
                             with: .color(Color(argb: 0xff3a3a3c).opacity(s == toy.shape ? 1 : 0.35)))
                }
            }
        }
    }

    private func drawClearButton(_ ctx: GraphicsContext) {
        let r = clearButton()
        ctx.fill(Path(roundedRect: r, cornerRadius: 3), with: .color(.white.opacity(0.55)))
        ctx.draw(Text("CLEAR").font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(Color(argb: 0xff4a4742)),
                 at: CGPoint(x: r.midX, y: r.midY), anchor: .center)
    }

    private func drawEditUI(_ ctx: GraphicsContext) {
        if toy.selected >= 0 && toy.selected < toy.table.count {
            let b = toy.table[toy.selected]
            let p = outline(b.shape, b.nx * toy.w, b.ny * toy.h, toy.bumperRadius(b), b.rot)
            ctx.stroke(p, with: .color(Color(argb: 0xff702929)),
                       style: StrokeStyle(lineWidth: 2, dash: [6, 5]))
            let hs = toy.handles(b)
            let hr = min(toy.w, toy.h) * 0.022
            ctx.fill(Path(ellipseIn: CGRect(x: hs.resize.x - hr, y: hs.resize.y - hr,
                                            width: hr * 2, height: hr * 2)),
                     with: .color(Color(argb: 0xff702929)))
            if b.shape != .circle {
                ctx.fill(Path(ellipseIn: CGRect(x: hs.rotate.x - hr, y: hs.rotate.y - hr,
                                                width: hr * 2, height: hr * 2)),
                         with: .color(Color(argb: 0xff465a78)))
            }
        }
        for c in toy.toolbarButtons() {
            let label = toy.toolbarLabels[c.i]
            let live = c.i == 0 || label == "reset" || label == "done" || toy.selected >= 0
            let rect = CGRect(x: c.x, y: c.y, width: c.w, height: c.h)
            ctx.fill(Path(roundedRect: rect, cornerRadius: 5), with: .color(Color(argb: 0xffe2dccd).opacity(0.92)))
            if label == "ink" && live {
                let sw = min(c.w, c.h) * 0.34
                ctx.fill(Path(CGRect(x: c.x + c.w / 2 - sw, y: c.y + c.h / 2 - sw,
                                     width: sw * 2, height: sw * 2)),
                         with: .color(Color(argb: toy.bumperColor(toy.table[toy.selected]))))
                continue
            }
            ctx.draw(Text(label).font(.system(size: min(toy.w, toy.h) * 0.026, design: .monospaced))
                        .foregroundColor(Color(argb: 0xff3a3a3c).opacity(live ? 1 : 0.35)),
                     at: CGPoint(x: c.x + c.w / 2, y: c.y + c.h / 2), anchor: .center)
        }
    }

    private func drawDrawer(_ ctx: GraphicsContext) {
        let b = toy.drawerBox()
        let panel = CGRect(x: b.x, y: b.y, width: b.w, height: b.h)
        ctx.fill(Path(roundedRect: panel, cornerRadius: 10), with: .color(Color(argb: 0xffe8e4dc).opacity(0.97)))

        let targeted = toy.targetBumperIndex()
        let selFamily = targeted.map { toy.table[$0].family } ?? toy.inkFamily
        let selTone = targeted.map { toy.table[$0].tone } ?? toy.inkTone
        ctx.draw(Text((targeted != nil ? "BUMPER  ·  " : "INK  ·  ") + Palette.names[selFamily])
                    .font(.system(size: min(toy.w, toy.h) * 0.026, design: .monospaced))
                    .foregroundColor(Color(argb: 0xff3a3a3c).opacity(0.6)),
                 at: CGPoint(x: b.gx, y: b.gy - min(toy.w, toy.h) * 0.02), anchor: .leading)

        for f in Palette.colors.indices {
            for t in Palette.colors[f].indices {
                let x = b.gx + Double(f) * b.cell
                let y = b.gy + Double(t) * b.cell
                let locked = toy.familyLocked(f)
                let cell = CGRect(x: x + 2, y: y + 2, width: b.cell - 4, height: b.cell - 4)
                ctx.fill(Path(cell), with: .color(Color(argb: Palette.colors[f][t], alpha: locked ? 0.25 : 1)))
                if locked && t == 0 {
                    drawLock(ctx, x + b.cell / 2, y + b.cell * 1.5, b.cell * 0.26,
                             Color(argb: 0xff3a3a3c).opacity(0.7))
                }
                if f == selFamily && t == selTone {
                    ctx.stroke(Path(cell.insetBy(dx: 3, dy: 3)), with: .color(.black), lineWidth: 2)
                }
            }
        }

        for kind in toy.drawerRows {
            let y = toy.drawerRowY(b, kind)
            let n = toy.drawerRowCount(kind)
            let title = kind == "alpha" ? "TRANSLUCENCY" : kind == "canvas" ? "CANVAS"
                : kind == "scrim" ? "SCREEN TINT" : "HAPTICS"
            ctx.draw(Text(title).font(.system(size: min(toy.w, toy.h) * 0.024, design: .monospaced))
                        .foregroundColor(Color(argb: 0xff3a3a3c).opacity(0.6)),
                     at: CGPoint(x: b.gx, y: y - min(toy.w, toy.h) * 0.02), anchor: .leading)
            for c in toy.drawerChips(y, n, b) {
                let rect = CGRect(x: c.x, y: c.y, width: c.w, height: c.h)
                let selected: Bool
                var fillColour = Color.white.opacity(0.8)
                var label = ""
                switch kind {
                case "alpha":
                    selected = c.i == toy.inkAlphaIndex
                    fillColour = Color(argb: toy.inkColor(), alpha: Palette.alphas[c.i])
                    label = "\(Int(Palette.alphas[c.i] * 100))%"
                case "canvas":
                    selected = c.i == toy.canvasIndex
                    let locked = toy.canvasLocked(c.i)
                    fillColour = c.i == 0 ? .white.opacity(0.5)
                        : Color(argb: Palette.canvasColors[c.i], alpha: locked ? 0.3 : 1)
                    label = Palette.canvasNames[c.i]
                case "scrim":
                    selected = c.i == toy.scrimIndex
                    fillColour = .black.opacity(Palette.scrims[c.i])
                    label = "\(Int(Palette.scrims[c.i] * 100))%"
                default:
                    selected = c.i == toy.hapticIndex
                    fillColour = Color(argb: 0xff3a3a3c).opacity(0.13 + Palette.hapticScales[c.i] * 0.59)
                    label = Palette.hapticNames[c.i]
                }
                ctx.fill(Path(roundedRect: rect, cornerRadius: 5), with: .color(fillColour))
                ctx.stroke(Path(roundedRect: rect, cornerRadius: 5),
                           with: .color(.black.opacity(selected ? 0.85 : 0.18)),
                           lineWidth: selected ? 2 : 1)
                ctx.draw(Text(label).font(.system(size: min(toy.w, toy.h) * 0.022, design: .monospaced))
                            .foregroundColor(Color(argb: 0xff3a3a3c)),
                         at: CGPoint(x: c.x + c.w / 2, y: c.y + c.h / 2), anchor: .center)
            }
        }
    }
}
