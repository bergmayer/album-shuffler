import WidgetKit
import SwiftUI

// MARK: - Timeline

struct WatchShuffleEntry: TimelineEntry {
    let date: Date
}

struct WatchShuffleProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchShuffleEntry {
        WatchShuffleEntry(date: .now)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchShuffleEntry) -> Void) {
        completion(WatchShuffleEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchShuffleEntry>) -> Void) {
        completion(Timeline(entries: [WatchShuffleEntry(date: .now)], policy: .never))
    }
}

// MARK: - Deep link

private let triggerURL = URL(string: "albumshuffler://shuffle")!

// MARK: - Chaos star drawing

private struct ChaosStarGlyph: View {
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

// MARK: - View

struct AlbumShufflerWatchWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryInline:
            Label("Shuffle Album", systemImage: "shuffle")
                .widgetURL(triggerURL)

        case .accessoryRectangular:
            HStack(spacing: 6) {
                ChaosStarGlyph()
                    .frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Album Shuffler")
                        .font(.caption2.weight(.semibold))
                    Text("Tap to play")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .unredacted()
                Spacer(minLength: 0)
            }
            .widgetURL(triggerURL)

        default:
            ChaosStarGlyph()
                .widgetURL(triggerURL)
        }
    }
}

// MARK: - Widget

struct AlbumShufflerWatchWidget: Widget {
    let kind: String = "AlbumShufflerWatchWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchShuffleProvider()) { _ in
            AlbumShufflerWatchWidgetView()
                .containerBackground(for: .widget) { Color.black }
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Album Shuffle")
        .description("Tap to shuffle a random album on your iPhone.")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline,
            .accessoryCorner
        ])
    }
}

@main
struct AlbumShufflerWatchWidgetBundle: WidgetBundle {
    var body: some Widget {
        AlbumShufflerWatchWidget()
    }
}
