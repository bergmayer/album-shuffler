import Foundation
import MusicKit

enum MusicKitServiceError: LocalizedError {
    case authorizationDenied
    case emptySource
    case missingAlbumMetadata
    case catalogLookupFailed

    var errorDescription: String? {
        switch self {
        case .authorizationDenied:    return "Apple Music access was denied."
        case .emptySource:            return "The selected source has no tracks."
        case .missingAlbumMetadata:   return "Could not determine the album for the chosen track."
        case .catalogLookupFailed:    return "Could not find this album in the Apple Music catalog."
        }
    }
}

/// Stateless wrapper around the MusicKit fetches we need.
struct MusicKitService {

    // MARK: - Authorization

    func requestAuthorization() async -> MusicAuthorization.Status {
        await MusicAuthorization.request()
    }

    // MARK: - Playlists

    func fetchUserPlaylists() async throws -> [Playlist] {
        var request = MusicLibraryRequest<Playlist>()
        request.sort(by: \.lastPlayedDate, ascending: false)
        let response = try await request.response()
        return Array(response.items)
    }

    func loadTracks(in playlist: Playlist) async throws -> [Track] {
        let detailed = try await playlist.with([.tracks])
        return Array(detailed.tracks ?? [])
    }

    // MARK: - Subset mode

    /// Picks a random album represented in the playlist, returns the subset of
    /// that album's tracks that appear in the playlist, sorted by (disc, track)
    /// number. The chosen album key is returned alongside so callers can record
    /// it in their recently-played set. Albums whose key is in `excluding` are
    /// skipped as long as alternatives exist.
    ///
    /// We swap library tracks for their catalog counterparts before returning.
    /// `SystemMusicPlayer.Queue(for: librarySequence)` is unreliable for library
    /// `Track`s from a playlist — the system Music app collapses the queue and
    /// plays only the first entry. A sequence of catalog tracks queues cleanly
    /// and plays through in order.
    func playlistSubset(for playlist: Playlist,
                        excluding: Set<String> = []) async throws -> (tracks: [Track], albumID: String) {
        let tracks = try await loadTracks(in: playlist)
        guard !tracks.isEmpty else { throw MusicKitServiceError.emptySource }

        let grouped = Dictionary(grouping: tracks, by: { albumKey(for: $0) })
            .filter { !$0.key.isEmpty }
        guard !grouped.isEmpty else { throw MusicKitServiceError.missingAlbumMetadata }

        let allKeys = Array(grouped.keys)
        let available = allKeys.filter { !excluding.contains($0) }
        let pool = available.isEmpty ? allKeys : available

        guard let key = pool.randomElement(),
              let libraryMatches = grouped[key],
              let seed = libraryMatches.first else {
            throw MusicKitServiceError.missingAlbumMetadata
        }

        let resolved = await catalogTracks(matchingPositionsOf: libraryMatches, seed: seed)
            ?? libraryMatches.sorted(by: trackOrdering)
        return (resolved, key)
    }

    /// Resolves `libraryTracks` (from one library album) to the matching tracks
    /// on the catalog album, by (disc, trackNumber) position. Returns `nil` if
    /// catalog lookup fails or no positions line up — callers should fall back
    /// to the library tracks in that case.
    private func catalogTracks(matchingPositionsOf libraryTracks: [Track],
                               seed: Track) async -> [Track]? {
        guard let album = try? await resolveCatalogAlbum(for: seed),
              let catalogTracks = album.tracks else { return nil }
        let wanted = Set(libraryTracks.map(positionKey))
        let filtered = catalogTracks.filter { wanted.contains(positionKey(for: $0)) }
        guard !filtered.isEmpty else { return nil }
        return filtered.sorted(by: trackOrdering)
    }

    // MARK: - Complete mode

    /// Picks a random album from the source and returns the catalog version
    /// (so any missing library tracks are filled in automatically). Albums whose
    /// catalog ID is in `excluding` are skipped as long as alternatives exist.
    func completeAlbum(from source: SourceSelection,
                       excluding: Set<String> = []) async throws -> Album {
        switch source {
        case .entireLibrary:
            // Library is too large to enumerate; retry a handful of times to
            // avoid recently-played albums. Bail out to a repeat after that
            // so we never spin forever on tiny libraries.
            var lastAlbum: Album?
            for _ in 0..<8 {
                let track = try await randomLibraryTrack()
                let album = try await resolveCatalogAlbum(for: track)
                if !excluding.contains(album.id.rawValue) { return album }
                lastAlbum = album
            }
            if let lastAlbum { return lastAlbum }
            throw MusicKitServiceError.catalogLookupFailed

        case .playlist(let playlist):
            let tracks = try await loadTracks(in: playlist)
            guard !tracks.isEmpty else { throw MusicKitServiceError.emptySource }

            // Enumerate distinct albums in the playlist by key, then pick from
            // the non-excluded ones (falling back to the full set when needed).
            var seen: [String: Track] = [:]
            for t in tracks {
                let key = albumKey(for: t)
                guard !key.isEmpty, seen[key] == nil else { continue }
                seen[key] = t
            }
            guard !seen.isEmpty else { throw MusicKitServiceError.missingAlbumMetadata }

            // We don't know each album's catalog ID until after lookup, so we
            // resolve and check ID. Iterate distinct playlist albums in a
            // random order, returning the first whose catalog ID isn't excluded.
            var lastAlbum: Album?
            for key in seen.keys.shuffled() {
                guard let seed = seen[key] else { continue }
                let album = try await resolveCatalogAlbum(for: seed)
                if !excluding.contains(album.id.rawValue) { return album }
                lastAlbum = album
            }
            if let lastAlbum { return lastAlbum }
            throw MusicKitServiceError.catalogLookupFailed
        }
    }

    // MARK: - Catalog resolution

    /// Always resolves to the Apple Music *catalog* album for the given seed
    /// track. We deliberately do NOT take the shortcut of `song.with([.albums])`
    /// here — for a library song that returns the library's album record,
    /// which may be missing tracks (or include extras the user added). We want
    /// the complete, as-released album every time.
    private func resolveCatalogAlbum(for track: Track) async throws -> Album {
        let title = track.albumTitle ?? ""
        let artist = track.artistName
        guard !title.isEmpty else { throw MusicKitServiceError.missingAlbumMetadata }

        let term = "\(artist) \(title)"
        var search = MusicCatalogSearchRequest(term: term, types: [Album.self])
        search.limit = 10
        let response = try await search.response()

        let candidates = response.albums
        let best = candidates.first { matches(title: title, artist: artist, $0) }
            ?? candidates.first
        guard let album = best else { throw MusicKitServiceError.catalogLookupFailed }
        return (try? await album.with([.tracks])) ?? album
    }

    // MARK: - Helpers

    private func randomLibraryTrack() async throws -> Track {
        var request = MusicLibraryRequest<Song>()
        request.limit = 200
        let response = try await request.response()
        guard let song = response.items.randomElement() else {
            throw MusicKitServiceError.emptySource
        }
        return Track.song(song)
    }

    private func albumKey(for track: Track) -> String {
        let title = (track.albumTitle ?? "").lowercased()
        let artist = track.artistName.lowercased()
        return "\(artist)|\(title)"
    }

    /// Stable identity for "which slot on the album is this." Used to line up
    /// library tracks (from a playlist) with their catalog-album counterparts.
    /// Falls back to a title match when a track has no number.
    private func positionKey(for track: Track) -> String {
        let disc = track.discNumber ?? 1
        if let num = track.trackNumber {
            return "n:\(disc):\(num)"
        }
        return "t:\(track.title.lowercased())"
    }

    private func trackOrdering(_ lhs: Track, _ rhs: Track) -> Bool {
        let lDisc = lhs.discNumber ?? 1
        let rDisc = rhs.discNumber ?? 1
        if lDisc != rDisc { return lDisc < rDisc }
        let lNum = lhs.trackNumber ?? Int.max
        let rNum = rhs.trackNumber ?? Int.max
        return lNum < rNum
    }

    private func matches(title: String, artist: String, _ album: Album) -> Bool {
        album.title.caseInsensitiveCompare(title) == .orderedSame
            && album.artistName.caseInsensitiveCompare(artist) == .orderedSame
    }
}
