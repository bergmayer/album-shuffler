import Foundation
import MusicKit

enum SourceSelection: Hashable, Identifiable {
    case entireLibrary
    case playlist(Playlist)

    var id: String {
        switch self {
        case .entireLibrary:       return "entire-library"
        case .playlist(let p):     return "playlist-\(p.id.rawValue)"
        }
    }

    var displayName: String {
        switch self {
        case .entireLibrary:       return "Entire Library"
        case .playlist(let p):     return p.name
        }
    }

    var isPlaylist: Bool {
        if case .playlist = self { return true }
        return false
    }

    var playlist: Playlist? {
        if case .playlist(let p) = self { return p }
        return nil
    }
}
