import UIKit
import SwiftUI
import CarobdMovil

/// Muestra la interfaz de Compose Multiplatform (mobile/src/commonMain) dentro de SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // Compose maneja su propio teclado
    }
}
