package dev.kbt117.keyproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.kbt117.keyproxy.presentation.navigation.KeyProxyApp
import dev.kbt117.keyproxy.presentation.theme.KeyProxyTheme

/**
 * The single Activity. All navigation is in-Compose.
 *
 * `enableEdgeToEdge()` is mandatory rather than cosmetic here: for apps
 * targeting Android 16 (API 36), `windowOptOutEdgeToEdgeEnforcement` is disabled
 * and content draws behind the system bars whether you ask for it or not. So the
 * app opts in explicitly and handles insets in the Compose tree instead of
 * silently shipping overlapping UI.
 *
 * No custom back handling is registered, which is also the Android 16 posture:
 * predictive back is on by default for API 36 targets, `onBackPressed()` is no
 * longer called, and `android:enableOnBackInvokedCallback="true"` is set in the
 * manifest. Letting the framework own back gives the cross-activity animation
 * for free.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KeyProxyTheme {
                KeyProxyApp()
            }
        }
    }
}
