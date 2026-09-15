package dev.kbt117.keyproxy

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's entry point. Nothing else happens here on purpose.
 *
 * In particular the proxy server is *not* started from `onCreate`: bringing up a
 * listening socket on every cold start would surprise the user and burn battery.
 * Startup is always an explicit toggle on the Dashboard, which is what makes the
 * `specialUse` foreground service defensible under the platform's
 * user-initiated-work rules.
 */
@HiltAndroidApp
class KeyProxyApplication : Application()
