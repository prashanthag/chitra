import SwiftUI

/// A password entry masked by default, with an eye to reveal it — the same
/// control the web and Android clients use.
struct PasswordField: View {
    let title: String
    @Binding var text: String
    @State private var shown = false

    init(_ title: String, text: Binding<String>) {
        self.title = title
        _text = text
    }

    var body: some View {
        HStack {
            Group {
                if shown {
                    TextField(title, text: $text)
                } else {
                    SecureField(title, text: $text)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Button { shown.toggle() } label: {
                Image(systemName: shown ? "eye.slash" : "eye").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(shown ? "Hide password" : "Show password")
        }
    }
}

/// Sign-in sheet, shown whenever the server answers 401 without a session.
struct LoginSheet: View {
    let serverURL: String
    var onSignedIn: () -> Void

    @ObservedObject private var auth = AuthSession.shared
    @State private var name = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    PasswordField("Password", text: $password)
                } footer: {
                    if let error { Text(error).foregroundStyle(Palette.error) }
                    else { Text("Ask the admin of \(serverURL) for an account.") }
                }
            }
            .navigationTitle("Sign In")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { auth.needsLogin = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Signing In…" : "Sign In") { signIn() }
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty)
                }
            }
        }
        .interactiveDismissDisabled(auth.authRequired && !auth.isSignedIn)
    }

    private func signIn() {
        busy = true
        error = nil
        Task {
            do {
                try await auth.login(serverURL: serverURL, name: name.trimmingCharacters(in: .whitespaces), password: password)
                onSignedIn()
            } catch {
                self.error = "Wrong name or password"
            }
            busy = false
        }
    }
}

/// The Locked folder asks for the account password on every open.
struct UnlockSheet: View {
    let serverURL: String

    @ObservedObject private var auth = AuthSession.shared
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PasswordField("Account password", text: $password)
                } header: {
                    Label("Locked Folder", systemImage: "lock")
                } footer: {
                    if let error { Text(error).foregroundStyle(Palette.error) }
                    else { Text("Closes again after \(auth.idleSeconds) seconds without activity.") }
                }
            }
            .navigationTitle("Unlock")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { auth.afterUnlock = nil; auth.needsUnlock = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Unlocking…" : "Unlock") { unlock() }
                        .disabled(busy || password.isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func unlock() {
        busy = true
        error = nil
        Task {
            do {
                try await auth.unlock(serverURL: serverURL, password: password)
            } catch {
                self.error = "Wrong password"
            }
            busy = false
        }
    }
}

/// Creating an account: the very first one (the admin, no login needed) or,
/// as an admin, another user with a role.
struct NewAccountSheet: View {
    let serverURL: String
    /// true = bootstrap the admin account on an open server.
    var firstAdmin: Bool
    var onCreated: (User, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var password = ""
    @State private var confirm = ""
    @State private var role = "member"
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    PasswordField("Password", text: $password)
                    PasswordField("Confirm password", text: $confirm)
                    if !firstAdmin {
                        Picker("Role", selection: $role) {
                            Text("Member").tag("member")
                            Text("Admin").tag("admin")
                        }
                    }
                } footer: {
                    if let error { Text(error).foregroundStyle(Palette.error) }
                    else if firstAdmin { Text("From then on everyone must sign in. Admins manage accounts, the Locked folder and the Trash; members can add and organise but not delete.") }
                    else { Text("Members can upload, favourite and organise, but cannot delete or change files.") }
                }
            }
            .navigationTitle(firstAdmin ? "Create Admin" : "Add User")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Creating…" : "Create") { create() }
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty || password != confirm)
                }
            }
        }
    }

    private func create() {
        busy = true
        error = nil
        Task {
            do {
                let created = try await PhotoAPI(baseUrl: serverURL).createUser(
                    name: name.trimmingCharacters(in: .whitespaces), password: password,
                    role: firstAdmin ? "admin" : role)
                onCreated(created, password)
                dismiss()
            } catch {
                self.error = "Could not create the account (\(error.localizedDescription))"
            }
            busy = false
        }
    }
}

struct ChangePasswordSheet: View {
    let serverURL: String

    @Environment(\.dismiss) private var dismiss
    @State private var old = ""
    @State private var new = ""
    @State private var confirm = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PasswordField("Current password", text: $old)
                    PasswordField("New password", text: $new)
                    PasswordField("Confirm new password", text: $confirm)
                } footer: {
                    if let error { Text(error).foregroundStyle(Palette.error) }
                }
            }
            .navigationTitle("Change Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Saving…" : "Save") { save() }
                        .disabled(busy || old.isEmpty || new.isEmpty || new != confirm)
                }
            }
        }
    }

    private func save() {
        busy = true
        error = nil
        Task {
            do {
                try await PhotoAPI(baseUrl: serverURL).changePassword(old: old, new: new)
                dismiss()
            } catch {
                self.error = "Could not change the password (wrong current password?)"
            }
            busy = false
        }
    }
}

/// While locked content is on screen, a minute without a touch closes it:
/// the session is relocked on the server and `onIdle` leaves the screen.
/// A full-screen viewer on top counts as activity (its touches never reach
/// this layer), so callers pass `enabled: false` while one is open.
struct IdleRelock: ViewModifier {
    var enabled: Bool
    var seconds: Int
    var onIdle: () -> Void

    @State private var last = Date()

    func body(content: Content) -> some View {
        content
            .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in last = Date() })
            .task(id: enabled) {
                guard enabled else { return }
                last = Date()
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    guard !Task.isCancelled else { return }
                    if Date().timeIntervalSince(last) >= Double(seconds) {
                        onIdle()
                        return
                    }
                }
            }
    }
}

extension View {
    func idleRelock(enabled: Bool, seconds: Int, onIdle: @escaping () -> Void) -> some View {
        modifier(IdleRelock(enabled: enabled, seconds: seconds, onIdle: onIdle))
    }
}
