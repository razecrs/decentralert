import SwiftUI

@main
struct DecentralertApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    @State private var countdown: Int? = nil

    var body: some View {
        ZStack {
            Color(red: 0.027, green: 0.063, blue: 0.094).ignoresSafeArea()
            VStack(spacing: 28) {
                Text("DECENTRALERT").font(.headline).tracking(2)
                Spacer()
                if let countdown {
                    Text("Sending in").foregroundStyle(.secondary)
                    Text("\(countdown)").font(.system(size: 76, weight: .black, design: .rounded))
                    HStack {
                        Button("Cancel") { self.countdown = nil }.buttonStyle(.bordered)
                        Button("Send now") { self.countdown = 0 }.buttonStyle(.borderedProminent)
                    }
                } else {
                    Text("Need urgent help?").font(.largeTitle.bold())
                    Button("SOS") { countdown = 30 }
                        .font(.system(size: 42, weight: .black))
                        .frame(width: 210, height: 210)
                        .background(Color.red)
                        .foregroundStyle(.white)
                        .clipShape(Circle())
                }
                Spacer()
                Text("Prototype · always call 112 when every second counts")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(24)
        }
    }
}
