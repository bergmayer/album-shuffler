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

    /// Picks a random track in the playlist, returns the subset of playlist tracks
    /// from the same album, sorted by (disc, track) number.
    /// Note: registering with Recently Played as the *album* requires queuing the
    /// catalog album object. With a strict subset we can't do that, so the queue
    /// is built from loose tracks; each catalog song still carries album metadata
    /// so the album generally still surfaces in Recently Played.
    func playlistSubset(for playlist: Playlist) async throws -> [Track] {
        let tracks = try await loadTracks(in: playlist)
        guard let seed = tracks.randomElement() else { throw MusicKitServiceError.emptySource }
        let key = albumKey(for: seed)
        guard !key.isEmpty else { throw MusicKitServiceError.missingAlbumMetadata }

        let matching = tracks.filter { albumKey(for: $0) == key }
        return matching.sorted(by: trackOrdering)
    }

    // MARK: - Complete mode

    /// Picks a random album from the source and returns the catalog version
    /// (so any missing library tracks are filled in automatically).
    func completeAlbum(from source: SourceSelection) async throws -> Album {
        let seedTrack: Track
        switch source {
        case .entireLibrary:
            seedTrack = try await randomLibraryTrack()
        case .playlist(let playlist):
            let tracks = try await loadTracks(in: playlist)
            guard let pick = tracks.randomElement() else { throw MusicKitServiceError.emptySource }
            seedTrack = pick
        }
        return try await resolveCatalogAlbum(for: seedTrack)
    }

    // MARK: - Catalog resolution

    private func resolveCatalogAlbum(for track: Track) async throws -> Album {
        if case .song(let song) = track {
            if let detailed = try? await song.with([.albums]),
               let firstAlbum = detailed.albums?.first {
                return (try? await firstAlbum.with([.tracks])) ?? firstAlbum
            }
        }

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
