# OpenNetSim 🌐📱

> **Project Status:** 🚧 **Work in Progress (WIP)**  
> This project is currently under active development and testing.

**OpenNetSim** is an Android application designed to simulate and emulate adverse or degraded network conditions (such as slow 3G, Edge, flaky Wi-Fi, high latency, or packet loss) in a granular manner without requiring root privileges (`root`).

---

## 🎯 Goals and Objectives

The main goal of OpenNetSim is to provide developers, QA engineers, and network enthusiasts with a lightweight, reliable on-device tool to evaluate mobile application resilience and behavior under realistic, hostile connectivity scenarios.

### Key Features:
- **Non-Root (`VpnService`):** Intercepts network traffic transparently using Android's official `VpnService` API and a userspace TUN interface.
- **Asymmetric Bidirectional Throttling:** Independent bandwidth rate limiting for uplink and downlink traffic using the **Token Bucket** algorithm.
- **Latency, Jitter & Reordering:** Simulates round-trip time (RTT) delays and their variation without reordering packets, plus an optional reordering percentage (like `netem reorder`).
- **Packet Loss Simulation:** Separate upload and download loss rates, either independent or in bursts (Gilbert model).
- **Userspace L3/L4 Network Stack:** Pure Kotlin network forwarders for TCP (with flow control), UDP (DNS and streaming), and ICMP echo (`ping` is forwarded to the real network with unprivileged ping sockets, so its RTT is the real one plus the configured degradation and unreachable hosts do not answer), featuring **IPv4 & IPv6 Dual-Stack** compatibility.
- **Material 3 UI (light & dark):** Built with **Jetpack Compose** in the official OpenNetSim palette, with an animated splash screen, a first-run welcome that requests the VPN permission, and three sections:
  - **Simulate:** one-tap tunnel switch with active time, condition presets (3G, Flaky Wi-Fi, Satellite, etc.) and your own **saved profiles** (save, delete, undo).
  - **Parameters:** exact values typed in text fields (bandwidth in kbps or Mbps), with validation that never sends an invalid value to the tunnel and a one-tap reset to the base profile.
  - **Telemetry:** live 60-second throughput chart against the configured limit, transferred/dropped/retransmitted counters and tunnel status.

---

## 🏗️ Project Architecture

OpenNetSim follows **Clean Architecture** principles to ensure decoupling, high performance, and unit testability:

```text
OpenNetSim/
├── core/                    # Gradle module :core: pure Kotlin/JVM, Android imports do not compile
│   └── com/opennetsim/
│       ├── core/
│       │   ├── engine/      # TokenBucketLimiter, LatencyQueue, JitterProcess, PacketLossSimulator
│       │   ├── packet/      # Parsers, serializers and checksums (IPv4, IPv6, TCP, UDP, ICMP)
│       │   ├── tcp/         # Userspace TCP state machine (TcpConnection, RTO, reassembly)
│       │   ├── time/        # MonotonicClock shared by the engine, forwarders and service
│       │   └── buffer/      # Contention-free ByteBufferPool
│       └── model/           # Immutable configuration, presets and telemetry models
└── app/                     # Gradle module :app: Android application
    └── com/opennetsim/
        ├── tunnel/          # TunnelController and ConfigStore: the only contract the UI sees
        ├── profiles/        # User-saved connection profiles and their persistence
        ├── service/         # VpnService lifecycle, notifications, intents and config persistence
        │   ├── pipeline/    # TunnelPipeline: TUN loops + engine + forwarders (JVM-testable)
        │   └── forwarder/   # Userspace TCP/UDP forwarders and real ICMP (ping) forwarder
        └── ui/              # ViewModel and JVM-tested UI logic (fields, profiles, chart)
            ├── screens/     # Compose screens: welcome, simulate, parameters, telemetry
            ├── components/  # Shared Compose components
            └── theme/       # Official palette (light/dark) and typography
```

---

## 🛠️ Requirements & Build

- **Android SDK:** Min SDK 26 (Android 8.0) / Target SDK 34 (Android 14)
- **Language:** Kotlin 2.4 (built-in Kotlin of Android Gradle Plugin 9)
- **Build System:** Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`)

### Verification Commands

```bash
# Compile Kotlin code (pure :core module and Android :app)
./gradlew :core:compileKotlin :app:compileDebugKotlin

# Static analysis (detekt, including ktlint formatting rules) and Android lint
./gradlew detekt lintDebug

# Run unit test suites (JVM without emulator)
./gradlew :core:test :app:testDebugUnitTest

# Coverage with Kover: minimum 85% line coverage; HTML report in app/build/reports/kover/htmlCoverage
./gradlew :core:koverVerifyCoverage :app:koverVerifyCoverage :app:koverHtmlReportCoverage

# Microbenchmarks (JMH) of the per-packet path in :core; see openspec design for reference numbers
./gradlew :benchmark:smokeBenchmark       # quick check that benchmarks run
./gradlew :benchmark:allocationBenchmark  # time (ns/op) and allocations (B/op)

# Build debug APK
./gradlew assembleDebug

# Build release APK (R8 + resource shrinking; unsigned unless signing is configured)
./gradlew :app:assembleRelease
```

### Release Signing
The release keystore and its passwords never go in the repository. Add these Gradle properties to
`~/.gradle/gradle.properties` (or pass them as `ORG_GRADLE_PROJECT_<name>` environment variables in CI):

```properties
openNetSimReleaseStoreFile=/absolute/path/to/opennetsim-release.jks
openNetSimReleaseStorePassword=...
openNetSimReleaseKeyAlias=opennetsim
openNetSimReleaseKeyPassword=...
```

Without them, `assembleRelease` produces `app-release-unsigned.apk`. Keep a backup of the keystore: an
installed app can only be updated with an APK signed by the same key.

---

## ☕ Support

OpenNetSim is free, open source and ad-free. If you find it useful, you can support its development on
[Ko-fi](https://ko-fi.com/joseponce).

## 📄 License

This project is licensed under the Apache License 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.

OpenNetSim is provided **"as is", without warranty of any kind**, express or implied, and the authors are
not liable for any damage arising from its use (sections 7 and 8 of the license). It changes how your
device's traffic flows while the simulation is active: use it for testing, not as a security or privacy
tool.
