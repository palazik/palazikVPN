import SwiftUI
import NetworkExtension
import UIKit

struct ContentView: View {
    var body: some View {
        TabView {
            HomeView().tabItem { Label("Home", systemImage: "bolt.fill") }
            ProfilesView().tabItem { Label("Profiles", systemImage: "list.bullet") }
            SubscriptionsView().tabItem { Label("Subs", systemImage: "arrow.triangle.2.circlepath") }
            SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}

// ── Home ──────────────────────────────────────────────────────────────────────

struct HomeView: View {
    @EnvironmentObject var store: ProfileStore
    @EnvironmentObject var vpn: VPNManager
    @State private var errorText: String?

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Spacer()
                Button(action: toggle) {
                    ZStack {
                        Circle()
                            .fill(vpn.isConnected ? Color.green : Color.accentColor)
                            .frame(width: 168, height: 168)
                            .shadow(color: (vpn.isConnected ? Color.green : Color.accentColor).opacity(0.4), radius: 24)
                        Image(systemName: "power").font(.system(size: 60, weight: .bold)).foregroundColor(.white)
                    }
                }
                .disabled(vpn.isBusy)
                Text(statusText).font(.title3).bold()
                Text(store.activeProfile?.name ?? "No profile selected").foregroundColor(.secondary)
                if let errorText {
                    Text(errorText).font(.footnote).foregroundColor(.red)
                        .multilineTextAlignment(.center).padding(.horizontal)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .navigationTitle("palazikVPN")
        }
    }

    private var statusText: String {
        switch vpn.status {
        case .connected:     return "Connected"
        case .connecting:    return "Connecting…"
        case .disconnecting: return "Disconnecting…"
        case .reasserting:   return "Reconnecting…"
        default:             return "Disconnected"
        }
    }

    private func toggle() {
        errorText = nil
        if vpn.isConnected { vpn.disconnect(); return }
        guard store.writeActiveConfig() else { errorText = "Select a profile first"; return }
        Task {
            do { try await vpn.connect() } catch { errorText = error.localizedDescription }
        }
    }
}

// ── Profiles ──────────────────────────────────────────────────────────────────

enum ProfileSheet: Identifiable {
    case link, new
    case edit(VpnProfile)
    var id: String {
        switch self {
        case .link: return "link"
        case .new: return "new"
        case .edit(let p): return "edit-\(p.id)"
        }
    }
}

struct ProfilesView: View {
    @EnvironmentObject var store: ProfileStore
    @State private var sheet: ProfileSheet?
    @State private var linkText = ""
    @State private var addError: String?
    @State private var warpBusy = false

    var body: some View {
        NavigationView {
            List {
                ForEach(store.profiles) { p in
                    Button { sheet = .edit(p) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).foregroundColor(.primary)
                                Text("\(p.proto.rawValue) · \(p.address):\(p.port)")
                                    .font(.caption2).foregroundColor(.secondary).lineLimit(1)
                            }
                            Spacer()
                            if store.activeId == p.id {
                                Image(systemName: "checkmark.circle.fill").foregroundColor(.accentColor)
                            }
                        }
                    }
                    .swipeActions(edge: .leading) {
                        Button("Active") { store.setActive(p) }.tint(.accentColor)
                    }
                }
                .onDelete { idx in idx.map { store.profiles[$0] }.forEach(store.remove) }
            }
            .overlay {
                if store.profiles.isEmpty {
                    Text("No profiles yet.\nTap + to add.")
                        .multilineTextAlignment(.center).foregroundColor(.secondary)
                }
                if warpBusy { ProgressView("Setting up WARP…") }
            }
            .navigationTitle("Profiles")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { sheet = .new } label: { Label("Add manually", systemImage: "square.and.pencil") }
                        Button { sheet = .link } label: { Label("Paste link", systemImage: "link") }
                        Button { generateWarp() } label: { Label("Generate WARP", systemImage: "cloud") }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $sheet) { which in
                switch which {
                case .link: linkSheet
                case .new:  ProfileEditView(profile: VpnProfile(), isNew: true).environmentObject(store)
                case .edit(let p): ProfileEditView(profile: p, isNew: false).environmentObject(store)
                }
            }
        }
    }

    private var linkSheet: some View {
        NavigationView {
            Form {
                Section("Share link") {
                    TextField("vless:// vmess:// trojan:// …", text: $linkText)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
                Button("Paste from clipboard") { if let s = UIPasteboard.general.string { linkText = s } }
                if let addError { Text(addError).foregroundColor(.red).font(.footnote) }
            }
            .navigationTitle("Add from Link")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { resetLink() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addLink() }
                        .disabled(linkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func addLink() {
        let link = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p = ProfileCodec.decode(link) else { addError = "Couldn't parse that link"; return }
        store.add(p); resetLink()
    }

    private func resetLink() { linkText = ""; addError = nil; sheet = nil }

    private func generateWarp() {
        warpBusy = true
        Task {
            let p = await WarpProvisioner.register()
            await MainActor.run {
                warpBusy = false
                if let p { store.add(p) }
            }
        }
    }
}

// ── Subscriptions ──────────────────────────────────────────────────────────────

struct SubscriptionsView: View {
    @EnvironmentObject var store: ProfileStore
    @State private var showAdd = false
    @State private var name = ""
    @State private var url = ""
    @State private var busy = false

    var body: some View {
        NavigationView {
            List {
                ForEach(store.subscriptions) { sub in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(sub.name).font(.headline)
                        Text("\(sub.profileCount) profiles").font(.caption).foregroundColor(.secondary)
                        if sub.hasUsageInfo {
                            Text(usageText(sub)).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                .onDelete { idx in idx.forEach { removeSub(store.subscriptions[$0]) } }
            }
            .overlay {
                if store.subscriptions.isEmpty {
                    Text("No subscriptions.\nTap + to add a URL.")
                        .multilineTextAlignment(.center).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Subscriptions")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showAdd) {
                NavigationView {
                    Form {
                        TextField("Name", text: $name)
                        TextField("https://…", text: $url)
                            .autocorrectionDisabled().textInputAutocapitalization(.never)
                    }
                    .navigationTitle("Add Subscription")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showAdd = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(busy ? "Adding…" : "Add") { addSub() }.disabled(url.isEmpty || busy)
                        }
                    }
                }
            }
        }
    }

    private func usageText(_ s: Subscription) -> String {
        var t = ByteFmt.string(s.usedBytes)
        if s.totalBytes > 0 { t += " / " + ByteFmt.string(s.totalBytes) }
        return t
    }

    private func addSub() {
        busy = true
        var sub = Subscription(name: name.isEmpty ? "Subscription" : name, url: url)
        Task {
            let result = await SubscriptionService.fetch(sub, settings: store.settings)
            await MainActor.run {
                sub.profileCount = result.profiles.count
                sub.lastUpdated = Date().timeIntervalSince1970
                if let u = result.usage {
                    sub.uploadBytes = u.upload; sub.downloadBytes = u.download
                    sub.totalBytes = u.total; sub.expireEpochSec = u.expire
                }
                store.subscriptions.append(sub)
                store.profiles.append(contentsOf: result.profiles.map { var p = $0; p.subscriptionId = sub.id; return p })
                store.save()
                busy = false; showAdd = false; name = ""; url = ""
            }
        }
    }

    private func removeSub(_ sub: Subscription) {
        store.subscriptions.removeAll { $0.id == sub.id }
        store.profiles.removeAll { $0.subscriptionId == sub.id }
        store.save()
    }
}

// ── Settings ──────────────────────────────────────────────────────────────────

struct SettingsView: View {
    @EnvironmentObject var store: ProfileStore

    var body: some View {
        NavigationView {
            Form {
                Section("Routing") {
                    Picker("Mode", selection: binding(\.routingMode)) {
                        Text("Rule-based").tag(RoutingMode.ruleBased)
                        Text("Global").tag(RoutingMode.global)
                        Text("Bypass LAN").tag(RoutingMode.bypassLan)
                    }
                    Picker("Domain strategy", selection: binding(\.domainStrategy)) {
                        ForEach(DomainStrategy.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    Toggle("Block ads", isOn: binding(\.blockAds))
                    Toggle("Bypass China", isOn: binding(\.bypassChina))
                    Toggle("FakeDNS", isOn: binding(\.enableFakeDns))
                    Toggle("Route IPv6", isOn: binding(\.enableIpv6))
                }
                Section("DNS") {
                    TextField("Remote DNS", text: binding(\.remoteDns)).autocorrectionDisabled()
                    TextField("Direct DNS", text: binding(\.directDns)).autocorrectionDisabled()
                }
                Section("Custom domains") {
                    TextField("Direct (comma separated)", text: listBinding(\.customDirectDomains)).autocorrectionDisabled()
                    TextField("Blocked (comma separated)", text: listBinding(\.customBlockedDomains)).autocorrectionDisabled()
                }
                Section("Anti-DPI fragment") {
                    TextField("Packets", text: binding(\.fragmentPackets)).autocorrectionDisabled()
                    TextField("Length", text: binding(\.fragmentLength)).autocorrectionDisabled()
                    TextField("Interval", text: binding(\.fragmentInterval)).autocorrectionDisabled()
                }
                Section("Geo files") {
                    TextField("geoip.dat URL", text: binding(\.geoipUrl)).autocorrectionDisabled()
                    TextField("geosite.dat URL", text: binding(\.geositeUrl)).autocorrectionDisabled()
                }
                Section("Subscriptions") {
                    TextField("User-Agent", text: binding(\.subscriptionUserAgent)).autocorrectionDisabled()
                }
                Section("About") {
                    HStack { Text("Version"); Spacer(); Text("2.0.2").foregroundColor(.secondary) }
                    HStack { Text("Engine"); Spacer(); Text("Xray / SwiftyXrayKit").foregroundColor(.secondary) }
                }
            }
            .navigationTitle("Settings")
        }
    }

    private func binding<T>(_ keyPath: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(
            get: { store.settings[keyPath: keyPath] },
            set: { store.settings[keyPath: keyPath] = $0; store.save() }
        )
    }

    /// Edits a [String] setting as a comma-separated text field.
    private func listBinding(_ keyPath: WritableKeyPath<AppSettings, [String]>) -> Binding<String> {
        Binding(
            get: { store.settings[keyPath: keyPath].joined(separator: ", ") },
            set: {
                store.settings[keyPath: keyPath] = $0
                    .split(whereSeparator: { $0 == "," || $0 == "\n" })
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
                store.save()
            }
        )
    }
}

enum ByteFmt {
    static func string(_ bytes: Int64) -> String {
        let b = max(bytes, 0)
        if b < 1024 { return "\(b) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var value = Double(b), i = -1
        while value >= 1024 && i < units.count - 1 { value /= 1024; i += 1 }
        return String(format: "%.1f %@", value, units[max(i, 0)])
    }
}
