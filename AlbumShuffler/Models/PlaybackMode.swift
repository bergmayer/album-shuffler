import Foundation

enum PlaybackMode: String, CaseIterable, Identifiable, Hashable {
    case subset    // Strict-subset within a playlist: only the playlist's tracks of one album.
    case complete  // Whole catalog album, with missing-track fallback.

    var id: String { rawValue }

    var title: String {
        switch self {
        case .subset:   return "Playlist Subset"
        case .complete: return "Complete Album"
        }
    }

    var detail: String {
        switch self {
        case .subset:
            return "Plays only the album tracks that exist in the chosen playlist, in album order."
        case .complete:
            return "Plays the entire catalog album, filling in any tracks missing from your library."
        }
    }

    var requiresPlaylist: Bool {
        self == .subset
    }
}
