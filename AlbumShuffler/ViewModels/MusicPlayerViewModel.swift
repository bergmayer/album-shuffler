import Foundation
import SwiftUI
import MusicKit
import Combine

#if os(macOS) && !targetEnvironment(macCatalyst)
typealias PlatformMusicPlayer = ApplicationMusicPlayer
#else
typealias PlatformMusicPlayer = SystemMusicPlayer
#endif

@MainActor
final class MusicPlayerViewModel: ObservableObject {

    // MARK: - Auth & loading

    @Published private(set) var authorizationStatus: MusicAuthorization.Status = MusicAuthorization.currentStatus
    @Published private(set) var isLoadingPlaylists = false
    @Published private(set) var isShuffling = false
    @Published var errorMessage: String?

    // MARK: - Source & mode

    @Published private(set) var playlists: [Playlist] = []
    @Published var source: SourceSelection = .entireLibrary {
        didSet { if !isRestoring { persistSource() } }
    }
    @Published var mode: PlaybackMode = .complete {
        didSet { if !isRestoring { persistMode() } }
    }

    private var isRestoring = false
    private enum DefaultsKey {
        static let mode = "AlbumShuffler.mode"
        static let sourceKind = "AlbumShuffler.source.kind"
        static let sourcePlaylistID = "AlbumShuffler.source.playlistID"
    }

    // MARK: - Now Playing

    @Published private(set) var nowPlayingTitle: String?
    @Published private(set) var nowPlayingArtist: String?
    @Published private(set) var nowPlayingAlbumTitle: String?
    @Published private(set) var nowPlayingArtwork: Artwork?
    @Published private(set) var isPlaying = false

    private let service = MusicKitService()
    private let player = PlatformMusicPlayer.shared
    private var cancellables: Set<AnyCancellable> = []

    init() {
        observePlayer()
    }

    // MARK: - Bootstrap

    func bootstrap() async {
        restoreMode()
        await refreshAuthorization()
        if authorizationStatus == .authorized {
            await loadPlaylists()
            restoreSource()
        }
    }

    func refreshAuthorization() async {
        if MusicAuthorization.currentStatus == .notDetermined {
            authorizationStatus = await service.requestAuthorization()
        } else {
            authorizationStatus = MusicAuthorization.currentStatus
        }
    }

    func loadPlaylists() async {
        isLoadingPlaylists = true
        defer { isLoadingPlaylists = false }
        do {
            playlists = try await service.fetchUserPlaylists()
        } catch {
            errorMessage = "Couldn't load playlists: \(error.localizedDescription)"
        }
    }

    // MARK: - Settings persistence

    private func persistMode() {
        UserDefaults.standard.set(mode.rawValue, forKey: DefaultsKey.mode)
    }

    private func persistSource() {
        let defaults = UserDefaults.standard
        switch source {
        case .entireLibrary:
            defaults.set("library", forKey: DefaultsKey.sourceKind)
            defaults.removeObject(forKey: DefaultsKey.sourcePlaylistID)
        case .playlist(let playlist):
            defaults.set("playlist", forKey: DefaultsKey.sourceKind)
            defaults.set(playlist.id.rawValue, forKey: DefaultsKey.sourcePlaylistID)
        }
    }

    private func restoreMode() {
        guard let raw = UserDefaults.standard.string(forKey: DefaultsKey.mode),
              let restored = PlaybackMode(rawValue: raw) else { return }
        isRestoring = true
        mode = restored
        isRestoring = false
    }

    /// Resolves the persisted source against the just-loaded playlists. Must run
    /// after `loadPlaylists()` so playlist IDs can be matched back to objects.
    private func restoreSource() {
        let defaults = UserDefaults.standard
        guard defaults.string(forKey: DefaultsKey.sourceKind) == "playlist",
              let id = defaults.string(forKey: DefaultsKey.sourcePlaylistID),
              let match = playlists.first(where: { $0.id.rawValue == id }) else {
            return
        }
        isRestoring = true
        source = .playlist(match)
        isRestoring = false
    }

    // MARK: - Validation

    var canShuffle: Bool {
        if authorizationStatus != .authorized { return false }
        if isShuffling { return false }
        if mode.requiresPlaylist && source.playlist == nil { return false }
        return true
    }

    // MARK: - Play random album

    func playRandomAlbum() async {
        guard canShuffle else { return }
        isShuffling = true
        defer { isShuffling = false }

        do {
            switch mode {
            case .subset:
                guard let playlist = source.playlist else { return }
                let tracks = try await service.playlistSubset(for: playlist)
                try await play(tracks: tracks)
            case .complete:
                let album = try await service.completeAlbum(from: source)
                try await play(album: album)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Transport controls

    func togglePlayPause() {
        if isPlaying {
            player.pause()
        } else {
            Task { try? await player.play() }
        }
    }

    func skipToNext() {
        Task { try? await player.skipToNextEntry() }
    }

    func skipToPrevious() {
        Task { try? await player.skipToPreviousEntry() }
    }

    // MARK: - Internal playback

    private func play(tracks: [Track]) async throws {
        guard !tracks.isEmpty else { throw MusicKitServiceError.emptySource }
        player.queue = PlatformMusicPlayer.Queue(for: tracks)
        try await player.prepareToPlay()
        try await player.play()
    }

    private func play(album: Album) async throws {
        player.queue = PlatformMusicPlayer.Queue(for: [album])
        try await player.prepareToPlay()
        try await player.play()
    }

    // MARK: - Player observation

    private func observePlayer() {
        player.queue.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                Task { @MainActor in self?.refreshNowPlaying() }
            }
            .store(in: &cancellables)

        player.state.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                Task { @MainActor in self?.refreshNowPlaying() }
            }
            .store(in: &cancellables)

        refreshNowPlaying()
    }

    private func refreshNowPlaying() {
        isPlaying = (player.state.playbackStatus == .playing)

        guard let entry = player.queue.currentEntry else {
            nowPlayingTitle = nil
            nowPlayingArtist = nil
            nowPlayingAlbumTitle = nil
            nowPlayingArtwork = nil
            return
        }

        nowPlayingTitle = entry.title
        nowPlayingArtwork = entry.artwork

        switch entry.item {
        case .song(let song):
            nowPlayingArtist = song.artistName
            nowPlayingAlbumTitle = song.albumTitle
        case .musicVideo(let video):
            nowPlayingArtist = video.artistName
            nowPlayingAlbumTitle = video.albumTitle
        default:
            nowPlayingArtist = entry.subtitle
            nowPlayingAlbumTitle = nil
        }
    }
}
