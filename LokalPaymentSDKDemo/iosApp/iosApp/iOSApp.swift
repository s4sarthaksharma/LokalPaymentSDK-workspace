import SwiftUI
import UIKit
import LokalPaymentSDKDemo

// Hosts the shared Compose UI. The Kotlin entry point
// MainViewControllerKt.MainViewController() returns a ComposeUIViewController
// rendering App(); this wrapper bridges it into SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}
