import SwiftUI

@main
struct NonsenseApp: App {
    var body: some Scene {
        WindowGroup {
            ToyView()
                .preferredColorScheme(.dark)
                .persistentSystemOverlays(.hidden)   // dim the home indicator
        }
    }
}
