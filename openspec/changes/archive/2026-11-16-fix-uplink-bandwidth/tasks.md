# Tasks

## 1. Core Engine Hardening & MTU Burst Floor

- [x] 1.1 Update `TokenBucketLimiter` to enforce a minimum burst capacity floor of at least `1500` bytes (`TUN_MTU`) and verify with unit tests in `TokenBucketLimiterTest` ensuring low bitrates (e.g. 20 kbps) can consume 1500-byte packets.
- [x] 1.2 Implement monotonic virtual finish time pacing in `ThrottlingEngine` to guarantee strict FIFO scheduling when packets are enqueued for token refill, and verify via unit tests in `ThrottlingEngineTest` that packet order is preserved when latency is zero.
- [x] 1.3 Add bounded queue capacity (`MAX_QUEUE_CAPACITY = 100` packets) and tail-drop backpressure to `LatencyQueue` and `ThrottlingEngine`, verifying in `ThrottlingEngineTest` that overflowing packets are dropped and increment `totalDroppedPackets`.

## 2. TCP Forwarder Stream Reassembly

- [x] 2.1 Add out-of-order segment reassembly buffering (`TreeMap<Long, ByteArray>`) to `TcpForwarder.Session` to buffer non-contiguous segments up to 64 KB, emitting cumulative ACKs for the current continuous sequence.
- [x] 2.2 Update `TcpForwarder.handleData` to drain contiguous buffered segments into `SocketChannel` upon arrival of missing segments, ensuring complete and uncorrupted upload byte streams.
- [x] 2.3 Write comprehensive unit tests for `TcpForwarder` out-of-order segment assembly, duplicate discard, and cumulative ACK generation.

## 3. Service Drain Pacing & Integration Verification

- [x] 3.1 Optimize `uplinkDrainJob` in `ThrottleVpnService` to synchronize sleep delays with `timeUntilNextUplinkEventMs()`, preventing CPU spin and ensuring responsive draining of ready packets.
- [x] 3.2 Update `BidirectionalThrottlingEngineTest` to validate combined uplink/downlink telemetry and asymmetric rate limiting with the new FIFO and burst floor guarantees.
- [x] 3.3 Execute the full test suite (`testDebugUnitTest`) to verify that all core engine and forwarder test cases pass cleanly without regressions.
