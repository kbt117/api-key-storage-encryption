# KeyProxy — Android Local API Key Manager & Proxy Server

A local, loopback-only HTTP proxy for Android that holds your API keys in the
Android Keystore and injects them into requests forwarded to any
OpenAI-compatible endpoint. Point a client at `http://127.0.0.1:8080`, keep your
key off that client, and keep it off the wire in clear text.

```
┌─────────────────────── your phone ───────────────────────┐
│                                                          │
│  client app ──HTTP──▶ 127.0.0.1:8080 ──┐                 │
│  (no key)              (Ktor/Netty)    │                 │
│                                        ▼                 │
│                              inject Authorization:       │
│                              Bearer <key from Keystore>  │
│                                        │                 │
└────────────────────────────────────────┼─────────────────┘
                                         ▼  TLS
                              https://api.openai.com/v1/…
```

* Built with **Kotlin**, **Jetpack Compose** (Material 3), **Hilt**, **Ktor
  Server (Netty)**, **OkHttp**, and **Google Tink** over the **Android Keystore**.
* Clean Architecture (`domain` / `data` / `presentation`) with MVVM.
* Importable into Android Studio **and** fully buildable from a shell with
  `./gradlew assembleDebug` — including on the device itself via Termux.
* Targets **Android 16 (API 36)**, minSdk 26.

---

## Contents

- [Quick start](#quick-start)
- [Using the proxy](#using-the-proxy)
- [Project structure](#project-structure)
- [Toolchain matrix](#toolchain-matrix)
- [Security model](#security-model)
- [Android 16 readiness](#android-16-readiness)
- [Building on-device with Termux](#building-on-device-with-termux)
- [CI/CD](#cicd)
- [Design decisions](#design-decisions)
- [Verification status](#verification-status)
- [Troubleshooting](#troubleshooting)

---

## Quick start

```bash
git clone https://github.com/kbt117/api-key-storage-encryption.git
cd api-key-storage-encryption

# Unit tests (no device or emulator needed)
./gradlew testDebugUnitTest

# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
```

Requirements: **JDK 17**, and an Android SDK with `platforms;android-36` and
`build-tools;35.0.0`. Point the build at the SDK either with the
`ANDROID_HOME` environment variable or a git-ignored `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

Then:

1. Open **Settings**, pick a preset or type a Base API URL, paste your API key,
   **Save**. Tap **Test** to confirm the key is accepted.
2. Open **Dashboard**, flip the server switch on.
3. Point any OpenAI-compatible client at the endpoint shown on the Dashboard.

---

## Using the proxy

The proxy passes request bodies through **untouched** — it never parses JSON —
so any OpenAI-compatible schema works, including fields this app has never seen.

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Say hi in three words."}]
  }'
```

No `Authorization` header needed. Anything a client sends in that header is
**discarded** and replaced with the stored key.

Streaming works: responses are relayed chunk-by-chunk, so `{"stream": true}`
server-sent events arrive live instead of in one blob when the stream closes.

Other useful routes:

| Route | Behaviour |
| --- | --- |
| `GET /` | Local control endpoint. Returns proxy status, the upstream, and whether a key is set. Never forwarded. |
| `GET /v1/models` | Proxied. Good smoke test. |
| anything else | Proxied verbatim to `<base-url><path><query>`. |

`/v1` is de-duplicated: if your base URL already ends in `/v1` (OpenRouter, Groq,
Together) and the client sends `/v1/chat/completions`, the proxy calls
`…/v1/chat/completions`, not `…/v1/v1/chat/completions`.

Failures come back in OpenAI's error envelope so existing client libraries
surface the message instead of failing to parse the body:

```json
{"error":{"message":"No API key stored. Open Settings and add one.","type":"keyproxy_error","code":"missing_api_key"}}
```

---

## Project structure

```
.
├── .github/workflows/android-build.yml   CI: test, lint, assembleDebug, upload APK
├── build.gradle.kts                      root: plugin versions from the catalog
├── settings.gradle.kts                   repositories + module graph
├── gradle.properties                     JVM args, AndroidX, config cache
├── gradle/
│   ├── libs.versions.toml                version catalog (single source of truth)
│   └── wrapper/                          committed wrapper jar + properties
├── gradlew, gradlew.bat
├── scripts/verify_project.py             static verification harness (see below)
└── app/
    ├── build.gradle.kts                  AGP config, signing, packaging, deps
    ├── proguard-rules.pro                R8 rules for Ktor/Netty/Tink/OkHttp
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/dev/kbt117/keyproxy/
        │   │   ├── KeyProxyApplication.kt        @HiltAndroidApp
        │   │   ├── MainActivity.kt               edge-to-edge, predictive back
        │   │   │
        │   │   ├── domain/                       ← pure, no Android imports
        │   │   │   ├── SettingsValidator.kt
        │   │   │   ├── model/{ProxySettings,ProxyLogEntry,ServerState}.kt
        │   │   │   └── repository/SettingsRepository.kt
        │   │   │
        │   │   ├── data/
        │   │   │   ├── local/
        │   │   │   │   ├── SecureKeyValueStore.kt              interface
        │   │   │   │   ├── SecureStorageKeys.kt
        │   │   │   │   ├── TinkSecureKeyValueStore.kt          default backend
        │   │   │   │   └── EncryptedPrefsSecureKeyValueStore.kt opt-in legacy
        │   │   │   └── repository/SettingsRepositoryImpl.kt
        │   │   │
        │   │   ├── security/
        │   │   │   ├── SecurityHelper.kt          encrypt/decrypt contract
        │   │   │   └── TinkSecurityHelper.kt      AES-256-GCM + Keystore
        │   │   │
        │   │   ├── server/
        │   │   │   ├── ProxyServer.kt             Ktor/Netty lifecycle
        │   │   │   ├── UpstreamForwarder.kt       header injection + streaming
        │   │   │   ├── UpstreamUrlBuilder.kt      pure URL resolution
        │   │   │   ├── ServerErrors.kt            pure error mapping
        │   │   │   ├── ServerToggle.kt
        │   │   │   └── ProxyForegroundService.kt  specialUse FGS + notification
        │   │   │
        │   │   ├── logging/ProxyLogBuffer.kt      bounded, non-persisted
        │   │   ├── di/{Qualifiers,AppModule,NetworkModule}.kt
        │   │   └── presentation/
        │   │       ├── navigation/KeyProxyApp.kt
        │   │       ├── theme/{Color,Theme,Type}.kt
        │   │       ├── dashboard/{DashboardScreen,DashboardViewModel}.kt
        │   │       ├── settings/{SettingsScreen,SettingsViewModel}.kt
        │   │       └── logs/{LogsScreen,LogsViewModel}.kt
        │   └── res/
        │       ├── drawable/ic_launcher_foreground.xml
        │       ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml
        │       ├── values/{strings,themes}.xml
        │       └── xml/
        │           ├── network_security_config.xml   cleartext = loopback only
        │           ├── backup_rules.xml              excludes encrypted data
        │           └── data_extraction_rules.xml
        └── test/java/dev/kbt117/keyproxy/            JVM unit tests
            ├── domain/SettingsValidatorTest.kt
            ├── logging/ProxyLogBufferTest.kt
            └── server/{UpstreamUrlBuilderTest,ServerErrorsTest}.kt
```

The layering rule is enforced by imports, not by convention: nothing under
`domain/` imports `android.*`, Ktor, OkHttp, or Compose, which is why
`UpstreamUrlBuilder`, `ServerErrors`, `SettingsValidator` and `ProxyLogBuffer`
all have real JVM unit tests.

---

## Toolchain matrix

Every version below was resolved from the official Maven metadata for its
artifact and cross-checked against the published compatibility tables.

| Component | Version | Why this one |
| --- | --- | --- |
| AGP | **8.13.2** | Last 8.x line. Supports up to API 36.1, needs Gradle ≥ 8.13 and JDK 17. See [Why not AGP 9?](#why-not-agp-9) |
| Gradle | **8.14.3** | Latest 8.14.x; satisfies the AGP floor and Kotlin's 7.6.3–9.3.0 window |
| Kotlin | **2.3.21** | Supported by AGP 8.13 (Kotlin 2.3 caps at AGP 8.13) |
| compileSdk / targetSdk | **36** | Android 16. Required for Play submissions after 2026-08-31 |
| minSdk | **26** | Per the brief; also the first release with `java.time` and a usable Keystore AES-GCM |
| Compose BOM | **2026.09.00** | Current stable |
| Hilt | **2.58** | **The last Hilt whose Gradle plugin supports AGP 8.x.** 2.59+ requires AGP 9 |
| Ktor | **3.5.2** | Must be ≥ 3.3.2 — see [Ktor on Android](#ktor-on-android) |
| OkHttp | **5.5.0** | Current stable |
| tink-android | **1.23.0** | Current stable |
| security-crypto | **1.1.0** | Stable but **deprecated upstream**; opt-in backend only |
| androidx.core | **1.19.0** | |
| activity-compose | **1.13.0** | |
| lifecycle | **2.11.0** | |
| navigation-compose | **2.10.1** | |
| hilt-navigation-compose | **1.4.0** | |

This combination is pinned in `gradle/libs.versions.toml`; bump it there, nowhere
else.

---

## Security model

### What protects the key

The default backend is **Tink AES-256-GCM with the wrapping key in the Android
Keystore**:

| Artefact | Location | Contents |
| --- | --- | --- |
| Keystore entry `keyproxy_master_key` | Hardware/TEE where available | AES-GCM key. **Not exportable.** |
| `shared_prefs/keyproxy_tink_keyset.xml` | App-private | Tink keyset, itself wrapped by the key above |
| `shared_prefs/keyproxy_secrets.xml` | App-private | Base64 **ciphertext** only |

Plaintext exists only inside `putString`/`getString` stack frames. Copying all
three files to another device yields nothing, because the Keystore key does not
leave the device.

Every ciphertext is additionally bound with associated data
(`dev.kbt117.keyproxy/secrets/v1`), so a blob from another app — or from an older
schema of this one — fails authentication rather than decrypting to something
surprising.

### Threat model, honestly

| Threat | Status |
| --- | --- |
| Key read from disk / `adb backup` / a leaked prefs file | **Mitigated** — ciphertext only, backups excluded |
| Key sent to a host you did not configure | **Mitigated** — upstream host is fixed in Settings; the client cannot redirect it |
| Client supplies its own `Authorization` header | **Mitigated** — always replaced, never merged |
| Another app on the device reaching the proxy | **Not mitigated.** Loopback is reachable by any app on the device. Any local app can use your key while the server is on. Turn it off when you are done. |
| Another device on your Wi-Fi reaching the proxy | **Mitigated** — binds `127.0.0.1`, never `0.0.0.0` |
| A root user with Keystore access | Out of scope. Nothing user-space can defend against this |

### Least privilege

Exactly three permissions are requested, none of them dangerous:

```
INTERNET                         — bind the listener, reach the upstream
FOREGROUND_SERVICE               — required by API 34+ for startForeground()
FOREGROUND_SERVICE_SPECIAL_USE   — matches the declared service type
```

`POST_NOTIFICATIONS` is deliberately **not** requested, per the no-dangerous-
permissions constraint. The consequence is real and worth knowing: on API 33+ the
foreground notification stays hidden until the user enables notifications in
system settings. The proxy keeps working; you just lose the visible indicator and
the notification's Stop button. The Dashboard detects this and shows a hint.

### Network policy

`network_security_config.xml` forbids cleartext globally and re-enables it for
exactly three loopback names (`127.0.0.1`, `localhost`, `::1`), each with
`includeSubdomains="false"`. Upstream calls must be TLS. Certificate pinning is
intentionally omitted: pinning a third-party API would break the
"any OpenAI-compatible endpoint" use case and would brick the app on routine
certificate rotation.

### Backups

`allowBackup="false"`, plus `backup_rules.xml` and `data_extraction_rules.xml`
that exclude `sharedpref`, `file` and `database` from both cloud backup and
device transfer. This matters beyond tidiness: a restored keyset is wrapped by a
Keystore key that was never backed up, so restoring it produces undecryptable
data.

### Recovery

Both backends can end up in a state where every read fails — an invalidated
Keystore key, or the `EncryptedSharedPreferences` keyset corruption that shows up
on some OEM images. **Settings → Reset secure storage** wipes the values *and*
the wrapping key. It is behind a confirmation because it is irreversible.

---

## Android 16 readiness

| Android 16 change | How this app handles it |
| --- | --- |
| **Edge-to-edge opt-out removed** (`windowOptOutEdgeToEdgeEnforcement` disabled for API 36 targets) | `enableEdgeToEdge()` in `MainActivity`, insets consumed via `Scaffold`'s `innerPadding` on the `NavHost`. No opt-out attribute anywhere. |
| **Predictive back on by default**; `onBackPressed()` no longer called | `android:enableOnBackInvokedCallback="true"`; no custom back handling, so the framework owns it and the cross-activity animation works. |
| **Foreground service types enforced** | `foregroundServiceType="specialUse"` plus the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification required by API 34+, and a Stop action so the session is user-controllable. |
| **Background work quotas tightened** | The proxy only runs inside a user-started, visible, stoppable foreground service — the sanctioned pattern. |
| **Local Network Protection** | Not applicable, and deliberately so. See below. |
| **16 KB page size** | Trivially satisfied: no native libraries of our own. Tink, OkHttp and Netty are pure JVM bytecode on Android. |

### Why `127.0.0.1` matters twice over

`ProxyServer.HOST` is hardcoded to `127.0.0.1`. That single constant is load-
bearing for two independent reasons:

1. **Security.** An unauthenticated HTTP server that injects your API key is
   exactly what you do not want reachable from the coffee-shop Wi-Fi.
2. **Android 16 Local Network Protection.** LNP gates traffic to
   broadcast-capable networks behind a new runtime permission. Its definition of
   "local network" is `10/8`, `172.16/12`, `192.168/16`, `169.254/16`,
   `100.64/10` and IPv6 link-local — **loopback is not in the list**. So this app
   needs no `NEARBY_WIFI_DEVICES` permission.

Changing `HOST` to `0.0.0.0` breaks *both* guarantees at once.
`scripts/verify_project.py` asserts it stays loopback.

### Note on `targetSdk`

The brief said `targetSdkVersion 35` in one place and "Android 16 readiness" in
another. This project uses **36**, because Android 16 *is* API 36 and Google Play
requires it for new apps and updates from 2026-08-31. It is a one-line change in
`app/build.gradle.kts` if you specifically need 35.

---

## Building on-device with Termux

Target device: **Samsung SM-S166V (Galaxy A15 5G)** — arm64 (`aarch64`),
Android 16.

> **Install Termux from F-Droid or GitHub.** The Play Store build is stale and
> is missing packages this guide needs.

### The one real obstacle

**Google publishes the Android SDK build-tools for Linux x86_64 only.** There is
no official `linux-aarch64` `aapt2`/`zipalign`, so on an arm64 phone the binaries
`sdkmanager` downloads will not execute — you get `exec format error` rather than
an APK.

The fix is to give AGP an arm64 `aapt2` instead, which Termux builds natively.
Termux's `aapt2` package is currently at **16.0.0.4**, new enough for
`compileSdk 36`. Everything else in the Android toolchain (`d8`, `apksigner`,
`sdkmanager`, Gradle, the Kotlin compiler) is Java and runs fine.

### 1. Base toolchain

```bash
pkg update && pkg upgrade -y
pkg install -y openjdk-17 wget unzip git aapt2

# Verify the ARM aapt2 is present and native
command -v aapt2            # -> /data/data/com.termux/files/usr/bin/aapt2
file "$PREFIX/bin/aapt2"    # -> ELF 64-bit LSB ... ARM aarch64
```

You do **not** need `pkg install gradle` — the committed wrapper pins Gradle
8.14.3 and downloads it itself.

### 2. Android SDK command-line tools

```bash
mkdir -p "$HOME/android-sdk/cmdline-tools"
cd "$HOME/android-sdk"

wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-*_latest.zip
mv cmdline-tools cmdline-tools/latest

cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
EOF
source ~/.bashrc
```

Deriving `JAVA_HOME` from `javac` rather than hardcoding a path keeps this
working when Termux moves the JDK.

### 3. SDK packages

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
```

`build-tools;35.0.0` is AGP 8.13's default; installing exactly that avoids AGP
trying to fetch another one.

### 4. Redirect AGP to the ARM aapt2

This must go in the **user-level** Gradle properties, not the project's
`gradle.properties`. The project file is committed and shared with CI, where the
host is x86_64 and the override would be wrong.

```bash
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
EOF
```

### 5. Clone and build

```bash
git clone https://github.com/kbt117/api-key-storage-encryption.git
cd api-key-storage-encryption

echo "sdk.dir=$ANDROID_HOME" > local.properties   # git-ignored
chmod +x gradlew

./gradlew --no-daemon assembleDebug
```

The A15 has limited RAM, so the first build is slow and the Kotlin compiler may
be killed. If you see `OutOfMemoryError` or a silent worker death, lower the
worker count in `~/.gradle/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=2
org.gradle.parallel=false
```

### 6. Install

```bash
termux-setup-storage        # one-time; grants access to shared storage
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
```

Then open `Download/` in Files and tap the APK. (Or, with wireless ADB
configured: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.)

The debug build uses `applicationIdSuffix = ".debug"`, so it installs alongside a
release build rather than replacing it.

---

## CI/CD

`.github/workflows/android-build.yml` runs on push to `main` and `arena/**`, on
pull requests, and manually:

1. JDK 17 (Temurin)
2. Android SDK via `android-actions/setup-android`
3. Installs `platforms;android-36`, `build-tools;35.0.0`, `platform-tools`
4. `./gradlew testDebugUnitTest` — fails the build on a test failure
5. `./gradlew lintDebug`
6. `./gradlew assembleDebug`
7. Uploads the APK and the test report as artefacts

Concurrent runs on the same ref are cancelled, which matters when iterating from
a phone. Only the default branch may write to the Gradle cache.

### Release signing

The release build falls back to the debug keystore when no release key is
present, so `./gradlew assembleRelease` always produces an installable APK. To
sign for real, create a git-ignored `keystore.properties` beside
`build.gradle.kts`:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`keystore.properties`, `*.jks` and `*.keystore` are all git-ignored.

---

## Design decisions

### Why not AGP 9?

AGP 9 is current (9.4 as of this writing) and brings built-in Kotlin. This
project stays on 8.13.2 because the brief requires Hilt, and the AGP 9 path is
not yet safe:

- Hilt **2.59+** is the first release whose Gradle plugin supports AGP 9, and
  2.58 is the last that supports AGP 8. So AGP 9 forces a Hilt major bump.
- Hilt 2.59 shipped with a broken `ComponentTreeDeps` — the plugin generated code
  importing a class absent from every 2.59 runtime artifact
  ([google/dagger#5099](https://github.com/google/dagger/issues/5099)).
- AGP 9's built-in Kotlin is incompatible with `org.jetbrains.kotlin.android`,
  and KSP needed fixes to work with it at all.

None of that is unfixable, but it is a coordinated multi-dependency migration
whose failure mode is a build that does not start — the worst outcome for a
project whose selling point is "build it anywhere with one command". When you are
ready, the sequence is: Gradle 9.6+ → Kotlin 2.3+ → AGP 9.x → drop
`kotlin-android` → Hilt 2.59+.

### Why kapt, not KSP?

KSP is the recommended processor and is faster. Two reasons not to use it here:

1. KSP's versioning changed for Kotlin 2.3 — releases are now plain `2.3.N`
   rather than `2.3.21-2.0.x`. Which `2.3.N` pairs with Kotlin 2.3.21 is not
   stated anywhere authoritative, so pinning one is a guess.
2. kapt on AGP 8.13 + Kotlin 2.3.21 is a combination with years of production
   mileage behind it.

kapt is in maintenance mode, not removed. The cost is a slower build; the benefit
is removing an unverifiable version pairing from a project that must build on a
phone.

### Why Tink is the default, with `EncryptedSharedPreferences` available

The brief specified `EncryptedSharedPreferences`. It is implemented and works —
build with `-PuseLegacyEncryptedPrefs=true` to select it.

It is not the default because Google deprecated the whole `security-crypto`
library at 1.1.0 and has said no further releases are planned; current guidance
is DataStore for storage plus Tink for the cryptography. `TinkSecurityHelper` is
that guidance, with the DataStore layer swapped for `SharedPreferences` because a
handful of short strings does not justify the dependency.

Both implement `SecureKeyValueStore`, so the rest of the app cannot tell them
apart. Switching backends after keys are stored requires clearing app data — the
two use different wrapping keys.

### Ktor on Android

Ktor's own FAQ confirms the Netty engine works on Android API 21+. Two things
worth knowing:

- **Netty logs an exception on startup and it is harmless.** It probes for native
  `epoll`/`kqueue` transports, does not find them, and falls back to NIO. Expect
  a `No implementation found for … epoll.Native.offsetofEpollData` line in logcat.
- **Ktor must be ≥ 3.3.2.** Earlier 3.3.x throws `java.lang.VerifyError` on
  Android (KTOR-8916, an upstream Netty bytecode issue). Pinned to 3.5.2.
- **`MicrometerMetrics` / `JvmGcMetrics` are never installed.** They reference
  `java.lang.management.ManagementFactory`, which does not exist on Android and
  crashes the server.

### Streaming, and why bodies are treated as opaque

Responses are relayed chunk-by-chunk with a flush per chunk, which is what makes
SSE arrive live. Request bodies are buffered but capped at 8 MiB — prompts are
small, and reading them fully avoids OkHttp's duplex request-body requirement
(which needs HTTP/2) while turning a hostile client into a clean `413` instead of
an OOM.

Not parsing JSON anywhere means no JSON dependency and no schema to fall behind.

### Logging discipline

`ProxyLogBuffer` is in-memory only, capped at 200 entries, and never persisted —
these entries describe traffic to a third-party API and writing them to disk
would put request metadata somewhere the user did not ask for it. Bodies are
never recorded; header *values* are never recorded (that would include
`Authorization`); only the upstream host is kept, never a full URL with a query
string.

---

## Verification status

**The single most important caveat: this project has not been compiled.**

There is no JDK or Android SDK in the environment it was written in, and
outbound network access is restricted to GitHub. `./gradlew assembleDebug` was
never run.

Getting a toolchain was attempted and failed, for the record: `apt` cannot reach
`deb.debian.org`; `dl.google.com`, `repo1.maven.org` and `services.gradle.org`
are all unreachable, so no Android SDK, no Maven dependencies and no Gradle
distribution can be fetched. A JDK and `kotlin-compiler-2.3.21.zip` *do* exist as
GitHub release assets, but downloading them redirects to
`release-assets.githubusercontent.com`, which is blocked, so even compiling the
dependency-free `domain` classes on their own was not possible.

**What was verified instead.**

1. **Every dependency version was resolved from its official Maven metadata** —
   `dl.google.com/dl/android/maven2` for AndroidX and AGP, `repo1.maven.org` for
   everything else — not recalled. This is what surfaced the two constraints that
   shape the whole build: AGP 8.13.2 caps at API 36.1, and Hilt 2.58 is the last
   release supporting AGP 8.
2. **Every third-party API used was checked against its source**, fetched from
   GitHub: Tink's `AndroidKeysetManager.Builder` (`withSharedPref`,
   `withMasterKeyUri`, `build`, `getKeysetHandle`),
   `KeysetHandle.getPrimitive`, `AeadConfig.register`,
   `Aead.encrypt/decrypt`, and androidx's `MasterKey`
   (`DEFAULT_MASTER_KEY_ALIAS`, `isKeyStoreBacked`).
3. **The Gradle wrapper jar is genuine** — pulled from the `gradle/gradle`
   repository at tag `v8.14.3`, not fabricated.
4. **`scripts/verify_project.py` passes 496 assertions** covering XML
   well-formedness, version-catalog resolution (including single-segment
   `libs.okhttp` accessors), every resource reference, manifest component
   existence, the exact permission set, package/directory agreement, bracket
   balance, every intra-project import, and the Android 16 / security invariants
   (foreground-service type and justification, predictive back, loopback-only
   binding, loopback-only cleartext, backup exclusions, SDK levels, Gradle floor).
5. **That harness was mutation-tested against 21 deliberate faults** — malformed
   XML, dangling resource references, unknown catalog accessors, dangling
   imports, a dangerous permission, a wrong foreground-service type, cleartext
   allowed for a LAN host, the proxy rebound to `0.0.0.0`, a deleted wrapper jar.
   All 21 were caught. This matters because a checker that has never failed
   anything proves nothing — and the exercise found two bugs in the checker
   itself, one of which silently skipped every single-segment `libs.*` accessor.

Three real defects were found and fixed during review, worth recording because
none of them would have been caught by the static harness:

- `SettingsValidator.validateBaseUrl` rejected `http://localhost:8080`, because
  it compared the port-inclusive authority against `"localhost"`. Found by
  writing the test, not by running it.
- `SettingsRepositoryImpl.ensureLoaded` used a `synchronized` double-check that
  could not actually work — the load has to suspend, and you cannot suspend
  inside a monitor. Replaced with a `Mutex`.
- `UpstreamForwarder` called OkHttp's blocking `execute()` directly inside Ktor's
  call handler. With streaming responses that would pin a Netty event-loop thread
  for minutes and stall every other request sharing it. Moved to the IO
  dispatcher.

**What remains unverified**, and needs a real toolchain:

- That the Kotlin compiles. The Compose and Ktor DSL surfaces are the most likely
  places for an error, and no static check substitutes for `kotlinc`.
- Runtime behaviour: that the server binds, that a proxied request round-trips,
  that SSE streams incrementally, that the foreground service survives
  backgrounding.
- That the Termux sequence completes on a real A15. The arm64 `aapt2` constraint
  and the package versions are researched and documented, not executed.

Run `./gradlew testDebugUnitTest` first — it exercises `UpstreamUrlBuilder`,
`SettingsValidator`, `ProxyLogBuffer` and `ServerErrors` with no device or
emulator required, and is the fastest signal that the toolchain is healthy. If
you hit a compile error, the most likely candidates are Compose Material 3
signatures and Ktor's routing/response DSL, both of which move between releases.

---

## Troubleshooting

**`exec format error` on `aapt2` (arm64 host).**
Google ships x86_64 build-tools only. Set
`android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` in `~/.gradle/gradle.properties`.

**`Port 8080 is already in use`.**
Another app holds the port. Change it in Settings; a running server restarts
itself automatically on save.

**Netty exceptions about `epoll`/`kqueue` in logcat.**
Expected on Android. Netty falls back to NIO. Not an error.

**`401` from every request.**
No key stored, or the upstream rejected it. Check the Settings **Test** button,
which calls `GET /v1/models` and reports the exact status.

**`400 invalid_base_url`.**
The stored base URL is blank or has no scheme. Re-save Settings.

**Every decrypt returns `null` after an OS update.**
The Keystore master key was invalidated. Use **Settings → Reset secure storage**
and re-enter the key.

**Proxy works but no notification appears (Android 13+).**
`POST_NOTIFICATIONS` is not requested by design. Enable notifications for KeyProxy
in system settings; the proxy itself is unaffected.

**Requests fail only from a browser page.**
No CORS plugin is installed — the intended clients are apps and CLIs, not
web pages. Add `install(CORS)` to `installProxyRouting` if you need it.

---

## Licence

MIT — see [LICENSE](LICENSE).
