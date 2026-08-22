import XCTest
@testable import NonsenseCore

/// The Kotlin suite, ported alongside the simulation it checks.
///
/// This exists because the port could not be compiled, let alone run, on the
/// machine it was written on — there is no Swift toolchain there. So the
/// verification travels with the code: `swift test` in `ios/` answers, in a
/// couple of seconds, whether the port is faithful. A constant transcribed
/// wrongly is the realistic failure mode of a hand port, and every one of them
/// is load-bearing for how the thing feels.
final class ToyTests: XCTestCase {

    let dt = 1.0 / 60.0

    /// A toy past the front door — it opens on its title screen and nothing
    /// moves while that is up — and with everything unlocked. These tests are
    /// about the simulation, not the shop; what the free tier can reach is
    /// tested separately, by asking for it explicitly.
    private func toy(_ w: Double = 1080, _ hh: Double = 1920, _ inset: Double = 48) -> Toy {
        let t = Toy()
        t.resize(w, hh, inset)
        t.screen = .play
        t.tier = .full
        return t
    }

    private func free() -> Toy { let t = toy(); t.tier = .free; return t }
    private func paid() -> Toy { toy() }

    /// Steps until `done` or the time runs out; returns whether it happened.
    @discardableResult
    private func run(_ t: Toy, _ seconds: Double, _ done: () -> Bool) -> Bool {
        for _ in 0..<Int(seconds / dt) {
            t.step(dt)
            if done() { return true }
        }
        return false
    }

    /// Turns the wheel through `turns` revolutions over `seconds`.
    private func sweepDial(_ t: Toy, turns: Double, seconds: Double,
                          samples: Int = 20, stallFrames: Int = 0) {
        let r = min(t.w, t.h) * 0.3
        let cx = t.w / 2
        let cy = t.h / 2
        t.grabDial(cx + r, cy)                        // angle 0
        let total = turns * 2 * Double.pi
        for i in 1...samples {
            let a = total * Double(i) / Double(samples)
            t.dragDial(cx + cos(a) * r, cy + sin(a) * r, seconds / Double(samples))
        }
        // A finger nearly always rests for a frame or two before it lifts.
        for _ in 0..<stallFrames {
            t.dragDial(cx + cos(total) * r, cy + sin(total) * r, dt)
        }
    }

    private func lum(_ c: UInt32) -> Double {
        let r = Double((c >> 16) & 0xff), g = Double((c >> 8) & 0xff), b = Double(c & 0xff)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    // MARK: palette

    func testPaletteIsFourteenFamiliesOfFourTonesAllDistinct() {
        XCTAssertEqual(Palette.names.count, 14)
        XCTAssertEqual(Palette.colors.count, Palette.names.count)
        let flat = Palette.colors.flatMap { $0 }
        XCTAssertEqual(flat.count, 56)
        XCTAssertEqual(Set(flat).count, 56, "two inks came out the same colour")
    }

    func testTheFirstNineFamiliesKeepTheirPlaces() {
        // A saved ink is an index. Appending is the only change that does not
        // quietly repaint a bumper table somebody built.
        let original = ["graphite", "bone", "oxblood", "rust", "ochre",
                        "moss", "teal", "slate", "plum"]
        XCTAssertEqual(Array(Palette.names.prefix(original.count)), original)
    }

    func testAStrikeFansOutAndLeansAwayFromAWall() {
        // Thrown outward from a corner, a single line met the wall in a tenth
        // of the screen and died there.
        XCTAssertEqual(Toy.boltArms(Toy.boltMinSpeed), Toy.boltArmsMin)
        XCTAssertEqual(Toy.boltArms(Toy.boltArmsFull * 2), Toy.boltArmsMax)
        XCTAssertGreaterThan(Toy.boltArms(2500), Toy.boltArms(700))

        let t = toy()
        t.mode = .bolt
        let px = t.w * 0.1, py = t.h * 0.12
        t.fireBolt(px, py, -1600, -1600)          // hard into the top-left corner
        run(t, 4) { t.bolts.isEmpty }
        var far = 0.0
        for e in t.etched { for n in e.nodes { far = max(far, hypot(n.x - px, n.y - py)) } }
        let diag = hypot(t.w, t.h)
        XCTAssertGreaterThan(far, diag * 0.35, "a corner strike went nowhere")

        let mid = toy()
        mid.mode = .bolt
        XCTAssertEqual(mid.boltAim(mid.w / 2, mid.h / 2, 1000, 0), 0, accuracy: 1e-3,
                       "nothing to lean away from here")
    }

    func testEveryFamilyRunsLightToDark() {
        for tones in Palette.colors {
            XCTAssertGreaterThan(lum(tones[0]), lum(tones[1]))
            XCTAssertGreaterThan(lum(tones[1]), lum(tones[2]))
            XCTAssertGreaterThan(lum(tones[2]), lum(tones[3]))
        }
    }

    func testTheOriginalColoursAreStillInThePalette() {
        let flat = Palette.colors.flatMap { $0 }.map { $0 & 0xffffff }
        for c: UInt32 in [0x3a3a3c, 0x702929, 0x465a78, 0x5c6e4a, 0xb08940] {
            XCTAssertTrue(flat.contains(c), "missing \(String(c, radix: 16))")
        }
    }

    // MARK: sizing recovers

    func testRadiiFollowTheFieldInsteadOfBeingFixedAtConstruction() {
        let t = Toy()
        XCTAssertEqual(t.ballR(), 0, accuracy: 0.001)      // nothing measured yet
        t.resize(1080, 1920, 48)
        XCTAssertGreaterThan(t.ballR(), 0)
        XCTAssertGreaterThan(t.dialR, 0)
        let small = t.ballR()
        t.resize(2000, 3000, 48)
        XCTAssertGreaterThan(t.ballR(), small, "radius must track a bigger field")
    }

    func testAZeroSizedMeasureIsIgnored() {
        let t = toy()
        let r = t.ballR()
        t.resize(0, 0, 0)
        XCTAssertEqual(t.ballR(), r, accuracy: 0.001)
    }

    // MARK: walls

    func testWallsContainEveryShapeAtEverySize() {
        for shape in Shape.allCases {
            for si in [0, Toy.sizes.count - 1] {
                let t = toy()
                t.mode = .ball
                t.shape = shape
                t.sizeIndex = si
                t.spin = 0.7; t.omega = 6
                t.bx = 500; t.by = 900
                t.vx = 2600; t.vy = 1900
                var worst = 0.0
                for _ in 0..<240 {
                    t.step(dt)
                    let r = t.ballR()
                    let xs: [Double], ys: [Double]
                    if let pts = t.ballPoints() {
                        xs = pts.map(\.x); ys = pts.map(\.y)
                    } else {
                        xs = [t.bx - r, t.bx + r]; ys = [t.by - r, t.by + r]
                    }
                    let out = max(-xs.min()!, xs.max()! - t.w, -ys.min()!, ys.max()! - t.h)
                    if out > worst { worst = out }
                }
                XCTAssertLessThan(worst, 2, "\(shape) size \(si) escaped by \(worst)")
            }
        }
    }

    func testABallRestingAgainstAWallIsNotPinnedThere() {
        let t = toy()
        t.shape = .square
        t.bx = t.ballR(); t.by = 900
        t.vx = 600; t.vy = 0          // moving away from the left wall
        t.omega = 8
        run(t, 0.5) { false }
        XCTAssertGreaterThan(t.bx, t.ballR() * 3, "should have travelled away from the wall")
    }

    // MARK: bumpers

    func testEveryBallShapeIsDeflectedByEveryBumperShape() {
        for ballShape in Shape.allCases {
            for bumpShape in Shape.allCases {
                let t = toy()
                t.mode = .bumpers
                t.paintOnBumpers = false
                t.table = [Bumper(nx: 0.5, ny: 0.55, size: 0.12, shape: bumpShape, rot: 0.2)]
                t.shape = ballShape
                t.sizeIndex = Toy.defaultSize
                t.bx = t.w / 2; t.by = t.h * 0.12
                t.vx = 0; t.vy = 1400
                let deflected = run(t, 3) { abs(atan2(t.vx, t.vy)) > 0.26 || t.vy < 0 }
                XCTAssertTrue(deflected, "\(ballShape) not deflected by \(bumpShape)")
            }
        }
    }

    func testAFastFlickDoesNotTunnelThroughABumper() {
        for shape in Shape.allCases {
            let t = toy()
            t.mode = .bumpers
            t.paintOnBumpers = false
            t.table = [Bumper(nx: 0.5, ny: 0.5, size: 0.10, shape: .square, rot: 0)]
            t.shape = shape
            t.sizeIndex = 1                       // small, so it could slip past
            t.bx = t.w / 2; t.by = t.h * 0.1
            t.vx = 0; t.vy = 5200
            let hit = run(t, 2) { t.vy < 0 || abs(t.vx) > 40 }
            XCTAssertTrue(hit, "\(shape) tunnelled straight through")
        }
    }

    func testNoShapeGetsStuckInsideABumper() {
        for shape in Shape.allCases {
            let t = toy()
            t.mode = .bumpers
            t.paintOnBumpers = false
            t.table = [Bumper(nx: 0.5, ny: 0.5, size: 0.14, shape: .pentagon, rot: 0.3)]
            t.shape = shape
            t.sizeIndex = 4
            t.bx = t.w / 2; t.by = t.h / 2       // starting overlapped
            t.vx = 1800; t.vy = 900
            run(t, 1.5) { false }
            let b = t.table[0]
            let gp = t.bumperPoints(b)!
            let overlap: Double
            if let bp = t.ballPoints() {
                overlap = Geom.satPolyPoly(bp, gp)?.depth ?? 0
            } else {
                overlap = Geom.circleVsPoly(t.bx, t.by, t.ballR(), gp)?.depth ?? 0
            }
            XCTAssertLessThan(overlap, 2, "\(shape) still overlapping by \(overlap)")
        }
    }

    func testANonRoundBallPicksUpSpinFromAnImpact() {
        let t = toy()
        t.mode = .ball
        t.shape = .square
        t.bx = t.w / 2; t.by = t.h / 2
        t.vx = 1500; t.vy = 400
        run(t, 1.2) { abs(t.omega) > 0.1 }
        XCTAssertGreaterThan(abs(t.omega), 0.1, "a square should tumble")
    }

    // MARK: catching

    func testCatchingIsMeasuredAgainstTheRealOutline() {
        let t = toy()
        t.mode = .ball
        t.mustCatch = true
        t.shape = .bar
        t.sizeIndex = Toy.defaultSize
        t.bx = t.w / 2; t.by = t.h / 2
        t.spin = 0
        let r = t.ballR()
        // a bar is 1.35 long and 0.5 across, on a unit circumradius
        XCTAssertTrue(t.withinCatch(t.bx + r * 1.2, t.by), "should be caught along the bar")
        XCTAssertFalse(t.withinCatch(t.bx, t.by + r * 1.2), "and missed across it")
    }

    func testASmallBallGetsSlackAndABigOneDoesNot() {
        let small = toy(); small.sizeIndex = 0
        let big = toy(); big.sizeIndex = Toy.sizes.count - 1
        XCTAssertGreaterThan(small.catchSlack(), 0, "a bead must not be a pixel-hunt")
        XCTAssertEqual(big.catchSlack(), 0, accuracy: 0.001, "a grapefruit you simply have to hit")
    }

    func testAMissedGrabLeavesTheBallAlone() {
        let t = toy()
        t.mode = .ball
        t.mustCatch = true
        t.bx = 200; t.by = 200
        t.vx = 500; t.vy = 0
        let caught = t.grab(900, 900, 12345)
        XCTAssertFalse(caught)
        XCTAssertFalse(t.dragging)
        XCTAssertEqual(t.vx, 500, accuracy: 0.001, "the ball carries on")
        XCTAssertEqual(t.missAt, 12345, accuracy: 0.001)
    }

    func testACatchHoldsTheBallWhereItWasCaught() {
        let t = toy()
        t.mode = .ball
        t.mustCatch = true
        t.bx = 500; t.by = 500
        let r = t.ballR()
        XCTAssertTrue(t.grab(500 + r * 0.5, 500, 0))
        XCTAssertEqual(t.bx, 500, accuracy: 0.001, "must not snap to the finger")
        XCTAssertEqual(t.by, 500, accuracy: 0.001)
    }

    // MARK: the opening screen

    func testTheAppOpensOnItsOwnName() {
        if case .title = Toy().screen {} else { XCTFail("should open on the title") }
    }

    func testNothingMovesWhileTheTitleIsUp() {
        let t = toy()
        t.bx = 300; t.by = 400; t.vx = 2000; t.vy = 900
        t.screen = .title
        run(t, 1) { false }
        XCTAssertEqual(t.bx, 300, accuracy: 0.001)
        XCTAssertEqual(t.by, 400, accuracy: 0.001)
    }

    func testEveryMenuRowIsOnScreenClearOfTheHomeIndicator() {
        for (w, hh, inset) in [(1080.0, 1920.0, 48.0), (1440.0, 3120.0, 120.0), (720.0, 1280.0, 0.0)] {
            let t = Toy(); t.resize(w, hh, inset)
            let rows = t.menuRows()
            XCTAssertEqual(rows.count, t.menuItems().count)
            let floorY = t.viewH - t.insetBottom
            for c in rows {
                XCTAssertGreaterThan(c.y, t.titleBaseline(), "row \(c.i) above the title at \(w)x\(hh)")
                XCTAssertLessThanOrEqual(c.y + c.h, floorY, "row \(c.i) under the bar at \(w)x\(hh)")
                XCTAssertTrue(c.x > 0 && c.x + c.w < t.w, "row \(c.i) off the side")
            }
            for i in 1..<rows.count {
                XCTAssertGreaterThan(rows[i].y, rows[i - 1].y + rows[i - 1].h, "rows overlap")
            }
        }
    }

    func testEachMenuRowAnswersToItsOwnMiddle() {
        let t = toy()
        let items = t.menuItems()
        for c in t.menuRows() {
            XCTAssertEqual(t.menuHit(c.x + c.w / 2, c.y + c.h / 2), items[c.i].key)
        }
        XCTAssertNil(t.menuHit(t.w / 2, t.titleBaseline() - 10))
    }

    func testTheMenuOpensWhatItNames() {
        for (key, mode) in [("ball", Mode.ball), ("dial", .dial), ("bumpers", .bumpers), ("paint", .paint)] {
            let t = Toy(); t.resize(1080, 1920, 48); t.tier = .full
            XCTAssertTrue(t.tapMenu(key))
            XCTAssertEqual(t.mode, mode)
            if case .play = t.screen {} else { XCTFail("should be playing") }
            XCTAssertFalse(t.drawerOpen)
        }
        let t = Toy(); t.resize(1080, 1920, 48); t.tier = .full
        XCTAssertTrue(t.tapMenu("ink"))
        XCTAssertTrue(t.drawerOpen, "ink & canvas should open the palette")
    }

    func testThereIsADrawnWayBackToTheMenuFromEveryMode() {
        for mode in Mode.allCases {
            let t = toy()
            t.mode = mode
            let labels = t.modeLabels()
            XCTAssertTrue(labels.contains("menu"), "no menu in \(mode)")
            let cell = t.modeCells()[labels.firstIndex(of: "menu")!]
            XCTAssertEqual(t.modeHit(cell.x + cell.w / 2, cell.y + cell.h / 2), "menu")
            t.drawerOpen = true
            t.tapMode("menu")
            if case .title = t.screen {} else { XCTFail("should be back at the title") }
            XCTAssertFalse(t.drawerOpen, "going back should close the palette")
        }
    }

    // MARK: the dial

    func testAFlickLeavesTheWheelTurningForSeconds() {
        let t = toy()
        t.mode = .dial
        sweepDial(t, turns: 0.5, seconds: 0.4)
        t.releaseDial()
        XCTAssertGreaterThan(abs(t.dialOmega), 4, "a flick should spin it")
        run(t, 3) { false }
        XCTAssertGreaterThan(abs(t.dialOmega), 1.5, "still turning after 3s")
        XCTAssertTrue(run(t, 45) { t.dialOmega == 0 }, "but it does stop")
    }

    func testAFingerThatStallsBeforeItLiftsStillThrowsTheWheel() {
        let t = toy()
        t.mode = .dial
        sweepDial(t, turns: 0.5, seconds: 0.4, stallFrames: 2)
        t.releaseDial()
        // Reading only the final sample would hand back a wheel at rest,
        // which is what made the old dial feel dead.
        XCTAssertGreaterThan(abs(t.dialOmega), 2, "stalled flick died")
    }

    func testTheWheelCannotSpinFastEnoughToStrobe() {
        let t = toy()
        t.mode = .dial
        sweepDial(t, turns: 6, seconds: 0.2, samples: 60)
        t.releaseDial()
        XCTAssertLessThanOrEqual(abs(t.dialOmega), Toy.maxDialOmega + 0.001)
        // Ribs must pass the eye slower than the screen redraws.
        let ribsPerSecond = Toy.maxDialOmega / (2 * Double.pi) * Double(Toy.dialRibs)
        XCTAssertLessThan(ribsPerSecond, 55, "\(ribsPerSecond) rib passes a second")
    }

    func testOneTurnIsOneClickPerRib() {
        let t = toy()
        t.mode = .dial
        let before = t.dialDetent
        sweepDial(t, turns: 1, seconds: 2, samples: 120)
        // Where a turn starts relative to a rib decides whether the last one
        // lands inside the turn or just past it, so one either way is right.
        let clicks = t.dialDetent - before
        XCTAssertLessThanOrEqual(abs(clicks - t.dialRibs), 1, "\(clicks) clicks for \(t.dialRibs) ribs")
    }

    func testACoastingWheelKeepsClickingAndAStillOneDoesNot() {
        let t = toy()
        t.mode = .dial
        sweepDial(t, turns: 0.4, seconds: 0.35)
        t.releaseDial()
        let atRelease = t.dialDetent
        run(t, 1) { false }
        XCTAssertGreaterThan(t.dialDetent, atRelease, "coasting should click")
        XCTAssertTrue(run(t, 45) { t.dialOmega == 0 }, "should settle")
        let settled = t.dialDetent
        run(t, 2) { false }
        XCTAssertEqual(t.dialDetent, settled, "a stopped wheel must be silent")
    }

    func testSpinningEitherWayClicksTheSame() {
        let forward = toy(); forward.mode = .dial
        let back = toy(); back.mode = .dial
        sweepDial(forward, turns: 1, seconds: 2, samples: 120)
        sweepDial(back, turns: -1, seconds: 2, samples: 120)
        XCTAssertLessThanOrEqual(abs(forward.dialDetent - back.dialDetent), 1)
        XCTAssertGreaterThan(forward.dialOmega, 0)
        XCTAssertLessThan(back.dialOmega, 0)
    }

    // MARK: canvases

    func testSlateIsTheDefaultAndSheerTheOnlySeeThroughGround() {
        let t = toy()
        // Slate rather than sheer: on iOS "sheer" is only the app's own dark
        // ground anyway, and a ground you can see is a better first
        // impression than one you cannot.
        XCTAssertEqual(t.canvasIndex, Toy.defaultCanvas)
        XCTAssertFalse(t.sheer(), "the app should not open see-through")
        t.canvasIndex = 0
        XCTAssertTrue(t.sheer(), "but sheer is still there")
        for i in 1..<Palette.canvasNames.count {
            t.canvasIndex = i
            XCTAssertFalse(t.sheer(), "canvas \(i) should be solid")
            XCTAssertEqual((t.canvasColor() >> 24) & 0xff, 0xff, "canvas \(i) must be opaque")
        }
    }

    func testThereIsACanvasNamedForEveryColourAndTheyDiffer() {
        XCTAssertEqual(Palette.canvasNames.count, Palette.canvasColors.count)
        let solid = Array(Palette.canvasColors.dropFirst())
        XCTAssertEqual(solid.count, Set(solid).count)
        XCTAssertTrue(solid.contains { lum($0) > 200 })
        XCTAssertTrue(solid.contains { lum($0) < 40 })
    }

    // MARK: the palette drawer

    func testEveryDrawerRowCanBeReachedAndSetsWhatItNames() {
        let t = toy()
        let b = t.drawerBox()
        for kind in t.drawerRows {
            let y = t.drawerRowY(b, kind)
            for chip in t.drawerChips(y, t.drawerRowCount(kind), b) {
                let hit = t.drawerHit(chip.x + chip.w / 2, chip.y + chip.h / 2)
                XCTAssertEqual(hit, kind, "row \(kind) chip \(chip.i)")
                let got: Int
                switch kind {
                case "alpha": got = t.inkAlphaIndex
                case "canvas": got = t.canvasIndex
                case "scrim": got = t.scrimIndex
                default: got = t.hapticIndex
                }
                XCTAssertEqual(got, chip.i, "row \(kind) chip \(chip.i)")
            }
        }
    }

    func testTheDrawerFitsOnScreen() {
        for (w, hh, inset) in [(1080.0, 1920.0, 48.0), (1440.0, 3120.0, 120.0), (720.0, 1280.0, 0.0)] {
            let t = Toy(); t.resize(w, hh, inset)
            let b = t.drawerBox()
            XCTAssertGreaterThanOrEqual(b.y, 0, "off the top at \(w)x\(hh)")
            XCTAssertLessThanOrEqual(b.y + b.h, t.viewH - t.insetBottom, "under the bar at \(w)x\(hh)")
            XCTAssertTrue(b.x >= 0 && b.x + b.w <= t.w, "off the side")
            XCTAssertLessThanOrEqual(b.hy + b.rowH, b.y + b.h, "last row spills")
        }
    }

    // MARK: bumpers have their own ink

    func testTheFactoryTableArrivesInFiveDifferentColours() {
        let t = toy()
        let inks = t.table.map { t.bumperColor($0) }
        XCTAssertEqual(inks.count, 5)
        XCTAssertEqual(Set(inks).count, 5, "each one should be its own colour")
        for b in t.table {
            XCTAssertTrue(b.family >= 0 && b.family < Palette.colors.count)
            XCTAssertTrue(b.tone >= 0 && b.tone < Palette.toneMix.count)
            XCTAssertEqual((t.bumperColor(b) >> 24) & 0xff, 0xff)
        }
    }

    func testATableSurvivesBeingWrittenOutAndReadBack() {
        let t = toy()
        t.table[0].family = 8
        t.table[0].tone = 3
        t.table[2].shape = .triangle
        t.table[2].rot = 0.75
        let back = t.decodeTable(t.encodeTable())
        XCTAssertEqual(back.count, t.table.count)
        for i in t.table.indices {
            XCTAssertEqual(back[i].family, t.table[i].family)
            XCTAssertEqual(back[i].tone, t.table[i].tone)
            XCTAssertEqual(back[i].shape, t.table[i].shape)
            XCTAssertEqual(back[i].nx, t.table[i].nx, accuracy: 1e-6)
            XCTAssertEqual(back[i].rot, t.table[i].rot, accuracy: 1e-6)
        }
    }

    func testTablesSavedBeforeBumpersHadColourStillLoad() {
        let t = toy()
        let old = "0.25,0.3,0.055,CIRCLE,0.0;0.75,0.72,0.06,BAR,1.2"
        let back = t.decodeTable(old)
        XCTAssertEqual(back.count, 2, "an arrangement is not worth discarding over a new field")
        XCTAssertEqual(back[0].shape, .circle)
        XCTAssertEqual(back[1].shape, .bar)
        for b in back {
            XCTAssertEqual(b.family, 0)
            XCTAssertEqual(b.tone, 2)
        }
        XCTAssertEqual(t.decodeTable("nonsense,not,a,row").count, 0)
    }

    func testTheToolbarCanAddDeleteReshapeAndRecolour() {
        let t = toy()
        t.mode = .bumpers
        t.editing = true
        let before = t.table.count

        t.doToolbar("add")
        XCTAssertEqual(t.table.count, before + 1)
        XCTAssertEqual(t.selected, t.table.count - 1, "the new one should be selected")

        let shape = t.table[t.selected].shape
        t.doToolbar("shape")
        XCTAssertNotEqual(t.table[t.selected].shape, shape, "shape should change")

        // colour opens the whole palette rather than cycling nine families
        t.doToolbar("ink")
        XCTAssertTrue(t.drawerOpen)
        XCTAssertEqual(t.drawerTarget, .bumper)
        XCTAssertEqual(t.targetBumperIndex(), t.selected)

        let box = t.drawerBox()
        let family = 6, tone = 1
        let hit = t.drawerHit(box.gx + box.cell * (Double(family) + 0.5),
                              box.gy + box.cell * (Double(tone) + 0.5))
        XCTAssertEqual(hit, "bumper")
        XCTAssertEqual(t.table[t.selected].family, family)
        XCTAssertEqual(t.table[t.selected].tone, tone)
        XCTAssertEqual(t.inkFamily, 0, "and the ink itself must not have moved")
        XCTAssertEqual(t.inkTone, 2)

        t.closeDrawer()
        t.doToolbar("del")
        XCTAssertEqual(t.table.count, before)
        XCTAssertEqual(t.selected, -1)
        XCTAssertFalse(t.drawerOpen, "the drawer cannot stay pointed at a deleted bumper")
    }

    func testEveryToolbarButtonIsReachableAtItsOwnMiddle() {
        let t = toy()
        for c in t.toolbarButtons() {
            XCTAssertEqual(t.toolbarHit(c.x + c.w / 2, c.y + c.h / 2), t.toolbarLabels[c.i])
        }
        for verb in ["add", "del", "shape", "ink"] {
            XCTAssertTrue(t.toolbarLabels.contains(verb), "no way to \(verb)")
        }
    }

    func testARecolouredBumperStillBouncesTheBall() {
        let t = toy()
        t.mode = .bumpers
        t.paintOnBumpers = false
        t.table = [Bumper(nx: 0.5, ny: 0.55, size: 0.12, shape: .circle, rot: 0, family: 4, tone: 0)]
        t.bx = t.w / 2; t.by = t.h * 0.12
        t.vx = 0; t.vy = 1400
        XCTAssertTrue(run(t, 3) { t.vy < 0 }, "colour is paint, not physics")
    }

    // MARK: what is free and what is bought

    func testTheBallIsTheFreeToyAndEveryOtherOneIsTheUnlock() {
        let t = free()
        XCTAssertFalse(t.modeLocked(.ball), "the ball is the free one")
        for m in [Mode.dial, .bumpers, .bolt, .paint] {
            XCTAssertTrue(t.modeLocked(m), "\(m) should be paid")
        }
        // The free ball is the whole ball, not a sample of it.
        XCTAssertEqual(Toy.sizes.count, 8)
        XCTAssertEqual(Shape.allCases.count, 6)
        XCTAssertFalse(t.familyLocked(0))

        t.mode = .ball
        t.bx = t.w / 2; t.by = t.h * 0.1
        t.vx = 0; t.vy = 1600
        XCTAssertTrue(run(t, 3) { t.vy < 0 || abs(t.vx) > 40 }, "the free tier must actually play")
    }

    func testEveryWayIntoALockedToyOpensTheShopInstead() {
        // There are three doors into a toy — the front door, the mode row and
        // the cycle gesture — and a gate on two of them is a hole.
        for key in ["dial", "bumpers", "bolt", "paint"] {
            let menu = free()
            menu.screen = .title
            XCTAssertTrue(menu.tapMenu(key))
            if case .paywall = menu.screen {} else { XCTFail("\(key) from the front door") }
            XCTAssertEqual(menu.mode, .ball)

            let row = free()
            row.tapMode(key)
            if case .paywall = row.screen {} else { XCTFail("\(key) from the row") }
            XCTAssertEqual(row.mode, .ball)
            XCTAssertTrue(row.menuLocked(key), "\(key) should wear a padlock")
        }
        // The gesture never ambushes you with a shop: it just stays put.
        let t = free()
        for _ in 0..<7 { t.cycleMode() }
        XCTAssertEqual(t.mode, .ball)
        if case .play = t.screen {} else { XCTFail("should still be playing") }
    }

    func testBuyingUnlocksExactlyWhatThePaywallPromised() {
        let t = free()
        XCTAssertTrue(t.modeLocked(.paint))
        XCTAssertTrue(t.editLocked())
        XCTAssertTrue(t.familyLocked(Palette.names.count - 1))
        XCTAssertTrue(t.canvasLocked(Palette.canvasNames.count - 1))

        t.unlock()
        XCTAssertEqual(t.tier, .full)
        XCTAssertFalse(t.modeLocked(.paint))
        XCTAssertFalse(t.editLocked())
        for i in Palette.names.indices { XCTAssertFalse(t.familyLocked(i), "family \(i)") }
        for i in Palette.canvasNames.indices { XCTAssertFalse(t.canvasLocked(i), "canvas \(i)") }
    }

    func testTheFreeTierKeepsThreeColoursAndThreeGrounds() {
        let t = free()
        XCTAssertEqual((0..<Palette.names.count).filter { !t.familyLocked($0) }.count, 3)
        // Sheer, paper, and the slate the app opens on: a default nobody can
        // use is not a default.
        XCTAssertEqual((0..<Palette.canvasNames.count).filter { !t.canvasLocked($0) }.count, 3)
        XCTAssertFalse(t.canvasLocked(Toy.defaultCanvas), "the ground it opens on")
        XCTAssertFalse(t.familyLocked(t.inkFamily))
        XCTAssertFalse(t.canvasLocked(t.canvasIndex))
        XCTAssertFalse(t.modeLocked(t.mode))
    }

    func testEveryLockedControlOpensThePaywallRatherThanDoingNothing() {
        // the menu
        do {
            let t = free()
            t.tapMenu("paint")
            if case .paywall = t.screen {} else { XCTFail("menu paint should sell") }
        }
        // the mode row
        do {
            let t = free()
            t.screen = .play
            t.tapMode("paint")
            if case .paywall = t.screen {} else { XCTFail("mode row paint should sell") }
            XCTAssertNotEqual(t.mode, .paint, "and must not have switched anyway")
        }
        do {
            let t = free()
            t.screen = .play
            t.mode = .bumpers
            t.tapMode("edit")
            if case .paywall = t.screen {} else { XCTFail("edit should sell") }
            XCTAssertFalse(t.editing)
        }
        // the strip
        do {
            let t = free()
            t.screen = .play
            let z = t.stripZones()[0]
            let step = (z.x1 - z.x0) / Double(z.count)
            t.stripTap(z.x0 + step * (Double(Palette.names.count) - 0.5))
            if case .paywall = t.screen {} else { XCTFail("the strip should sell") }
            XCTAssertEqual(t.inkFamily, 0, "and must not have taken the colour")
        }
        // the drawer grid, and the canvas row
        do {
            let t = free()
            t.screen = .play
            t.drawerOpen = true
            let b = t.drawerBox()
            XCTAssertEqual(t.drawerHit(b.gx + b.cell * 8.5, b.gy + b.cell * 0.5), "locked")
            XCTAssertEqual(t.inkFamily, 0)
            if case .paywall = t.screen {} else { XCTFail("the grid should sell") }
        }
        do {
            let t = free()
            t.screen = .play
            t.drawerOpen = true
            let b = t.drawerBox()
            let chip = t.drawerChips(t.drawerRowY(b, "canvas"), Palette.canvasNames.count, b).last!
            XCTAssertEqual(t.drawerHit(chip.x + chip.w / 2, chip.y + chip.h / 2), "locked")
            XCTAssertEqual(t.canvasIndex, Toy.defaultCanvas,
                           "and must not have taken the ground")
            if case .paywall = t.screen {} else { XCTFail("the canvas row should sell") }
        }
    }

    func testNothingLockedIsReachableByAccident() {
        // Double tapping mid-fidget should not ambush you with a shop.
        let t = free()
        t.screen = .play
        t.mode = .bumpers
        for _ in 0..<12 {
            t.cycleMode()
            XCTAssertFalse(t.modeLocked(t.mode), "cycled into \(t.mode)")
            if case .play = t.screen {} else { XCTFail("must not have opened the shop") }
        }
    }

    func testTheCodeUnlocksAndAWrongOneDoesNot() {
        let t = free()
        t.showPaywall()
        XCTAssertFalse(t.codeOpen, "the keypad is not up until it is asked for")
        t.openCode()
        XCTAssertTrue(t.codeOpen)

        // wrong, and it says so rather than silently doing nothing
        for c in "0000" { t.typeCode(String(c)) }
        XCTAssertTrue(t.codeWrong, "a wrong code should be visible as wrong")
        XCTAssertEqual(t.codeEntry, "", "and it clears itself to try again")
        XCTAssertEqual(t.tier, .free)
        XCTAssertTrue(t.codeOpen, "still on the keypad")

        // right
        for c in "1836" { t.typeCode(String(c)) }
        XCTAssertEqual(t.tier, .full)
        XCTAssertFalse(t.codeOpen, "and the keypad puts itself away")
        for m in [Mode.ball, .dial, .bumpers, .bolt, .paint] {
            XCTAssertFalse(t.modeLocked(m), "\(m)")
        }
    }

    func testTheCodeIsOnlyEverStoredAsAHash() {
        // Obfuscation rather than security, but reading the source should not
        // hand the code over.
        XCTAssertNotEqual(String(Toy.codeHashValue), "1836")
        XCTAssertEqual(Toy.codeHash("1836"), Toy.codeHashValue)
        XCTAssertNotEqual(Toy.codeHash("1837"), Toy.codeHashValue)
        XCTAssertNotEqual(Toy.codeHash(""), Toy.codeHashValue)
        XCTAssertEqual(Toy.codeLength, 4, "four digits")
    }

    func testTheKeypadHasAKeyUnderEveryDigitAndFitsTheScreen() {
        let t = free()
        t.showPaywall()
        t.openCode()
        let keys = t.keypadKeys()
        let cells = t.keypadCells()
        XCTAssertEqual(keys.count, cells.count)
        for c in cells where !keys[c.i].isEmpty {
            XCTAssertEqual(t.keypadHit(c.x + c.w / 2, c.y + c.h / 2), keys[c.i])
        }
        XCTAssertNil(t.keypadHit(cells[9].x + cells[9].w / 2, cells[9].y + cells[9].h / 2),
                     "the blank is not a button")
        let last = cells[cells.count - 1]
        XCTAssertLessThanOrEqual(last.y + last.h, t.viewH, "the keypad runs off the bottom")
        XCTAssertGreaterThan(cells[0].y, t.viewH * 0.3, "or off the top")
    }

    func testBackspaceTakesADigitOffRatherThanStartingOver() {
        let t = free()
        t.showPaywall()
        t.openCode()
        t.typeCode("1"); t.typeCode("8"); t.typeCode("9")
        XCTAssertEqual(t.codeEntry, "189")
        t.typeCode("del")
        XCTAssertEqual(t.codeEntry, "18")
        t.typeCode("3"); t.typeCode("6")
        XCTAssertEqual(t.tier, .full)
    }

    func testThePaywallSaysWhatItCostsAndThatItRenews() {
        // Apple rejects a subscription paywall that leaves the terms to the
        // store sheet, and it is the honest thing to do anyway.
        let t = free()
        let terms = t.subscriptionTerms().joined(separator: " ").lowercased()
        XCTAssertTrue(terms.contains("1.99"), "no price on the screen that sells it")
        XCTAssertTrue(terms.contains("month"), "does not say how often")
        XCTAssertTrue(terms.contains("renew"), "does not say it renews")
        XCTAssertTrue(terms.contains("cancel"), "does not say how to stop")

        t.priceText = "£1.79"
        XCTAssertTrue(t.subscriptionTerms()[0].contains("£1.79"),
                      "the store's own price should win when it arrives")
        XCTAssertTrue(t.unlockLabel().contains("£1.79"))
    }

    func testThePaywallGoesBackWhereItCameFrom() {
        let t = free()
        t.screen = .play
        t.showPaywall()
        if case .paywall = t.screen {} else { XCTFail("should be selling") }
        t.showPaywall()                    // a second lock while it is already up
        t.dismissPaywall()
        if case .play = t.screen {} else { XCTFail("should return to play, not to the title") }

        let m = free()
        m.screen = .title
        m.tapMenu("unlock")
        if case .paywall = m.screen {} else { XCTFail("unlock should sell") }
        m.dismissPaywall()
        if case .title = m.screen {} else { XCTFail("should return to the title") }
    }

    func testBuyingFromThePaywallPutsYouBackInTheToy() {
        let t = free()
        t.screen = .play
        t.tapMode("paint")
        if case .paywall = t.screen {} else { XCTFail("should be selling") }
        t.unlock()
        if case .play = t.screen {} else { XCTFail("should be back in the toy") }
    }

    func testARefundTakesBackWhatItPaidFor() {
        let t = paid()
        t.mode = .paint
        t.inkFamily = Palette.names.count - 1
        t.canvasIndex = Palette.canvasNames.count - 1
        t.editing = true
        t.selected = 1

        t.tier = .free
        t.clampToTier()
        XCTAssertEqual(t.mode, .ball)
        XCTAssertFalse(t.familyLocked(t.inkFamily))
        XCTAssertFalse(t.canvasLocked(t.canvasIndex))
        XCTAssertFalse(t.editing)
        // but the table they built keeps its shipped colours
        XCTAssertEqual(t.table.count, 5)
        XCTAssertGreaterThan(Set(t.table.map(\.family)).count, 1)
    }

    func testTheFrontDoorNamesThePriceInsteadOfHidingIt() {
        let t = free()
        XCTAssertTrue(t.menuItems().map(\.key).contains("unlock"))
        XCTAssertTrue(t.menuLocked("paint"))
        for c in t.menuRows() {
            XCTAssertEqual(t.menuHit(c.x + c.w / 2, c.y + c.h / 2), t.menuItems()[c.i].key)
        }
        XCTAssertFalse(paid().menuItems().map(\.key).contains("unlock"), "not nagged once paid")
        XCTAssertFalse(paid().menuLocked("paint"))
    }

    func testThePaywallFitsOnScreenAndEveryButtonAnswers() {
        for (w, hh, inset) in [(1080.0, 1920.0, 48.0), (1440.0, 3120.0, 120.0), (720.0, 1280.0, 0.0)] {
            let t = Toy(); t.resize(w, hh, inset)
            let buttons = t.paywallButtons()
            XCTAssertEqual(buttons.count, 3)
            let floorY = t.viewH - t.insetBottom
            for c in buttons {
                XCTAssertLessThanOrEqual(c.y + c.h, floorY, "under the bar at \(w)x\(hh)")
                XCTAssertTrue(c.x >= 0 && c.x + c.w <= t.w, "off the side")
                XCTAssertEqual(t.paywallHit(c.x + c.w / 2, c.y + c.h / 2), t.paywallLabels[c.i])
            }
            for i in 1..<buttons.count {
                XCTAssertGreaterThanOrEqual(buttons[i].y, buttons[i - 1].y + buttons[i - 1].h)
            }
            XCTAssertGreaterThan(buttons[0].y, t.viewH * 0.55, "must clear the promises above it")
        }
    }

    func testTheSubscribeButtonSaysThePriceBeforeAndAfterTheStore() {
        let t = free()
        XCTAssertTrue(t.unlockLabel().contains(Toy.priceFallback),
                      "should stand in with the list price")
        XCTAssertTrue(t.unlockLabel().contains("/mo"), "and say how often")
        t.priceText = "£2.99"
        XCTAssertTrue(t.unlockLabel().contains("£2.99"))
        XCTAssertEqual(t.paywallLines().count, 5)
        XCTAssertFalse(t.paywallLines().contains { $0.contains("bought once") },
                       "the promises must not still say it is bought once")
    }

    // MARK: haptics

    func testHapticsAreOnByDefaultAndCanBeSilenced() {
        let t = toy()
        XCTAssertGreaterThan(t.hapticScale(), 0, "should arrive switched on")
        t.hapticIndex = 0
        XCTAssertEqual(t.hapticScale(), 0, "off must mean nothing at all")
        XCTAssertEqual(Palette.hapticNames.count, Palette.hapticScales.count)
    }

    func testAWallKnockIsWorthFeeling() {
        let t = toy()
        t.mode = .ball
        t.bx = t.w / 2; t.by = t.h / 2
        t.vx = 3000; t.vy = 0
        let before = t.bounceCount
        XCTAssertTrue(run(t, 1) { t.bounceCount > before }, "should reach the wall")
        XCTAssertGreaterThan(t.impactStrength(), 0.6, "a hard hit should be near full strength")
        XCTAssertTrue(t.lastImpactWall, "and it should know it was a wall")
    }

    func testABallComingToRestStopsProducingImpactsToFeel() {
        let t = toy()
        t.mode = .ball
        t.bx = t.w / 2; t.by = t.h / 2
        t.vx = 2500; t.vy = 1200
        XCTAssertTrue(run(t, 12) { t.vx == 0 && t.vy == 0 }, "should have settled")
        let settled = t.bounceCount
        run(t, 3) { false }
        XCTAssertEqual(t.bounceCount, settled, "a still ball must not keep buzzing")
    }

    // MARK: lightning

    private func struck(_ t: Toy? = nil) -> Toy {
        let toyv = t ?? toy()
        toyv.mode = .bolt
        toyv.fireBolt(toyv.w / 2, toyv.h / 2, 1800, -900)
        return toyv
    }

    /// Runs until every bolt has arrived, then hands back what it etched.
    @discardableResult
    private func arrived(_ t: Toy) -> [Etched] {
        run(t, 4) { t.bolts.isEmpty && !t.etched.isEmpty }
        return t.etched
    }

    /// The longest of the arms the finger threw. A strike is a fan now, so
    /// "the root" is several paths and the interesting one is whichever had
    /// room to run.
    private func root(_ t: Toy) -> Etched {
        t.etched.filter { $0.gen == 0 }.max { $0.nodes.count < $1.nodes.count }!
    }

    /// The etching that began where this bolt did, matched on its first kink.
    private func etchingOf(_ t: Toy, _ start: Pt) -> Etched {
        t.etched.first {
            $0.nodes.count > 1 && abs($0.nodes[1].x - start.x) < 1e-4
                && abs($0.nodes[1].y - start.y) < 1e-4
        }!
    }

    func testAFlickStrikesAndANudgeDoesNot() {
        let t = toy()
        t.mode = .bolt
        XCTAssertFalse(t.fireBolt(100, 100, 60, 40), "a slow drag is not a strike")
        XCTAssertEqual(t.bolts.count, 0)
        XCTAssertTrue(t.fireBolt(100, 100, 1500, 0))
        // A strike is a fan, not a line: several arms leave at once.
        XCTAssertEqual(t.bolts.count, Toy.boltArms(1500))
    }

    func testABoltLeavesFasterThanTheFingerButNotWithoutLimit() {
        let t = toy()
        t.mode = .bolt
        t.fireBolt(100, 100, 1000, 0)
        XCTAssertGreaterThan(t.bolts[0].vx, 1000, "should outrun the flick")

        let fast = toy()
        fast.mode = .bolt
        fast.fireBolt(100, 100, 40000, 30000)
        XCTAssertLessThanOrEqual(hypot(fast.bolts[0].vx, fast.bolts[0].vy), Toy.boltMaxSpeed + 1,
                                 "capped, or it crosses the field between frames")
    }

    func testABoltHittingAWallRegistersAnImpactToFeel() {
        let t = toy()
        t.mode = .bolt
        let before = t.bounceCount
        t.fireBolt(t.w / 2, t.h / 2, 3000, 0)      // straight at the right wall
        XCTAssertTrue(run(t, 1) { t.bounceCount > before }, "should reach the wall")
        XCTAssertTrue(t.lastImpactWall, "and it must read as a wall")
        XCTAssertGreaterThan(t.impactStrength(), 0.5, "hard enough to be worth feeling")
    }

    func testNoBoltEverLeavesTheField() {
        let t = toy()
        t.mode = .bolt
        for a in 0..<12 {
            let ang = Double(a) * Double.pi / 6
            t.fireBolt(t.w / 2, t.h / 2, cos(ang) * 6000, sin(ang) * 6000)
        }
        var worst = 0.0
        for _ in 0..<140 {
            t.step(dt)
            for b in t.bolts {
                worst = max(worst, -b.x, b.x - t.w, -b.y, b.y - t.h)
                for n in b.nodes {
                    worst = max(worst, -n.x, n.x - t.w, -n.y, n.y - t.h)
                }
            }
            // and nothing it leaves behind, either
            for e in t.etched {
                for n in e.nodes { worst = max(worst, -n.x, n.x - t.w, -n.y, n.y - t.h) }
            }
        }
        XCTAssertLessThan(worst, 2, "escaped by \(worst)")
    }

    func testBoltsBurnOutAndThereIsALimitOnHowManyBurnAtOnce() {
        let t = struck()
        XCTAssertFalse(t.bolts.isEmpty)
        XCTAssertTrue(run(t, 4) { t.bolts.isEmpty }, "should fade")

        let many = toy()
        many.mode = .bolt
        for _ in 0..<(Toy.maxBolts * 3) { many.fireBolt(500, 500, 2000, 500) }
        XCTAssertEqual(many.bolts.count, Toy.maxBolts)
    }

    func testTheZigzagIsLaidDownOnceAndNeverMovesAgain() {
        // A bolt redrawn from fresh randomness every frame is television
        // static, so the shape has to be part of the simulation. Two identical
        // strikes must etch identical scenes, fork for fork.
        let a = arrived(struck())
        let b = arrived(struck())
        XCTAssertEqual(a.count, b.count, "the same strike must etch the same scene")
        for e in a.indices {
            XCTAssertEqual(a[e].nodes.count, b[e].nodes.count, "etching \(e)")
            for i in a[e].nodes.indices {
                XCTAssertEqual(a[e].nodes[i].x, b[e].nodes[i].x, accuracy: 1e-4)
                XCTAssertEqual(a[e].nodes[i].y, b[e].nodes[i].y, accuracy: 1e-4)
            }
        }

        // And once a kink is down, later frames must not move it: what is
        // etched at the end has to still contain what was laid mid-flight.
        let t = struck()
        run(t, 0.06) { false }
        let snapshot = t.bolts[0].nodes
        XCTAssertGreaterThan(snapshot.count, 3, "nothing was laid down to check")
        arrived(t)
        // A strike is a fan, so find the etching this particular arm became,
        // by the first kink it laid down rather than by its place in the list.
        let mine = etchingOf(t, snapshot[1])
        for i in snapshot.indices {
            XCTAssertEqual(mine.nodes[i].x, snapshot[i].x, accuracy: 1e-4)
            XCTAssertEqual(mine.nodes[i].y, snapshot[i].y, accuracy: 1e-4)
        }
    }

    func testAStrikeSpreadsOnItsWay() {
        // One flick, several paths: a bolt that only ever drew one line is a
        // wire. Forks come off it, and a fork can fork once itself.
        let t = struck()
        var peak = 0
        run(t, 4) { peak = max(peak, t.bolts.count); return t.bolts.isEmpty }
        XCTAssertGreaterThan(peak, 1, "one flick should have thrown forks")
        XCTAssertGreaterThan(t.etched.count, 1, "and every one of them lands")
        XCTAssertTrue(t.etched.contains { $0.gen > 0 }, "the forks should be marked as forks")
    }

    func testABoltStopsAtTheWallItReachesAndStaysThere() {
        let t = toy()
        t.mode = .bolt
        t.fireBolt(t.w / 2, t.h / 2, 3000, 0)          // straight at the right wall
        XCTAssertTrue(run(t, 2) { !t.etched.isEmpty }, "should arrive")
        let end = root(t).nodes.last!
        XCTAssertGreaterThanOrEqual(end.x, t.w - 1, "it should end on a wall")
        XCTAssertTrue(run(t, 3) { t.bolts.isEmpty }, "the strike should stop, not pinball")
        XCTAssertFalse(t.etched.isEmpty, "but the scene keeps it")
    }

    func testAnEtchingKeepsTheInkItWasThrownIn() {
        let t = toy()
        t.mode = .bolt
        t.inkFamily = 2
        let first = t.inkColor()
        t.fireBolt(t.w / 2, t.h / 2, 3000, 0)
        XCTAssertTrue(run(t, 2) { t.bolts.isEmpty })
        t.inkFamily = 6
        let second = t.inkColor()
        XCTAssertNotEqual(first, second, "a different ink to prove anything with")
        t.fireBolt(t.w / 2, t.h / 2, -3000, 0)
        XCTAssertTrue(run(t, 2) { t.bolts.isEmpty })

        let inks = Set(t.etched.map(\.argb))
        XCTAssertTrue(inks.contains(first), "both inks should be on the scene")
        XCTAssertTrue(inks.contains(second),
                      "and the later strike must not repaint the earlier one")
    }

    func testTheSceneCanBeWipedAndCannotGrowWithoutLimit() {
        let t = toy()
        t.mode = .bolt
        for _ in 0..<60 {
            t.fireBolt(t.w / 2, t.h / 2, 2600, 1300)
            run(t, 1) { t.bolts.isEmpty }
        }
        XCTAssertFalse(t.etched.isEmpty, "something should have been etched")
        XCTAssertLessThanOrEqual(t.etched.count, Toy.maxEtched, "and it is capped")
        t.clearEtched()
        XCTAssertTrue(t.etched.isEmpty, "a wipe should leave a blank scene")
    }

    func testLightningOffersThePaletteAndNothingElse() {
        let t = toy()
        t.mode = .bolt
        let z = t.stripZones()
        XCTAssertEqual(z.count, 1, "one zone, and it is the ink")
        XCTAssertEqual(z[0].kind, "color")
        XCTAssertEqual(z[0].x1, t.w, accuracy: 0.01, "across the whole width")
        let step = t.w / Double(z[0].count)
        t.stripTap(step * 4.5)
        XCTAssertEqual(t.inkFamily, 4)

        t.mode = .paint
        XCTAssertEqual(t.stripZones().count, 3, "paint still gets sizes and shapes too")
    }

    func testTheThrowKeepsAlternatingAfterTheMemoryFills() {
        // The side used to be read off the node count, which stops changing
        // the moment the rolling window is full. From there every kink threw
        // the same way, and the zigzag straightened into a smooth arc — a bug
        // no other test could see, because every node was still off-line.
        let t = Toy()
        t.resize(400, 4000, 0)
        t.screen = .play
        t.tier = .full
        t.mode = .bolt
        t.fireBolt(200, 40, 0, 2500)         // straight down a very tall field
        arrived(t)
        let nodes = root(t).nodes
        XCTAssertEqual(nodes.count, Toy.boltMaxNodes, "the window should be full")
        // Travel is straight down, so a node's x is its throw. The last one is
        // where it met the wall — an exact position rather than a throw — so
        // it is not part of the alternation.
        var sign = 0
        var alternations = 0
        for i in 0..<(nodes.count - 1) {
            let s = nodes[i].x > 200 ? 1 : -1
            if sign != 0 && s != sign { alternations += 1 }
            sign = s
        }
        XCTAssertEqual(alternations, nodes.count - 2, "every kink should throw the other way")
    }

    func testTheZigzagActuallyZigzags() {
        let t = struck()
        arrived(t)
        let nodes = root(t).nodes
        XCTAssertGreaterThan(nodes.count, 6, "too few kinks to be lightning")
        var offLine = 0
        for i in 2..<nodes.count {
            let a = nodes[i - 2], b = nodes[i - 1], c = nodes[i]
            let cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
            if abs(cross) > 1 { offLine += 1 }
        }
        XCTAssertGreaterThan(offLine, (nodes.count - 2) / 2, "a straight line is not lightning")
    }

    func testABoltCannotOutliveItsOwnMemory() {
        let t = toy()
        t.mode = .bolt
        t.fireBolt(t.w / 2, t.h / 2, 4000, 3000)
        run(t, 3) { false }
        for b in t.bolts { XCTAssertLessThanOrEqual(b.nodes.count, Toy.boltMaxNodes) }
        for e in t.etched { XCTAssertLessThanOrEqual(e.nodes.count, Toy.boltMaxNodes) }
    }

    func testLightningIsNamedOnTheFrontDoorAndOpensOnceBought() {
        let t = free()
        XCTAssertTrue(t.menuItems().map(\.key).contains("bolt"),
                      "named whether or not you have paid")
        XCTAssertTrue(t.menuLocked("bolt"), "and it wears a padlock until you do")

        let p = toy()
        p.screen = .title
        XCTAssertTrue(p.tapMenu("bolt"))
        XCTAssertEqual(p.mode, .bolt)
        if case .play = p.screen {} else { XCTFail("should be playing") }

        p.mode = .ball
        XCTAssertTrue(p.modeLabels().contains("bolt"))
        p.tapMode("bolt")
        XCTAssertEqual(p.mode, .bolt)
    }

    func testNothingElseMovesWhileTheLightningDoes() {
        let t = toy()
        t.mode = .bolt
        t.bx = 300; t.by = 400; t.vx = 1500; t.vy = 800
        struck(t)
        run(t, 0.5) { false }
        XCTAssertEqual(t.bx, 300, accuracy: 0.001, "the ball is not in this toy")
        XCTAssertEqual(t.by, 400, accuracy: 0.001)
    }

    /// The seed arithmetic has to wrap the way Kotlin's Int does, not trap the
    /// way Swift's default does — otherwise the first negative seed crashes.
    func testTheSeedWrapsRatherThanTrapping() {
        var s: Int32 = 0x5eed
        for _ in 0..<10_000 { s = Toy.nextRand(s) }
        let u = Toy.randUnit(s)
        XCTAssertTrue(u >= -1 && u <= 1, "out of range: \(u)")
    }
}
