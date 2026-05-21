import WidgetKit
import SwiftUI

// MARK: - Timeline entry

struct ShuffleEntry: TimelineEntry {
    let date: Date
}

// MARK: - Provider

struct ShuffleProvider: TimelineProvider {
    func placeholder(in context: Context) -> ShuffleEntry {
        ShuffleEntry(date: .now)
    }

    func getSnapshot(in context: Context, completion: @escaping (ShuffleEntry) -> Void) {
        completion(ShuffleEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ShuffleEntry>) -> Void) {
        let entry = ShuffleEntry(date: .now)
        let next = Calendar.current.date(byAdding: .hour, value: 1, to: .now) ?? .now.addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

// MARK: - Deep link

private let shuffleURL: URL = URL(string: "albumshuffler://shuffle")!

// MARK: - Star glyph (drawn in SwiftUI so the gallery preview can't redact it)

private struct ChaosStarGlyph: View {
    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2
            let centerR = r * 0.22       // central disc radius
            let shaftWidth = r * 0.07    // arm thickness
            let headLen = r * 0.22       // arrowhead length
            let headWidth = r * 0.16     // arrowhead half-width

            for i in 0..<8 {
                let angle = Double(i) * .pi / 4 - .pi / 2 // start pointing up
                let c = cos(angle), s = sin(angle)
                let px = cos(angle + .pi / 2), py = sin(angle + .pi / 2)

                // Shaft from edge of center circle to base of arrowhead.
                let shaftStart = CGPoint(x: center.x + c * centerR, y: center.y + s * centerR)
                let shaftEnd   = CGPoint(x: center.x + c * (r - headLen), y: center.y + s * (r - headLen))
                var shaftPath = Path()
                shaftPath.move(to: shaftStart)
                shaftPath.addLine(to: shaftEnd)
                context.stroke(shaftPath, with: .color(.white), lineWidth: shaftWidth)

                // Arrowhead triangle.
                let tip  = CGPoint(x: center.x + c * r, y: center.y + s * r)
                let baseL = CGPoint(x: shaftEnd.x + px * headWidth, y: shaftEnd.y + py * headWidth)
                let baseR = CGPoint(x: shaftEnd.x - px * headWidth, y: shaftEnd.y - py * headWidth)
                var headPath = Path()
                headPath.move(to: tip)
                headPath.addLine(to: baseL)
                headPath.addLine(to: baseR)
                headPath.closeSubpath()
                context.fill(headPath, with: .color(.white))
            }

            // Central filled disc.
            let discRect = CGRect(x: center.x - centerR, y: center.y - centerR,
                                  width: centerR * 2, height: centerR * 2)
            context.fill(Path(ellipseIn: discRect), with: .color(.white))
        }
    }
}

// MARK: - Widget view

struct AlbumShufflerWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryInline:
            // Inline strip above the clock — system only allows a Label with an
            // SF Symbol here, not a custom image asset. Best we can do is use
            // a radiating-arrow SF Symbol that suggests the chaos star.
            Label("Album Shuffler", systemImage: "arrow.up.and.down.and.arrow.left.and.right")
                .widgetURL(shuffleURL)

        case .accessoryRectangular:
            HStack(spacing: 8) {
                ChaosStarGlyph()
                    .frame(width: 34, height: 34)
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
            .widgetURL(shuffleURL)

        default:
            // accessoryCircular and systemSmall: star fills the whole tile.
            ChaosStarGlyph()
                .widgetURL(shuffleURL)
        }
    }
}

// MARK: - Widget definition

struct AlbumShufflerWidget: Widget {
    let kind: String = "AlbumShufflerWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ShuffleProvider()) { entry in
            AlbumShufflerWidgetView()
                .containerBackground(for: .widget) { Color.black }
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Album Shuffle")
        .description("Tap to play a random album using the app's current source and mode.")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline,
            .systemSmall
        ])
    }
}

// MARK: - Widget bundle

@main
struct AlbumShufflerWidgetBundle: WidgetBundle {
    var body: some Widget {
        AlbumShufflerWidget()
    }
}
