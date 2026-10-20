# Proposal

## Why

In NetThrottle, simulating restricted uplink (upload) bandwidth currently suffers from critical failure modes that prevent reliable testing of slow or degraded network environments:
1. When uplink bandwidth is configured to low values (such as 20–48 kbps), the token bucket burst capacity drops below the TUN MTU (1500 bytes), causing packets to be permanently unable to acquire sufficient tokens, deadlocking the transmission pipeline.
2. The queueing engine uses a PriorityQueue ordered by scheduled release time without enforcing sequential spacing when tokens are exhausted. When latency is zero or packets are already pending, newly arriving packets are scheduled earlier than previously delayed packets, creating head-of-line priority inversions, out-of-order packet delivery, and queue livelocks.
3. In `TcpForwarder`, out-of-order TCP segments caused by queueing or jitter trigger immediate, non-contiguous sequence advances and subsequent segment drops, permanently corrupting TCP upload payloads.
4. The absence of bounded buffer management allows the TUN reading loop to queue unbounded packets in memory during uploads, causing massive bufferbloat (dozens of seconds of latency) and socket timeouts rather than providing backpressure.

Addressing these issues is essential to make uplink bandwidth throttling functional, accurate, and stable for real-world application testing.

## What Changes

- **Token Bucket MTU-Safe Burst Capacity**: Guarantee that `TokenBucketLimiter.burstCapacityBytes` is always at least equal to the maximum transmission unit (`TUN_MTU = 1500` bytes), preventing token starvation deadlocks at low bitrates.
- **Strict FIFO Packet Shaper & Scheduling**: Replace the defective timestamp scheduling in `ThrottlingEngine` with a FIFO-preserving queueing model where delayed packets are spaced according to token refill requirements, ensuring packets are released in exact arrival order.
- **Bounded Buffer & Congestion Backpressure**: Implement a configurable maximum queue limit (in packets/bytes) for the uplink queue; when full, excess packets are dropped (tail-drop), signaling congestion to the OS network stack and eliminating runaway memory consumption and bufferbloat.
- **TCP Stream Reassembly in TcpForwarder**: Add out-of-order segment buffering and contiguous acknowledgement handling in `TcpForwarder` to ensure that TCP payloads sent by the client are reassembled in correct byte order before forwarding to the external socket, preventing data truncation.
- **Precise Uplink Event Pacing**: Synchronize the `uplinkDrainJob` in `ThrottleVpnService` with the calculated token availability delay, avoiding spin-waits and ensuring smooth, throttled packet emission.

## Capabilities

### New Capabilities
- `uplink-throttling`: Defines system requirements for shaping and degrading outbound (device-to-network) traffic, encompassing token bucket rate enforcement, MTU-safe burst floors, bounded queue tail-drop backpressure, strict FIFO preservation, and lossless TCP stream forwarding.

### Modified Capabilities
<!-- None: Initial capability definition -->

## Impact

- **Core Engine (`com.netthrottle.core.engine`)**:
  - `TokenBucketLimiter`: Enforces MTU lower bound on burst capacity.
  - `LatencyQueue` / `ThrottlingEngine`: Implements bounded capacity, tail-drop metrics, and FIFO preservation when pacing packets.
- **Service Layer (`com.netthrottle.service`)**:
  - `ThrottleVpnService`: Updates uplink drain pacing and telemetry reporting.
  - `TcpForwarder`: Adds out-of-order TCP segment reassembly buffering.
- **Testing (`app/src/test`)**:
  - Unit tests for MTU safety at low kbps, FIFO ordering without latency, queue overflow tail-drop, and out-of-order TCP packet uploads.
