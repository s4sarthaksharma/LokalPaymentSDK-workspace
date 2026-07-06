package com.getlokalapp.paymentsdk.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point the iOS app hosts. Swift calls MainViewControllerKt.MainViewController(). */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
