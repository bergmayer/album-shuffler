import SwiftUI
import MusicKit

struct AuthorizationView: View {
    enum State { case pending, denied }

    let state: State
    @EnvironmentObject private var viewModel: MusicPlayerViewModel

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "music.note.list")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(.secondary)

            Text(title)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)

            Text(message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            actionButton
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    private var title: String {
        switch state {
        case .pending: return "Apple Music Access"
        case .denied:  return "Permission Needed"
        }
    }

    private var message: String {
        switch state {
        case .pending:
            return "Album Shuffler needs access to your Apple Music library so it can pick albums and start playback."
        case .denied:
            return "Open Settings to allow access to Apple Music, then return here to start shuffling albums."
        }
    }

    @ViewBuilder
    private var actionButton: some View {
        switch state {
        case .pending:
            Button("Grant Access") {
                Task { await viewModel.refreshAuthorization() }
            }
            .buttonStyle(.borderedProminent)
        case .denied:
            #if os(iOS) || targetEnvironment(macCatalyst)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
            #elseif os(tvOS)
            Text("Open Settings → Apps → Album Shuffler to grant access.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            #else
            EmptyView()
            #endif
        }
    }
}
