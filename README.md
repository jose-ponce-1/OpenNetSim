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
- **Latency & Jitter Injection:** Simulates round-trip time (RTT) delays and temporal variations through scheduled delay queues.
- **Packet Loss Simulation:** Probabilistic packet drop emulation at the IP level.
- **Userspace L3/L4 Network Stack:** Pure Kotlin network forwarders for TCP (with flow control), UDP (DNS and streaming), and ICMP echo (`ping` is answered locally inside the tunnel with the configured latency and loss, so it does not measure the real network or detect unreachable hosts), featuring **IPv4 & IPv6 Dual-Stack** compatibility.
- **Intuitive UI & Live Telemetry:** Built with **Jetpack Compose** and Material 3, offering quick condition presets (3G, Flaky Wi-Fi, Satellite, etc.), manual sliders, and real-time throughput and drop metrics.

---

## 🏗️ Project Architecture

OpenNetSim follows **Clean Architecture** principles to ensure decoupling, high performance, and unit testability:

```text
OpenNetSim/
├── core/
│   ├── engine/    # Pure mathematical logic: TokenBucketLimiter, LatencyQueue, PacketLossSimulator
│   ├── packet/    # Parsers, serializers, and checksum computation (IPv4, IPv6, TCP, UDP, ICMP)
│   ├── tcp/       # Pure userspace TCP state machine (TcpConnection, RTO, reassembly)
│   ├── time/      # MonotonicClock shared by the engine, forwarders and service
│   └── buffer/    # Efficient memory management & recycling (contention-free ByteBufferPool)
├── tunnel/        # TunnelController and ConfigStore: the only contract the UI sees
├── service/       # VpnService lifecycle, notifications, intents and config persistence
│   ├── pipeline/  # TunnelPipeline: TUN loops + engine + forwarders (JVM-testable, no Android)
│   └── forwarder/ # Userspace TCP/UDP forwarders and local ICMP responder
├── model/         # Immutable data models for configuration, presets, and telemetry
└── ui/            # Reactive Jetpack Compose screens, ViewModels, and Material 3 design
```

---

## 🛠️ Requirements & Build

- **Android SDK:** Min SDK 26 (Android 8.0) / Target SDK 34 (Android 14)
- **Language:** Kotlin 2.0+
- **Build System:** Gradle (Kotlin DSL)

### Verification Commands

```bash
# Compile Kotlin code
./gradlew compileDebugKotlin

# Static analysis (detekt, including ktlint formatting rules) and Android lint
./gradlew detekt lintDebug

# Run unit test suite (JVM without emulator)
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug
```

---

## 📄 License

This project is licensed under the Apache License 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.
