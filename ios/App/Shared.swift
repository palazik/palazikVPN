import Foundation

/// Persists profiles, subscriptions and settings in the shared App Group container, and
/// builds the active Xray config for the extension to run.
final class ProfileStore: ObservableObject {
    @Published var profiles: [VpnProfile] = []
    @Published var subscriptions: [Subscription] = []
    @Published var settings = AppSettings()
    @Published var activeId: String? = nil

    private let d = AppGroup.defaults

    init() { load() }

    func load() {
        if let data = d?.data(forKey: AppGroup.profilesKey),
           let v = try? JSONDecoder().decode([VpnProfile].self, from: data) { profiles = v }
        if let data = d?.data(forKey: AppGroup.subscriptionsKey),
           let v = try? JSONDecoder().decode([Subscription].self, from: data) { subscriptions = v }
        if let data = d?.data(forKey: AppGroup.settingsKey),
           let v = try? JSONDecoder().decode(AppSettings.self, from: data) { settings = v }
        activeId = d?.string(forKey: AppGroup.activeIdKey)
    }

    func save() {
        d?.set(try? JSONEncoder().encode(profiles), forKey: AppGroup.profilesKey)
        d?.set(try? JSONEncoder().encode(subscriptions), forKey: AppGroup.subscriptionsKey)
        d?.set(try? JSONEncoder().encode(settings), forKey: AppGroup.settingsKey)
        d?.set(activeId, forKey: AppGroup.activeIdKey)
    }

    var activeProfile: VpnProfile? { profiles.first { $0.id == activeId } }

    func add(_ p: VpnProfile) {
        profiles.append(p)
        if activeId == nil { activeId = p.id }
        save()
    }

    func remove(_ p: VpnProfile) {
        profiles.removeAll { $0.id == p.id }
        if activeId == p.id { activeId = profiles.first?.id }
        save()
    }

    func setActive(_ p: VpnProfile) { activeId = p.id; save() }

    func update(_ p: VpnProfile) {
        if let i = profiles.firstIndex(where: { $0.id == p.id }) { profiles[i] = p; save() }
    }

    /// Build the active profile's Xray config and write it where the extension reads it.
    /// Returns false if there is no active profile.
    @discardableResult
    func writeActiveConfig() -> Bool {
        guard let p = activeProfile else { return false }
        d?.set(XrayConfigBuilder.build(p, settings), forKey: AppGroup.activeConfigKey)
        return true
    }
}
