import SwiftUI
import MusicKit

struct ContentView: View {
    @EnvironmentObject private var viewModel: MusicPlayerViewModel

    var body: some View {
        Group {
            switch viewModel.authorizationStatus {
            case .authorized:
                mainLayout
            case .notDetermined:
                AuthorizationView(state: .pending)
            case .denied, .restricted:
                AuthorizationView(state: .denied)
            @unknown default:
                AuthorizationView(state: .denied)
            }
        }
        .alert(
            "Something went wrong",
            isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            ),
            actions: { Button("OK", role: .cancel) {} },
            message: { Text(viewModel.errorMessage ?? "") }
        )
        .onOpenURL { url in handleDeepLink(url) }
    }

    @ViewBuilder
    private var mainLayout: some View {
        #if os(tvOS)
        HStack(alignment: .top, spacing: 80) {
            ShuffleControlsView()
                .frame(maxWidth: .infinity, alignment: .leading)
            NowPlayingTVPanel()
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 80)
        .padding(.vertical, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(backgroundColor.ignoresSafeArea())
        #else
        VStack(spacing: 0) {
            ShuffleControlsView()
                .padding(20)
                .frame(maxWidth: 640)

            Spacer(minLength: 0)

            NowPlayingFooter()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(backgroundColor.ignoresSafeArea())
        #endif
    }

    private var backgroundColor: Color {
        #if os(macOS)
        Color(nsColor: .windowBackgroundColor)
        #elseif os(tvOS)
        Color.black
        #else
        Color(uiColor: .systemBackground)
        #endif
    }

    // MARK: - Widget deep link handling

    private func handleDeepLink(_ url: URL) {
        guard url.scheme == "albumshuffler",
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              comps.host == "shuffle" else { return }

        // Widgets do not pass parameters — they always replay the user's
        // current in-app source and mode.
        Task { await viewModel.playRandomAlbum() }
    }
}

#if DEBUG
struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
            .environmentObject(MusicPlayerViewModel())
    }
}
#endif
