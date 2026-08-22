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

/// A bumper's drawn shape: the loops the core hands back — one for an outline,
/// several for a letter — or an ellipse where a round bumper has been pulled
/// out of round. Even-odd, so the counter in an A stays a hole.
private func bumperPath(_ toy: Toy, _ b: Bumper) -> Path {
    let loops = toy.bumperLoops(b)
    if loops.isEmpty {
        let c = toy.bumperCenter(b)
        let r = toy.bumperRadius(b)
        return Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
    }
    var p = Path()
    for loop in loops { p.addPath(path(loop)) }
    return p
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
    @State private var speaker = Speaker()

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
    /// Whether the code has been entered on this install. There is no
    /// subscription for StoreKit to find, so without this every launch would
    /// take the unlock straight back off again.
    @State private var codeUnlocked = false

    /// Whether -uiPreview asked for lightning. Read from the process rather
    /// than kept in @State: it is decided once, at launch, and a screenshot
    /// cannot wait for a state change to propagate.
    private static let previewBolt: Bool = {
        #if DEBUG
        let a = ProcessInfo.processInfo.arguments
        if let i = a.firstIndex(of: "-uiPreview"), i + 1 < a.count { return a[i + 1] == "bolt" }
        #endif
        return false
    }()
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

        // Struck across the field so the picture always has something in it,
        // whenever the screenshot happens to land. The bolts are then run
        // forward here rather than left to the next frame: a bolt one frame
        // old is a single node, which draws nothing, and a screenshot cannot
        // count on there being a next frame at all.
        //
        // Waits for the toy to actually be on lightning, which is what took
        // three rounds to find. This runs on every frame including the first,
        // and `load()` runs later, from .task — so the first version fired its
        // bolts while the toy was still on the title screen, where step()
        // returns immediately. A hundred and eighty primed steps went nowhere,
        // and two bolts sat at their starting points for the rest of the run.
        if Self.previewBolt, toy.mode == .bolt, case .play = toy.screen,
           toy.etched.isEmpty, toy.bolts.isEmpty {
            toy.fireBolt(toy.w * 0.2, toy.h * 0.65, 1500, -800)
            toy.fireBolt(toy.w * 0.8, toy.h * 0.35, -1300, 900)
            for _ in 0..<180 { toy.step(1.0 / 120.0) }
        }

        // Whatever the toy decided to say this frame, say it.
        if !toy.notes.isEmpty { speaker.play(toy.takeNotes()) }

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
            if toy.codeOpen {
                if let key = toy.keypadHit(x, y) {
                    haptics.tick(0.5 * toy.hapticScale())
                    if toy.typeCode(key) {
                        // Remembered, and it outranks the store's answer on
                        // the next launch — the store will go on saying "not
                        // subscribed" for ever.
                        codeUnlocked = true
                        UserDefaults.standard.set(true, forKey: "codeUnlock")
                        save()
                    }
                }
                return
            }
            let prompt = toy.codePromptCell()
            if x >= prompt.x, x <= prompt.x + prompt.w, y >= prompt.y, y <= prompt.y + prompt.h {
                haptics.tick(0.5 * toy.hapticScale())
                toy.openCode()
                return
            }
            switch toy.paywallHit(x, y) {
            case "subscribe": haptics.tick(0.5 * toy.hapticScale()); Task { await store.buy() }
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
            // Picking a voice plays it: a list of words is no way to choose a
            // sound.
            case "sound":
                speaker.play([Note(voice: toy.voiceIndex, step: 7, gain: 0.8,
                                   seed: Int32(toy.bounceCount + 1))])
            default: break
            }
            save()
            return
        }

        if toy.painting() || toy.mode == .bolt || toy.mode == .glass,
           clearButton().contains(p) {
            clearTrail()
            toy.clearEtched()
            toy.clearGlass()
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
                if hypot(x - hs.pull.x, y - hs.pull.y) < reach { editDrag = "pull"; return }
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

        if toy.mode == .glass {
            // A press is the whole gesture. It breaks where you pressed, at
            // once, because glass does not wait to see whether you meant it.
            if toy.breakGlass(x, y) { haptics.knock(0.95 * toy.hapticScale(), sharp: true) }
            return
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
            case "pull":
                toy.pullTo(toy.selected, x, y)
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

    /// Not on the dial, which has neither a ball nor an ink to choose. On
    /// lightning the strip is the palette and nothing else — see stripZones.
    private func stripVisible() -> Bool {
        if case .play = toy.screen {} else { return false }
        return toy.mode != .dial && !toy.drawerOpen
            && !(toy.editing && toy.mode == .bumpers)
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
        d.set(4, forKey: "prefsVersion")
        d.set(toy.hapticIndex, forKey: "haptic")
        d.set(toy.voiceIndex, forKey: "voice")
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
            // The ground the app opens on changed from sheer to slate. Anything
            // saved before that is taken back to the new default once, rather
            // than leaving an old install on a ground it was never asked about.
            toy.canvasIndex = d.integer(forKey: "prefsVersion") >= 4
                ? min(Palette.canvasNames.count - 1, max(0, d.integer(forKey: "canvas")))
                : Toy.defaultCanvas
            toy.hapticIndex = min(Palette.hapticNames.count - 1, max(0, d.integer(forKey: "haptic")))
            toy.voiceIndex = min(Palette.voiceNames.count - 1, max(0, d.integer(forKey: "voice")))
            toy.mustCatch = d.bool(forKey: "mustCatch")
            toy.paintOnBumpers = d.bool(forKey: "paintOnBumpers")
        }
        if let s = d.string(forKey: "shape"), let shape = toy.shape(named: s) { toy.shape = shape }
        // The opening screen is the opening screen: what you were playing with
        // is remembered, but you still come back through the front door.
        toy.screen = .title
        codeUnlocked = d.bool(forKey: "codeUnlock")
        if codeUnlocked { toy.tier = .full }
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
        default: previewing = false
        }
        #endif
    }

    private func applyTier() {
        if previewing { return }
        if codeUnlocked { toy.tier = .full; toy.priceText = store.price; return }
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
        case .paywall: if toy.codeOpen { drawKeypad(ctx) } else { drawPaywall(ctx) }; return
        case .title: drawTitle(ctx); return
        case .play: break
        }

        if toy.mode == .glass {
            drawGlass(ctx)
            drawClearButton(ctx)
            if stripVisible() { drawStrip(ctx) }
            drawModeRow(ctx)
            if toy.drawerOpen { drawDrawer(ctx) }
            return
        }

        if toy.mode == .bolt {
            drawBolts(ctx)
            drawClearButton(ctx)
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
                let p = bumperPath(toy, b)
                ctx.fill(p, with: .color(Color(argb: toy.bumperColor(b), alpha: Toy.bumperAlpha)),
                         style: FillStyle(eoFill: true))
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
                if item.key == "glass" {
                    // A pane with a break in it: a square, and three cracks
                    // leaving one point.
                    let box = CGRect(x: gx - gr * 0.85, y: gy - gr * 0.85,
                                     width: gr * 1.7, height: gr * 1.7)
                    ctx.stroke(Path(box), with: .color(ink.opacity(0.65)),
                               style: StrokeStyle(lineWidth: gr * 0.16, lineJoin: .round))
                    var cracks = Path()
                    for (dx, dy) in [(-0.75, -0.55), (0.8, 0.3), (-0.2, 0.85)] {
                        cracks.move(to: CGPoint(x: gx - gr * 0.1, y: gy - gr * 0.05))
                        cracks.addLine(to: CGPoint(x: gx + gr * dx, y: gy + gr * dy))
                    }
                    ctx.stroke(cracks, with: .color(ink.opacity(0.65)),
                               style: StrokeStyle(lineWidth: gr * 0.16, lineCap: .round))
                    continue
                }
                if item.key == "bolt" {
                    // Lightning gets a stroke rather than an outline. Every
                    // other glyph is the thing itself; a triangle said nothing
                    // about what the row does.
                    var z = Path()
                    z.move(to: CGPoint(x: gx - gr * 0.5, y: gy - gr * 0.9))
                    z.addLine(to: CGPoint(x: gx + gr * 0.3, y: gy - gr * 0.3))
                    z.addLine(to: CGPoint(x: gx - gr * 0.3, y: gy + gr * 0.3))
                    z.addLine(to: CGPoint(x: gx + gr * 0.5, y: gy + gr * 0.9))
                    ctx.stroke(z, with: .color(ink.opacity(0.65)),
                               style: StrokeStyle(lineWidth: gr * 0.3,
                                                  lineCap: .round, lineJoin: .round))
                    continue
                }
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

        // The terms, above the buttons: what it costs, how often, and that it
        // renews. Apple rejects a subscription paywall that leaves this to the
        // store sheet, and it is the honest thing to do anyway.
        let prompt = toy.codePromptCell()
        let termSize = min(toy.w * 0.028, 12)
        for (i, line) in toy.subscriptionTerms().enumerated() {
            let y = prompt.y - prompt.h * 0.5
                - termSize * Double(toy.subscriptionTerms().count - i) * 1.35
            ctx.draw(Text(line)
                        .font(.system(size: termSize, design: .monospaced))
                        .foregroundColor(ink.opacity(0.6)),
                     at: CGPoint(x: toy.w / 2, y: y), anchor: .center)
        }
        ctx.draw(Text(Toy.codePrompt)
                    .font(.system(size: min(prompt.h * 0.45, 14), weight: .medium,
                                  design: .monospaced))
                    .foregroundColor(ink.opacity(0.7)),
                 at: CGPoint(x: toy.w / 2, y: prompt.y + prompt.h / 2), anchor: .center)

        for c in buttons {
            let primary = toy.paywallLabels[c.i] == "subscribe"
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

    /// The keypad, which replaces the buttons once the code row is tapped.
    private func drawKeypad(_ ctx: GraphicsContext) {
        if toy.sheer() {
            ctx.fill(Path(CGRect(x: 0, y: 0, width: toy.w, height: toy.viewH)),
                     with: .color(Color(argb: 0xff0c0c0e).opacity(0.62)))
        }
        let ink = sceneInk
        ctx.draw(Text(toy.codeWrong ? "NOT THAT ONE" : "ENTER THE CODE")
                    .font(.system(size: min(toy.w * 0.062, 26), weight: .semibold))
                    .foregroundColor(ink),
                 at: CGPoint(x: toy.w / 2, y: toy.viewH * 0.19), anchor: .center)

        // One dot per digit, filled as they are typed: a row of dots says how
        // long the code is without a field that needs a cursor.
        let cells = toy.keypadCells()
        let r = min(toy.w * 0.014, 7)
        let gap = r * 3.4
        let dotsY = cells[0].y - r * 6
        let x0 = toy.w / 2 - Double(Toy.codeLength - 1) * gap / 2
        for i in 0..<Toy.codeLength {
            let cx = x0 + gap * Double(i)
            ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: dotsY - r, width: r * 2, height: r * 2)),
                     with: .color(ink.opacity(i < toy.codeEntry.count ? 0.9 : 0.25)))
        }

        let keys = toy.keypadKeys()
        for c in cells where !keys[c.i].isEmpty {
            let rect = CGRect(x: c.x, y: c.y, width: c.w, height: c.h)
            let round = c.h * 0.22
            ctx.fill(Path(roundedRect: rect, cornerRadius: round), with: .color(ink.opacity(0.1)))
            ctx.stroke(Path(roundedRect: rect, cornerRadius: round),
                       with: .color(ink.opacity(0.22)), lineWidth: 1)
            let key = keys[c.i]
            ctx.draw(Text(key == "del" ? "back" : key)
                        .font(.system(size: c.h * (key == "del" ? 0.3 : 0.42),
                                      weight: .medium, design: .monospaced))
                        .foregroundColor(ink.opacity(0.82)),
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
    /// The etchings go down first, cool, so a fresh strike is plainly the
    /// bright one and the scene behind it is a record rather than a crowd.
    private func drawBolts(_ ctx: GraphicsContext) {
        let short = min(toy.w, toy.h)
        for e in toy.etched {
            boltPath(ctx, e.nodes, nil, e.argb, short, Toy.etchAlpha,
                     Toy.boltWeight(e.gen), Toy.boltCoreCool, cool: true)
        }
        for b in toy.bolts {
            boltPath(ctx, b.nodes, b.struck ? nil : Pt(b.x, b.y), b.argb, short,
                     toy.boltAlpha(b), Toy.boltWeight(b.gen), Toy.boltCoreHot, cool: false)
        }
    }

    /// Glass. The seam is dark and the edge beside it is the ink you chose —
    /// that pair is the whole illusion: a fracture face catching the light, in
    /// a colour, against the dark of the crack itself.
    private func drawGlass(_ ctx: GraphicsContext) {
        let short = min(toy.w, toy.h)
        for b in toy.breaks {
            let edge = mix(b.argb, 0xffffff, 0.34)
            for c in b.cracks where c.nodes.count >= 2 {
                var p = Path()
                p.move(to: CGPoint(x: c.nodes[0].x, y: c.nodes[0].y))
                for n in c.nodes.dropFirst() { p.addLine(to: CGPoint(x: n.x, y: n.y)) }

                // The seam: the gap itself, darker than anything around it.
                ctx.stroke(p, with: .color(.black.opacity(c.ring ? 0.35 : 0.47)),
                           style: StrokeStyle(lineWidth: short * (c.ring ? 0.006 : 0.008),
                                              lineCap: .round, lineJoin: .round))
                // The exposed edge, offset by a hair so it reads as a face
                // rather than as an outline.
                var lit = ctx
                lit.translateBy(x: short * 0.0022, y: -short * 0.0022)
                lit.stroke(p, with: .color(Color(argb: edge, alpha: c.ring ? 0.59 : 0.8)),
                           style: StrokeStyle(lineWidth: short * (c.ring ? 0.0028 : 0.0038),
                                              lineCap: .round, lineJoin: .round))
            }
            // The point of impact: a small crater of the same ink.
            let r = short * 0.012
            ctx.fill(Path(ellipseIn: CGRect(x: b.x - r, y: b.y - r, width: r * 2, height: r * 2)),
                     with: .color(Color(argb: mix(b.argb, 0xffffff, 0.5), alpha: 0.75)))
        }
    }

    /// One path, three widths. `head` extends it to a bolt still travelling.
    private func boltPath(_ ctx: GraphicsContext, _ nodes: [Pt], _ head: Pt?,
                          _ argb: UInt32, _ short: Double, _ a: Double,
                          _ weight: Double, _ hot: Double, cool: Bool) {
        if nodes.count < 2 { return }
        let core = mix(argb, 0xffffff, hot)
        var p = Path()
        p.move(to: CGPoint(x: nodes[0].x, y: nodes[0].y))
        for n in nodes.dropFirst() { p.addLine(to: CGPoint(x: n.x, y: n.y)) }
        if let head { p.addLine(to: CGPoint(x: head.x, y: head.y)) }

        // Three passes, and the middle one is the reason it reads on a pale
        // canvas as well as a dark one: a white filament laid straight on
        // paper is invisible, so it gets a dark sheath to sit against.
        ctx.stroke(p, with: .color(Color(argb: argb, alpha: a * 0.28)),
                   style: StrokeStyle(lineWidth: short * 0.026 * weight,
                                      lineCap: .round, lineJoin: .round))
        // Etchings get two passes rather than three: a fan lands a lot of them,
        // and the middle sheath is what makes a live strike sit on a pale
        // canvas, not what makes a cooled one readable.
        if !cool {
            ctx.stroke(p, with: .color(Color(argb: argb, alpha: a * 0.7)),
                       style: StrokeStyle(lineWidth: short * 0.013 * weight,
                                          lineCap: .round, lineJoin: .round))
        }
        ctx.stroke(p, with: .color(Color(argb: core, alpha: a * 0.95)),
                   style: StrokeStyle(lineWidth: short * 0.005 * weight,
                                      lineCap: .round, lineJoin: .round))
        if let head {
            let hr = short * 0.011 * a * weight
            ctx.fill(Path(ellipseIn: CGRect(x: head.x - hr, y: head.y - hr,
                                            width: hr * 2, height: hr * 2)),
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
            case "glass": on = toy.mode == .glass
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
        // On a phone the strip is the only way to reach size and shape, and on
        // a phone the cells are half as wide as they are on a laptop: a chip
        // sized off the strip's height alone overlapped its neighbours and ran
        // off the left edge. It gets whichever is smaller.
        let chipCap = sh * 0.28
        // Graphite chips on a dark ground are a hole rather than a control.
        // Unlike the other two builds, sheer here is not a window onto
        // something unknown — iOS cannot float an app over anything, so sheer
        // is the app's own dark ground and the chips can follow it too.
        let chipInk = sceneInk
        for z in toy.stripZones() {
            let step = (z.x1 - z.x0) / Double(z.count)
            let chipR = min(chipCap, step * 0.42)
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
                             with: .color(chipInk.opacity(0.55)))
                default:
                    let s = ToyShape.allCases[i]
                    ctx.fill(outline(s, cx, cy, chipR * 0.92, 0),
                             with: .color(chipInk.opacity(s == toy.shape ? 1 : 0.35)))
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
            let p = bumperPath(toy, b)
            ctx.stroke(p, with: .color(Color(argb: 0xff702929)),
                       style: StrokeStyle(lineWidth: 2, dash: [6, 5]))
            let hs = toy.handles(b)
            let hr = min(toy.w, toy.h) * 0.022
            ctx.fill(Path(ellipseIn: CGRect(x: hs.pull.x - hr, y: hs.pull.y - hr,
                                            width: hr * 2, height: hr * 2)),
                     with: .color(Color(argb: 0xff702929)))
            // A perfectly round bumper has no orientation to show; a pulled one
            // does, so the turn handle appears the moment it stops being a circle.
            if b.shape != .circle || !b.glyph.isEmpty || b.sx != b.sy {
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

        let selFamily = toy.drawerFamily()
        let selTone = toy.drawerTone()
        ctx.draw(Text(toy.drawerHeading())
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
                : kind == "scrim" ? "SCREEN TINT" : kind == "haptic" ? "HAPTICS" : "SOUND"
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
                case "haptic":
                    selected = c.i == toy.hapticIndex
                    fillColour = Color(argb: 0xff3a3a3c).opacity(0.13 + Palette.hapticScales[c.i] * 0.59)
                    label = Palette.hapticNames[c.i]
                default:
                    selected = c.i == toy.voiceIndex
                    let on = c.i == Voices.off ? 0.0
                        : 0.25 + 0.75 * Double(c.i) / Double(Palette.voiceNames.count - 1)
                    fillColour = Color(argb: 0xff3a3a3c).opacity(0.13 + on * 0.59)
                    label = Palette.voiceNames[c.i]
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
