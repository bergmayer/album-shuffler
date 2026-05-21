import Foundation
import WatchConnectivity

/// Owns the WCSession on the watch side and sends a "shuffle" payload to the
/// iPhone. Uses sendMessage when reachable for low latency; falls back to
/// transferUserInfo so the action survives an unreachable phone.
final class WatchBridge: NSObject {
    static let shared = WatchBridge()

    private let session = WCSession.default

    private override init() {
        super.init()
        guard WCSession.isSupported() else { return }
        session.delegate = self
        session.activate()
    }

    func sendShuffle() {
        let payload: [String: Any] = ["action": "shuffle", "at": Date().timeIntervalSince1970]

        guard session.activationState == .activated else {
            session.activate()
            session.transferUserInfo(payload)
            return
        }

        if session.isReachable {
            session.sendMessage(payload, replyHandler: nil) { [weak self] _ in
                // Direct send failed — queue for delivery.
                self?.session.transferUserInfo(payload)
            }
        } else {
            session.transferUserInfo(payload)
        }
    }
}

extension WatchBridge: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {}
}
