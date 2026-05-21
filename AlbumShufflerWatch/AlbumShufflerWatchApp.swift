import SwiftUI

@main
struct AlbumShufflerWatchApp: App {
    init() {
        // Touch the singleton so WCSession activates on launch (and is ready by
        // the time a complication-triggered onOpenURL fires).
        _ = WatchBridge.shared
    }

    var body: some Scene {
        WindowGroup {
            WatchContentView()
        }
    }
}
