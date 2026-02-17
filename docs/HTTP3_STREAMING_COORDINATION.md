# Research: HTTP/3 (QUIC) Streaming Coordination

## The Concept
Current coordination uses gRPC over HTTP/2 (TCP). While efficient, TCP suffers from **Head-of-Line Blocking** and handshake latency, especially on consumer-grade networks or WiFi-connected Raspberry Pis.

**HTTP/3 (QUIC)** runs over UDP. It offers:
1.  **True Multiplexing**: Packet loss on one stream does not block others.
2.  **Zero-RTT Handshakes**: Faster connection establishment for ephemeral search contexts.
3.  **Congestion Control**: Better handling of bursty traffic (like 8 shards returning results simultaneously).

## Why It Matters for "Streaming K"
In a "Streaming K" or "Collaborative Bar" scenario, the coordinator is blasting tiny updates ("New High Score: 0.95") to all shards.
*   **TCP**: A single dropped packet stalls the entire window, delaying the "Stop Search" signal. A shard might waste 10ms (thousands of vector comparisons) waiting for the retransmission.
*   **QUIC**: The update arrives independently. If one packet drops, the next update (e.g., "New High Score: 0.96") arrives immediately, rendering the lost packet obsolete anyway.

## Implementation Viability
*   **Vert.x / Netty**: Have robust HTTP/3 support (experimental but functional).
*   **Quarkus**: Integration is pending/experimental, but we can potentially use the underlying Netty transport directly or a standalone Vert.x sidecar.

## The Protocol Shift
Instead of `gRPC -> HTTP/2`, we propose a custom **UDP-based Coordination Protocol**:
*   **Reliable Stream**: For control signals (Start, Stop, Error).
*   **Unreliable Datagrams**: For "Score Updates" and "Candidate Hints." We don't care if we miss a "0.95" update if a "0.96" update follows 1ms later.

## Impact
*   **Latency Jitter**: flattened.
*   **Resilience**: The cluster tolerates packet loss without stalling the search.
*   **"Real-Time" Feel**: The search results stream to the user with the fluidity of a video game updates.
