// swift-tools-version: 5.9
import PackageDescription

// The simulation only. Building it as a plain package rather than as part of
// the app target is the point: `swift test` runs the whole suite from a
// terminal in a couple of seconds, with no simulator, no scheme and no Xcode
// project — which is the fastest way to find out whether the port from Kotlin
// is faithful. macOS is listed so that test run needs no device at all.
let package = Package(
    name: "NonsenseCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "NonsenseCore", targets: ["NonsenseCore"]),
    ],
    targets: [
        .target(name: "NonsenseCore"),
        .testTarget(name: "NonsenseCoreTests", dependencies: ["NonsenseCore"]),
    ]
)
