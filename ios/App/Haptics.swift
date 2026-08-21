import UIKit

/// The phone's answer to a collision.
///
/// The Android build learned this the expensive way, twice: a hand-rolled
/// nine-millisecond pulse is shorter than a linear actuator takes to reach
/// full travel, so it moves almost nothing, and the lightest platform
/// "constant" is silenced outright by a system switch. Both were code that
/// ran and could not be felt.
///
/// iOS makes the same mistake harder to make. `UIImpactFeedbackGenerator` is
/// the tuned waveform the system uses for its own knocks, and the intensity
/// argument scales it without inventing a duration. So this file is short on
/// purpose: the temptation to hand-roll the envelope is the bug.
final class Haptics {

    private let light = UIImpactFeedbackGenerator(style: .light)
    private let medium = UIImpactFeedbackGenerator(style: .medium)
    private let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private let rigid = UIImpactFeedbackGenerator(style: .rigid)

    /// Warms the actuator so the first knock is not late. Cheap to call.
    func prepare() {
        light.prepare()
        medium.prepare()
    }

    /// A ball meeting something solid. `strength` runs 0 to 1.
    ///
    /// A wall is a flat knock — `rigid` is exactly that shape — and a bumper
    /// throws the ball back, so it gets the rounder one.
    func knock(_ strength: Double, sharp: Bool) {
        let s = min(1, max(0, strength))
        if s <= 0.02 { return }
        let generator: UIImpactFeedbackGenerator
        switch (sharp, s) {
        case (true, _): generator = rigid
        case (false, let v) where v > 0.6: generator = heavy
        default: generator = medium
        }
        generator.impactOccurred(intensity: CGFloat(0.35 + 0.65 * s))
        generator.prepare()
    }

    /// A detent, a chip, a control answering.
    func tick(_ strength: Double) {
        let s = min(1, max(0, strength))
        if s <= 0.02 { return }
        light.impactOccurred(intensity: CGFloat(0.25 + 0.75 * s))
        light.prepare()
    }
}
