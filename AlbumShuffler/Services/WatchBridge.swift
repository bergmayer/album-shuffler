import Foundation
#if canImport(WatchConnectivity)
import WatchConnectivity

/// Listens for "shuffle" messages sent from the Apple Watch and forwards them
/// to the iPhone view model. Lives for the whole iPhone-app lifecycle.
final class WatchBridge: NSObject {
    static let shared = WatchBridge()

    weak var viewModel: MusicPlayerViewModel?

    private override init() {
        super.init()
        activate()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }
}

extension WatchBridge: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {}

    // iPhone-side callbacks required by the protocol but not relevant for our flow.
    func sessionDidBecomeInactive(_ session: WCSession) {}
    func sessionDidDeactivate(_ session: WCSession) { WCSession.default.activate() }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        handle(message: message)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        handle(message: message)
        replyHandler(["ok": true])
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        handle(message: userInfo)
    }

    private func handle(message: [String: Any]) {
        guard message["action"] as? String == "shuffle" else { return }
        Task { @MainActor in
            await self.viewModel?.playRandomAlbum()
        }
    }
}
#endif
