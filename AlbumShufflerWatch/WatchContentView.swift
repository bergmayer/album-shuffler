import SwiftUI

struct WatchContentView: View {
    @State private var didSend = false
    @State private var bounce = false

    var body: some View {
        VStack(spacing: 14) {
            ChaosStarGlyph()
                .frame(width: 56, height: 56)
                .scaleEffect(bounce ? 1.15 : 1.0)
                .animation(.spring(response: 0.3, dampingFraction: 0.5), value: bounce)

            Text(didSend ? "Sent to iPhone" : "Album Shuffler")
                .font(.headline)
                .multilineTextAlignment(.center)

            Button {
                send()
            } label: {
                Label("Shuffle", systemImage: "shuffle")
            }
            .buttonStyle(.borderedProminent)
            .tint(.white)
        }
        .padding()
        .onOpenURL { url in
            // Complication tap deep-links here; fire immediately.
            if url.scheme == "albumshuffler" { send() }
        }
    }

    private func send() {
        WatchBridge.shared.sendShuffle()
        didSend = true
        bounce.toggle()
    }
}

// MARK: - Chaos star (matches the iOS widget)

struct ChaosStarGlyph: View {
    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2
            let centerR = r * 0.22
            let shaftWidth = r * 0.07
            let headLen = r * 0.22
            let headWidth = r * 0.16

            for i in 0..<8 {
                let angle = Double(i) * .pi / 4 - .pi / 2
                let c = cos(angle), s = sin(angle)
                let px = cos(angle + .pi / 2), py = sin(angle + .pi / 2)

                let shaftStart = CGPoint(x: center.x + c * centerR, y: center.y + s * centerR)
                let shaftEnd = CGPoint(x: center.x + c * (r - headLen), y: center.y + s * (r - headLen))
                var shaftPath = Path()
                shaftPath.move(to: shaftStart)
                shaftPath.addLine(to: shaftEnd)
                context.stroke(shaftPath, with: .color(.white), lineWidth: shaftWidth)

                let tip = CGPoint(x: center.x + c * r, y: center.y + s * r)
                let baseL = CGPoint(x: shaftEnd.x + px * headWidth, y: shaftEnd.y + py * headWidth)
                let baseR = CGPoint(x: shaftEnd.x - px * headWidth, y: shaftEnd.y - py * headWidth)
                var headPath = Path()
                headPath.move(to: tip)
                headPath.addLine(to: baseL)
                headPath.addLine(to: baseR)
                headPath.closeSubpath()
                context.fill(headPath, with: .color(.white))
            }

            let discRect = CGRect(x: center.x - centerR, y: center.y - centerR,
                                  width: centerR * 2, height: centerR * 2)
            context.fill(Path(ellipseIn: discRect), with: .color(.white))
        }
    }
}
