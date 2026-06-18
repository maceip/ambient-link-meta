import Foundation

// Copy of the canonical contract in ambient-link-core/contracts/GlassLink.swift.
// Kept in-repo until the shared core-apple package lands.
//
// Meta-iOS implements this against the DAT capture path; display lives alongside
// HudPresenter / ChipSet / WearablesViewModel. Shape extracted from the recovered
// Cosmo CosmoGlassManager (ambient-link-google/glasses_link.md); plan in
// ambient-link-core/ROUTING.md.

public struct GlassFrame: Sendable {
    public let width: Int
    public let height: Int
    public let pixels: Data
    public let timestamp: Date
    public init(width: Int, height: Int, pixels: Data, timestamp: Date) {
        self.width = width; self.height = height; self.pixels = pixels; self.timestamp = timestamp
    }
}

public protocol GlassLink: AnyObject {
    var connected: AsyncStream<Bool> { get }
    var bound: AsyncStream<Bool> { get }

    func bind() async
    func unbind()

    func setupImageCapture(onFrame: @escaping @Sendable (GlassFrame) -> Void)
    func startImageCapture()
    func stopImageCapture()

    func startAudioCapture(onBytes: @escaping @Sendable (Data, Int) -> Void)
    func stopAudioCapture()

    func clear()
}

public enum GlassLinkDefaults {
    /// Cosmo: 10s frame interval / 0.1 fps target.
    public static let frameIntervalMillis: Int = 10_000
}
