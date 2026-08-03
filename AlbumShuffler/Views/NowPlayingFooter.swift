import SwiftUI
import MusicKit
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// MARK: - Shared helpers

private func openMusicApp() {
    // music://nowplaying lands on the Now Playing screen on supporting
    // versions; music:// is the documented fallback that reliably opens
    // the Music app.
    guard let first = URL(string: "music://nowplaying"),
          let fallback = URL(string: "music://") else { return }
    #if canImport(UIKit)
    UIApplication.shared.open(first, options: [:]) { success in
        if !success {
            UIApplication.shared.open(fallback)
        }
    }
    #elseif canImport(AppKit)
    if !NSWorkspace.shared.open(first) {
        NSWorkspace.shared.open(fallback)
    }
    #endif
}

private func nowPlayingSubtitle(artist: String?, album: String?) -> String {
    let a = artist ?? ""
    let b = album ?? ""
    switch (a.isEmpty, b.isEmpty) {
    case (false, false): return "\(a) — \(b)"
    case (false, true):  return a
    case (true, false):  return b
    case (true, true):   return "Pick a mode and play"
    }
}

// MARK: - iOS / iPadOS / Catalyst / macOS bottom strip

#if !os(tvOS)
struct NowPlayingFooter: View {
    @EnvironmentObject private var viewModel: MusicPlayerViewModel

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                artworkView
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(viewModel.nowPlayingTitle ?? "Nothing playing")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(nowPlayingSubtitle(artist: viewModel.nowPlayingArtist,
                                            album: viewModel.nowPlayingAlbumTitle))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                openInMusicButton
            }

            transportControls
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.thinMaterial)
        .overlay(
            Rectangle()
                .frame(height: 0.5)
                .foregroundStyle(.separator),
            alignment: .top
        )
    }

    @ViewBuilder
    private var artworkView: some View {
        if let artwork = viewModel.nowPlayingArtwork {
            ArtworkImage(artwork, width: 56, height: 56)
        } else {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(.quaternary)
                .overlay(
                    Image(systemName: "music.note")
                        .foregroundStyle(.secondary)
                )
        }
    }

    private var openInMusicButton: some View {
        Button {
            openMusicApp()
        } label: {
            Image(systemName: "arrow.up.forward.app.fill")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open in Apple Music")
    }

    private var transportControls: some View {
        HStack(spacing: 36) {
            Button { viewModel.skipToPrevious() } label: {
                Image(systemName: "backward.fill").font(.title3)
            }
            Button { viewModel.togglePlayPause() } label: {
                Image(systemName: viewModel.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 44))
            }
            Button { viewModel.skipToNext() } label: {
                Image(systemName: "forward.fill").font(.title3)
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(viewModel.nowPlayingTitle == nil ? Color.secondary : Color.primary)
        .disabled(viewModel.nowPlayingTitle == nil)
    }
}
#endif

// MARK: - tvOS widescreen Now Playing panel

#if os(tvOS)
struct NowPlayingTVPanel: View {
    @EnvironmentObject private var viewModel: MusicPlayerViewModel

    var body: some View {
        VStack(spacing: 36) {
            artworkView
                .frame(width: 380, height: 380)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: .black.opacity(0.5), radius: 16, x: 0, y: 8)

            VStack(spacing: 6) {
                Text(viewModel.nowPlayingTitle ?? "Nothing playing")
                    .font(.title2.weight(.semibold))
                    .lineLimit(1)
                Text(nowPlayingSubtitle(artist: viewModel.nowPlayingArtist,
                                        album: viewModel.nowPlayingAlbumTitle))
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 48) {
                Button { viewModel.skipToPrevious() } label: {
                    Image(systemName: "backward.fill")
                        .font(.system(size: 36))
                        .frame(width: 88, height: 88)
                }
                Button { viewModel.togglePlayPause() } label: {
                    Image(systemName: viewModel.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 44))
                        .frame(width: 110, height: 110)
                }
                Button { viewModel.skipToNext() } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 36))
                        .frame(width: 88, height: 88)
                }
            }
            .disabled(viewModel.nowPlayingTitle == nil)

            Button {
                openMusicApp()
            } label: {
                Label("Open in Apple Music", systemImage: "arrow.up.forward.app.fill")
                    .font(.headline)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 10)
            }
            .accessibilityLabel("Open in Apple Music")
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var artworkView: some View {
        if let artwork = viewModel.nowPlayingArtwork {
            ArtworkImage(artwork, width: 380, height: 380)
        } else {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(.quaternary)
                .overlay(
                    Image(systemName: "music.note")
                        .font(.system(size: 100))
                        .foregroundStyle(.secondary)
                )
        }
    }
}
#endif
