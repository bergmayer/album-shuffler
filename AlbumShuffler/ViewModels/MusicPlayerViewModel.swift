import Foundation
import SwiftUI
import MusicKit
import Combine

// macOS has no SystemMusicPlayer. Everywhere else (including tvOS) we use
// SystemMusicPlayer so playback state is shared with the system Music app —
// lock-screen / Control Center on iOS, and a populated Now Playing screen
// on tvOS. The trade-off on tvOS is that the system Now Playing view can
// take over the screen during artwork mode.
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
        didSet {
            guard !isRestoring else { return }
            persistSource()
            if oldValue.id != source.id { recentlyPlayedAlbumIDs.removeAll() }
        }
    }
    @Published var mode: PlaybackMode = .complete {
        didSet {
            guard !isRestoring else { return }
            persistMode()
            if oldValue != mode { recentlyPlayedAlbumIDs.removeAll() }
        }
    }
    private var isRestoring = false
    private enum DefaultsKey {
        static let mode = "AlbumShuffler.mode"
        static let sourceKind = "AlbumShuffler.source.kind"
        static let sourcePlaylistID = "AlbumShuffler.source.playlistID"
    }

    // Albums played in the current source/mode context. Used to avoid back-to-back
    // repeats while alternatives exist — slight non-randomness that users expect.
    private var recentlyPlayedAlbumIDs: Set<String> = []

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

    private var bootstrapTask: Task<Void, Never>?

    /// Idempotent: multiple concurrent callers share a single bootstrap run.
    /// Needed because the widget deep-link handler and the scene's `.task`
    /// both kick this off on a fresh launch, and `playRandomAlbum()` must
    /// wait for the persisted source/mode to be restored — otherwise the
    /// first widget-triggered play picks from the default `.entireLibrary`
    /// instead of the user's currently-selected playlist.
    func bootstrap() async {
        if let bootstrapTask {
            await bootstrapTask.value
            return
        }
        let task = Task { @MainActor in
            restoreMode()
            await refreshAuthorization()
            if authorizationStatus == .authorized {
                await loadPlaylists()
                restoreSource()
            }
        }
        bootstrapTask = task
        await task.value
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
                let pick = try await service.playlistSubset(for: playlist,
                                                            excluding: recentlyPlayedAlbumIDs)
                recordPlayed(pick.albumID)
                try await play(tracks: pick.tracks)
            case .complete:
                let album = try await service.completeAlbum(from: source,
                                                            excluding: recentlyPlayedAlbumIDs)
                recordPlayed(album.id.rawValue)
                try await play(album: album)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Record that we just played this album. If the recently-played set has
    /// grown to cover (almost) every alternative we might still see, reset it
    /// so the next pick has room to be genuinely random.
    private func recordPlayed(_ albumID: String) {
        recentlyPlayedAlbumIDs.insert(albumID)
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

    private var artworkFetchKey: String?

    private func refreshNowPlaying() {
        isPlaying = (player.state.playbackStatus == .playing)

        guard let entry = player.queue.currentEntry else {
            nowPlayingTitle = nil
            nowPlayingArtist = nil
            nowPlayingAlbumTitle = nil
            nowPlayingArtwork = nil
            artworkFetchKey = nil
            return
        }

        nowPlayingTitle = entry.title

        // Only overwrite a previously-fetched album artwork when the player
        // hands us a new one — otherwise the lazy fallback below would flicker
        // back to nil on every queue refresh.
        if let entryArt = entry.artwork {
            nowPlayingArtwork = entryArt
        }

        switch entry.item {
        case .song(let song):
            nowPlayingArtist = song.artistName
            nowPlayingAlbumTitle = song.albumTitle
            fetchAlbumArtworkIfNeeded(for: song, entryID: entry.id)
        case .musicVideo(let video):
            nowPlayingArtist = video.artistName
            nowPlayingAlbumTitle = video.albumTitle
        default:
            nowPlayingArtist = entry.subtitle
            nowPlayingAlbumTitle = nil
        }
    }

    /// `entry.artwork` is often nil for the first refresh after the system
    /// Music app session is adopted (cold launch, lock screen, etc.) — we have
    /// a title and artist but no image. Look the song up and use the album's
    /// artwork so the now-playing panel doesn't show the placeholder forever.
    private func fetchAlbumArtworkIfNeeded(for song: Song, entryID: MusicPlayer.Queue.Entry.ID) {
        guard nowPlayingArtwork == nil else { return }
        let key = "\(entryID)"
        guard artworkFetchKey != key else { return }
        artworkFetchKey = key

        Task { @MainActor [weak self] in
            guard let detailed = try? await song.with([.albums]),
                  let album = detailed.albums?.first else { return }
            guard let self else { return }
            // Make sure the queue hasn't moved on while we were awaiting.
            guard self.artworkFetchKey == key else { return }
            self.nowPlayingArtwork = album.artwork ?? detailed.artwork
        }
    }
}
