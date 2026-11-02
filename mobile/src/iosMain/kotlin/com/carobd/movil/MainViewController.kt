package com.carobd.movil

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Punto de entrada para iOS. En Swift se llama MainViewControllerKt.MainViewController(). */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
