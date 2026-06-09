import SwiftUI

@main
struct palazikVPNApp: App {
    @StateObject private var store = ProfileStore()
    @StateObject private var vpn = VPNManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(vpn)
        }
    }
}
