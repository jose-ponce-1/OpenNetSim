# Design

## Context

NetThrottle intercepts device network traffic using Android's `VpnService` with a TUN interface (`10.0.0.2` / `fd00::2`, MTU 1500). Outbound traffic read from the TUN descriptor is pushed through `BidirectionalThrottlingEngine.processUplink` before reaching `TcpForwarder`, `UdpForwarder`, and `IcmpResponder`.

See `proposal.md` for motivation regarding burst capacity deadlocks, queue reordering, unbounded bufferbloat, and TCP out-of-order corruption.

## Goals / Non-Goals

**Goals:**
- Eliminate token bucket deadlocks by establishing an MTU floor (`1500` bytes) on `burstCapacityBytes`.
- Ensure strict FIFO delivery and monotonic release timestamps in `ThrottlingEngine` for uplink traffic.
- Introduce tail-drop backpressure (maximum queue capacity) to prevent bufferbloat and trigger natural TCP congestion window scaling in the OS kernel.
- Implement an out-of-order segment reassembly buffer in `TcpForwarder` to prevent TCP payload corruption during packet reordering or jitter.
- Optimize coroutine pacing in `ThrottleVpnService.uplinkDrainJob`.

**Non-Goals:**
- Replacing the lightweight forwarder architecture with a full user-space TCP stack (e.g. gVisor / lwIP).
- Changing UI controls or presets (current presets and sliders already encompass appropriate uplink values).
- Altering downlink processing except where shared core engine abstractions (`TokenBucketLimiter`, `ThrottlingEngine`) benefit both directions.

## Decisions

### Decision 1: MTU-Safe Burst Capacity Floor in TokenBucketLimiter
- **Design**: In `TokenBucketLimiter`, enforce:
  ```kotlin
  val effectiveBurst = max(MIN_BURST_BYTES, burstCapacityBytes)
  ```
  where `MIN_BURST_BYTES = 1500L` (standard TUN MTU).
- **Rationale**: If `upKbps` is set to 20 kbps, `bytesPerSecond = 2500`. Without an MTU floor, lower rates or small burst capacities prevent full 1500-byte packets from ever matching `availableTokens >= packet.size`, permanently freezing the pipeline.
- **Alternatives Considered**: Dropping packets larger than burst capacity was rejected because standard OS network stacks emit full MTU frames by default.

### Decision 2: Monotonic Virtual Finish Time Pacing in ThrottlingEngine
- **Design**: When enqueuing packets into `LatencyQueue` or pacing them for bandwidth:
  - Track `lastScheduledReleaseTimeMs`.
  - When enqueuing a packet that must wait for bandwidth tokens:
    ```kotlin
    val serializationDelayMs = max(1L, bandwidthLimiter.calculateWaitTimeMs(packet.size))
    val scheduledTime = max(currentTimeMs + latencyWithJitter, lastScheduledReleaseTimeMs + serializationDelayMs)
    lastScheduledReleaseTimeMs = scheduledTime
    ```
- **Rationale**: Prevents subsequent packets from being assigned timestamps in the past relative to earlier queued packets, eliminating PriorityQueue head-of-line inversions and livelocks.
- **Alternatives Considered**: Maintaining separate FIFO queues for rate limiting and latency. Using monotonic finish times achieves the same goal while preserving the existing, tested `PriorityQueue` architecture.

### Decision 3: Bounded Uplink Queue Depth with Tail Drop
- **Design**: Introduce a maximum queue size in `LatencyQueue` / `ThrottlingEngine` (default `MAX_QUEUE_CAPACITY = 100` packets):
  - When `latencyQueue.size >= MAX_QUEUE_CAPACITY`, `processIncoming` returns `PacketResult.Dropped` and increments `totalDroppedPackets`.
- **Rationale**: An upload of several megabytes will quickly generate thousands of packets. Without a bounded queue, memory grows uncontrollably and artificial queue delay stretches to dozens of seconds. Tail drop provides immediate feedback to the kernel's TCP stack to throttle back `cwnd`.
- **Alternatives Considered**: Random Early Detection (RED). Tail drop is substantially simpler, standard for token bucket filters (e.g., Linux `tc tbf limit`), and avoids probabilistic tuning complexities.

### Decision 4: Out-of-Order TCP Segment Reassembly in TcpForwarder
- **Design**: In `TcpForwarder.Session`, add:
  ```kotlin
  val outOfOrderBuffer = java.util.TreeMap<Long, ByteArray>()
  ```
  - When receiving a TCP packet with sequence `seq` and `payload`:
    1. If `seq == clientExpectedSeq`:
       - Write payload to `channel`.
       - Advance `clientExpectedSeq += payload.size`.
       - Check `outOfOrderBuffer` iteratively to drain contiguous buffered segments.
       - Send cumulative ACK for updated `clientExpectedSeq`.
    2. If `seq > clientExpectedSeq`:
       - Buffer the segment in `outOfOrderBuffer` (subject to total buffer size limit of 64 KB).
       - Send ACK for the unchanged `clientExpectedSeq` (triggering TCP sender retransmission or SACK behavior).
    3. If `seq + payload.size <= clientExpectedSeq`:
       - Duplicate segment; ignore payload and send ACK for `clientExpectedSeq`.
- **Rationale**: Completely eliminates byte stream truncation and corruption when packets arrive out of order due to queue pacing or network jitter.
- **Alternatives Considered**: Dropping out-of-order segments immediately. Buffering is superior because minor jitter does not necessitate expensive retransmissions from the client.

## Risks / Trade-offs

- **[Risk] High memory usage from buffered TCP segments**:
  - *Mitigation*: Limit `outOfOrderBuffer` size to 64 KB per session. If buffer limit is reached, incoming out-of-order segments are dropped, letting standard TCP fast retransmit recover the gap.
- **[Risk] Low bitrates causing aggressive tail drop if burst limit is too small**:
  - *Mitigation*: Default queue limit of 100 packets accommodates standard TCP initial congestion window bursts (typically 10 to 32 segments) without premature drops.
