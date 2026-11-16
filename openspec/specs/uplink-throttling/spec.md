# uplink-throttling Specification

## Purpose
Enforces outbound network degradation rules for traffic leaving the local TUN interface, providing reliable rate limiting, bounded queue backpressure, strict FIFO ordering, and lossless TCP stream delivery.

## Requirements

### Requirement: MTU-Safe Uplink Burst Capacity
The traffic shaping engine SHALL maintain a token bucket burst capacity of at least the TUN MTU (1500 bytes) regardless of configured uplink bitrate, ensuring standard MTU packets never deadlock due to token starvation.

#### Scenario: Full MTU packet transmission at minimal bitrate
- **WHEN** an outbound packet of 1500 bytes is processed with an uplink limit set to 20 kbps
- **THEN** the token bucket burst floor permits token accumulation up to at least 1500 bytes and the packet is released once sufficient tokens are refilled

### Requirement: Contiguous Outbound TCP Stream Assembly
The userspace TCP forwarder SHALL buffer out-of-order TCP data segments received from the TUN interface and SHALL only forward contiguous payload bytes to the destination socket, acknowledging only the continuous acknowledged sequence number to the client.

#### Scenario: Handling out-of-order TCP segments
- **WHEN** an outbound TCP data segment arrives with a sequence number beyond the expected sequence
- **THEN** the segment is retained in a reassembly buffer, the client is sent an ACK for the current continuous expected sequence, and data is only forwarded to the remote socket once missing intermediate segments arrive

#### Scenario: Handling duplicate TCP retransmissions
- **WHEN** an outbound TCP data segment arrives covering an already acknowledged sequence range
- **THEN** the duplicate data is ignored without forwarding to the remote socket and an ACK with the current expected sequence is returned
