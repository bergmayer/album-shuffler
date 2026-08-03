import SwiftUI
import MusicKit

struct ShuffleControlsView: View {
    @EnvironmentObject private var viewModel: MusicPlayerViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            sourceSection
            modeSection
            playSection
        }
    }

    // MARK: - Source

    private var sourceSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("SOURCE")

            Menu {
                Button {
                    viewModel.source = .entireLibrary
                } label: {
                    Label("Entire Library", systemImage: "music.note.house")
                }

                if !viewModel.playlists.isEmpty {
                    Divider()
                    ForEach(viewModel.playlists, id: \.id) { playlist in
                        Button(playlist.name) {
                            viewModel.source = .playlist(playlist)
                        }
                    }
                }
            } label: {
                #if os(tvOS)
                // Let the system focus pill carry all the chrome — drawing our
                // own background inside it produces a nested shape.
                Label(viewModel.source.displayName,
                      systemImage: viewModel.source.isPlaylist ? "music.note.list" : "music.note.house")
                #else
                HStack {
                    Image(systemName: viewModel.source.isPlaylist ? "music.note.list" : "music.note.house")
                    Text(viewModel.source.displayName)
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(.quaternary)
                )
                #endif
            }

            if viewModel.isLoadingPlaylists {
                Label("Loading playlists…", systemImage: "arrow.triangle.2.circlepath")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Mode (radio buttons)

    private var modeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("MODE")

            VStack(alignment: .leading, spacing: 8) {
                ForEach(PlaybackMode.allCases) { mode in
                    radioRow(for: mode)
                }
            }

            if viewModel.mode.requiresPlaylist && !viewModel.source.isPlaylist {
                Label("Subset mode requires a playlist source.", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func radioRow(for mode: PlaybackMode) -> some View {
        let selected = viewModel.mode == mode
        return Button {
            viewModel.mode = mode
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .padding(.top, 2)

                VStack(alignment: .leading, spacing: 2) {
                    Text(mode.title)
                        .font(.headline)
                    Text(mode.detail)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Play

    private var playSection: some View {
        VStack(spacing: 10) {
            playButton
            chooseAnotherButton
        }
        .tint(.blue)
    }

    private var playButton: some View {
        Button {
            Task { await viewModel.playRandomAlbum() }
        } label: {
            shuffleButtonLabel(
                viewModel.isShuffling ? "Working…" : "Play random album",
                systemImage: "play.fill"
            )
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!viewModel.canShuffle)
    }

    private var chooseAnotherButton: some View {
        Button {
            Task { await viewModel.playRandomAlbum() }
        } label: {
            shuffleButtonLabel(
                "Choose another",
                systemImage: "shuffle",
                showsProgress: false
            )
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(viewModel.nowPlayingTitle == nil || !viewModel.canShuffle)
        .accessibilityLabel("Choose another album")
    }

    private func shuffleButtonLabel(
        _ title: String,
        systemImage: String,
        showsProgress: Bool = true
    ) -> some View {
        HStack(spacing: 8) {
            if viewModel.isShuffling && showsProgress {
                ProgressView()
                    .controlSize(.small)
            } else {
                Image(systemName: systemImage)
            }
            Text(title)
        }
        .font(.headline)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Helpers

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.bold))
            .tracking(1.2)
            .foregroundStyle(.secondary)
    }
}
