import SwiftUI

@main
struct AlbumShufflerApp: App {
    @StateObject private var viewModel = MusicPlayerViewModel()

    init() {
        #if os(iOS) || targetEnvironment(macCatalyst)
        WatchBridge.shared.viewModel = nil // attached below once @StateObject is initialised
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(viewModel)
                .task {
                    await viewModel.bootstrap()
                    #if os(iOS) || targetEnvironment(macCatalyst)
                    WatchBridge.shared.viewModel = viewModel
                    #endif
                }
        }
        #if os(macOS)
        .defaultSize(width: 560, height: 720)
        .windowResizability(.contentMinSize)
        #endif
    }
}
