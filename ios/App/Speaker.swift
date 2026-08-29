import AVFoundation
import NonsenseCore

/// The phone's answer to a hit landing.
///
/// One engine, open for as long as the toy is on screen, with a source node
/// that mixes whatever notes are sounding into it. A player per note would be
/// simpler and is what a first attempt usually does, but starting one costs
/// milliseconds you can hear, and two hits landing together would fight over
/// the device rather than sum. Mixing into a single node gives both: a hit is
/// audible on the frame it happens, and a chord is a chord.
///
/// None of the arithmetic is here. What a note sounds like is `Synth`, in
/// NonsenseCore, so the phone and the page and the desktop are the same
/// instrument — down to the sample, which is a thing the tests check.
final class Speaker {

    /// Enough for the voices, half the samples of CD rate, a quarter the work.
    private let rate = 22050.0

    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
    private var started = false

    /// A note part-way through being played: its samples, and how far in.
    private final class Sounding {
        let buf: [Float]
        var at = 0
        init(_ buf: [Float]) { self.buf = buf }
    }

    /// Touched by the audio thread and the main one, so it is guarded. The
    /// lock is held for the length of a memcpy, never across a render.
    private let lock = NSLock()
    private var playing: [Sounding] = []

    func play(_ notes: [Note]) {
        guard !notes.isEmpty else { return }
        start()
        var fresh: [Sounding] = []
        for note in notes {
            let n = Synth.samples(note, Int(rate))
            var buf = [Float](repeating: 0, count: n)
            let wrote = Synth.render(note, Int(rate), &buf)
            if wrote > 0 { fresh.append(Sounding(Array(buf[0..<wrote]))) }
        }
        guard !fresh.isEmpty else { return }
        lock.lock()
        playing.append(contentsOf: fresh)
        lock.unlock()
    }

    private func start() {
        guard !started else { return }
        started = true

        // Ambient: the toy is not worth interrupting somebody's music for, and
        // it should go quiet with the ring switch like any other game noise.
        try? AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)

        let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
        let node = AVAudioSourceNode { [weak self] _, _, frameCount, audioBufferList in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let out = abl.first?.mData?.assumingMemoryBound(to: Float.self) else {
                return noErr
            }
            let n = Int(frameCount)
            for i in 0..<n { out[i] = 0 }
            guard let self else { return noErr }

            self.lock.lock()
            var keep: [Sounding] = []
            for s in self.playing {
                var i = 0
                while i < n && s.at < s.buf.count {
                    out[i] += s.buf[s.at]
                    i += 1
                    s.at += 1
                }
                if s.at < s.buf.count { keep.append(s) }
            }
            self.playing = keep
            self.lock.unlock()

            // Soft, not hard. Two notes landing together used to square off
            // against each other at the clamp; tanh lets them sum.
            for i in 0..<n { out[i] = Float(tanh(Double(out[i]))) }
            return noErr
        }
        source = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        try? engine.start()
    }

    /// The engine holds a thread and a session; a view that has left the
    /// screen should hold neither.
    func stop() {
        guard started else { return }
        started = false
        engine.stop()
        if let source { engine.detach(source) }
        source = nil
        lock.lock()
        playing.removeAll()
        lock.unlock()
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
