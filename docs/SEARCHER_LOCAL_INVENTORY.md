# Searcher Local Inventory

Local Raspberry Pi search cluster inventory for distributed shard testing.

Last verified: 2026-02-17

## Cluster Intent

- Initial deployment target: 8 shards on 8 nodes (one shard per node)
- Network target: 2.5 GbE on `eth1`
- Current cert/DNS state: hostnames resolve under `*.rokkon.com`

## Node Inventory

| Host | Model | RAM (GiB) | CPU (vCPU) | Root mount | Extra mount | Primary fast storage | Link speed |
|---|---|---:|---:|---|---|---|---|
| `pi5v1` | Raspberry Pi 5 Model B Rev 1.1 | 15.84 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `CT2000P310SSD8` (1.8T) | 2500 Mb/s |
| `pi5v2` | Raspberry Pi 5 Model B Rev 1.0 | 7.87 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `Samsung SSD 980 PRO 1TB` (931.5G) | 2500 Mb/s |
| `pi5v3` | Raspberry Pi 5 Model B Rev 1.0 | 7.87 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `Samsung SSD 990 PRO 2TB` (1.8T) | 2500 Mb/s |
| `pi5v4` | (Phasing Out) | 3.95 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `Patriot M.2 P320 256GB` (238.5G) | 2500 Mb/s |
| `pi5v5` | Raspberry Pi 5 Model B Rev 1.0 | 7.87 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `Samsung SSD 960 PRO 2TB` (1.9T) | 2500 Mb/s |
| `pi500p` | Raspberry Pi 500 Rev 1.0 | 15.83 | 4 | `/dev/nvme0n1p2 /` | - | NVMe `SAMSUNG MZ9LQ256HBJD-00BVL` (238.5G) | 2500 Mb/s |
| `cm5v1` | Raspberry Pi Compute Module 5 Rev 1.0 | 15.84 | 4 | `/dev/mmcblk0p2 /` | `/dev/nvme0n1 /work` | NVMe `CT1000P3SSD8` (931.5G) on `/work` | 2500 Mb/s |
| `pi5ai1` | Raspberry Pi 5 Model B Rev 1.1 | 15.84 | 4 | `/dev/sda2 /` | - | USB SSD `SABRENT` (953.9G) | 2500 Mb/s |
| `cm5v2` | CM5 on Waveshare Nano Board | 15.84 | 4 | `/dev/mmcblk0p2 /` | `/dev/sda /work` | NVMe via RTL9210 (USB3 Shared) | 2500 Mb/s (Shared) |
| `cm5v3` | Raspberry Pi Compute Module 5 Lite Rev 1.0 | 7.87 | 4 | `/dev/mmcblk0p2 /` | - | NVMe via USB-C (Upgraded) | 2500 Mb/s (Target) |
| `cm5ai1` | Raspberry Pi Compute Module 5 Rev 1.0 | 15.84 | 4 | `/dev/mmcblk0p2 /` | - | eMMC only (`mmcblk0` 58.2G) | 1000 Mb/s on `eth0` |
| `krick-1` | AMD Ryzen 9 9950X host + NVIDIA RTX 2070 | 62.40 | 32 | `/dev/md0 /` | - | 4x NVMe (~2 TB each) | 10000 Mb/s on `enp17s0` |
| `krick` | AMD Ryzen 9 9950X3D host + NVIDIA RTX 4080 SUPER | 123.45 | 32 | `/dev/nvme3n1p3 /` | `/dev/md0 /work` | 5x NVMe (one 4 TB + four ~2 TB) | 5000 Mb/s on `enp12s0` |
| `nas` | Intel i5-1235U NAS host | 62.55 | 12 | `/dev/md9 /` | - | 4x 12 TB SATA + 2x 1 TB WD Red SN700 | 10000 Mb/s on `eth0` |

## Capacity Notes

- **Minimum 8GB Baseline**: The 4GB `pi5v4` is being phased out/upgraded to an 8GB node. The cluster now targets a minimum of 8GB RAM per node to support high-K search buffers.
- **CM5v2 USB Contention**: `cm5v2` shares a single USB 3.0 root hub (Bus 003) for both its RTL9210 NVMe storage and its RTL8156 2.5GbE network adapter. Total bus bandwidth is capped at 5Gbps.
- **Upgrades**: `cm5v2` and `cm5v3` have transitioned to NVMe-backed storage, eliminating the eMMC/SD capacity bottleneck.
- `pi5ai1` currently boots from USB SSD, not NVMe. Keep this in mind when comparing per-node IO variance.
- `cm5v1` root is on eMMC, but `/work` is NVMe and is the preferred index/data path.
- `krick-1` and `krick` are both strong candidates for coordinator/query-driver, embedding service placement, and high-load shard-host experiments.
- `nas` has strong network/storage capacity and is ideal for shared dataset hosting and staging.

## Proposed 8-Shard Placement (Initial)

| Shard | Host | Rationale |
|---:|---|---|
| 0 | `pi5v1` | High-memory NVMe seed/coordinator candidate |
| 1 | `pi5v2` | NVMe |
| 2 | `pi5v3` | NVMe |
| 3 | `cm5v2` | 16GB RAM + NVMe USB3 (Replaces pi5v4). Watch for USB bus contention. |
| 4 | `pi5v5` | NVMe |
| 5 | `pi500p` | NVMe, high-memory |
| 6 | `cm5v1` | Use NVMe-backed `/work` for index |
| 7 | `pi5ai1` | USB SSD outlier; useful for mixed-device realism |

## Suggested Follow-up

- If `cm5v2` shows latency spikes, move the NVMe to the PCIe header using an adapter to bypass the USB hub contention.
- Keep this file updated when hosts, storage paths, or shard assignments change.
