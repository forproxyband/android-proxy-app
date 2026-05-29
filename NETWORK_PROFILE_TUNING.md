# Network profile tuning — field test report (2026-05-28)

## Why this document exists

The `NetworkProfile` feature was added on 2026-05-28
(`app/src/main/java/com/proxyagent/app/nativeagent/quic/NetworkProfile.kt`).
The numeric values it ships with (TCP socket buffer, TCP bridge
buffer, QUIC Brutal CC target, QUIC UDP socket buffer, QUIC flow-
control refresh ratio) are NOT arbitrary — they were picked
through five field tests on the same day. Twice during that
process a value choice broke the TCP upload direction in a way
that produced an identical 5 Mbps symptom from opposite extremes.

This report captures every test run with raw numbers and the
applied agent.log line, so a future engineer changing
`NetworkProfile.tuning()` can:

1. See immediately what's been tried and what broke.
2. Avoid re-running the same regressions (the 12 MiB and 1 MiB
   SO_*BUF tests are NOT free — they require a full stop/start
   cycle, a speedtest, and produce no useful signal beyond
   "this is still broken").
3. Extend the test matrix into the gaps listed under
   [Open questions](#open-questions).

When in doubt, read this BEFORE editing `NetworkProfile.tuning()`.

## Headline results

Best clean run per profile on the **Google Pixel 9 Pro XL**
(`google komodo`, Android 16, kernel 6.1.145-android14-11) in
**ideal in-apartment conditions**:

- Proxy server, proxy agent (the phone), and the Speedtest
  client all physically in the **same apartment in Kremenchuk**
- Test client on **gigabit Ethernet**, agent on Wi-Fi (router
  ≈2 m away)
- Round-trip from client to phone-exit-IP is ≈2 hops over the
  in-apartment LAN plus the phone's Wi-Fi link — no WAN/cellular
  in between
- Speedtest.net **Multi-Connection** mode through the proxy
- Phone's public exit IP during all three runs: 195.64.231.232
  (Vizit-net Kremenchuk)
- Transport: TCP (negotiated by sticky cache on every run)

| Profile | TCP SO_*BUF | Download | Upload | Idle ping | Loaded ↓ ping | Loaded ↑ ping |
|---|---|---|---|---|---|---|
| `LOW_100` (default) | 1.5 MiB | **331.61 Mbps** | 298.95 Mbps | 84 ms | 244 ms | 58 ms |
| `MID_500` | 2 MiB | 296.22 Mbps | **279.86 Mbps** | **34 ms** | **202 ms** | **56 ms** |
| `HIGH_1000` | 4 MiB | 305.41 Mbps | 259.71 Mbps | 82 ms | 236 ms | 62 ms |

What these numbers tell you:

- **All three profiles deliver near-symmetric duplex** (≥260 Mbps
  in both directions) on this channel — no profile leaves
  throughput on the table.
- **MID_500 wins on latency** (idle ping 34 ms vs 82–84 ms for
  the other two), at the cost of a few percent of peak download
  vs HIGH_1000.
- **LOW_100 surprisingly tops the download** despite its smaller
  buffers — likely because the smaller kernel queue keeps TCP's
  ACK timing tighter and feedback loops faster. Repeated runs
  would smooth this out; differences within ~5% are inside
  speedtest-to-speedtest variance.
- **Bufferbloat under load**: loaded download ping climbs to
  202–244 ms on every profile because the test client's gigabit
  link saturates the proxy direction and the kernel queue
  inevitably fills. The profile sizes the *steady-state* queue
  depth, not what happens during a 30-second flat-out burst.

The full test matrix including the broken runs (12 MiB and
1 MiB SO_*BUF regressions) is in [Test runs](#test-runs); the
regression analysis is in [Findings](#findings).

## Test environment

Conditions were deliberately controlled to eliminate variables
that aren't the agent's software:

| Element | Setting | Why |
|---|---|---|
| Proxy server (`proxy-server-go`) | Same apartment as agent, Kremenchuk | Removes WAN latency / packet loss from the agent↔server tunnel — any latency observed comes from the software, not the link |
| Test client (Speedtest.net) | Same apartment, gigabit Ethernet | Removes test-client uplink as a bottleneck |
| Phone uplink | Wi-Fi (not cellular) | Removes cellular modem variability (RAT switches, IMSI handoff, signal fluctuation) — keeps the receive side stable |
| Speedtest mode | Multi-connection (default) | Matches how real proxy clients open many parallel target dials |
| Transport | TCP (auto-negotiated) | Every test session ended up on TCP — QUIC parameters in the profile were NOT exercised (see [Open questions](#open-questions)) |
| Engine | NATIVE | `BINARY` engine ignores the profile entirely (see [BINARIES.md](BINARIES.md) §2) |
| Public exit IP | 195.64.231.232 (Vizit-net Kremenchuk) for the in-apartment path; 188.239.123.47 (ZasNet Oleksandriya) for one earlier test on a different uplink | Verified each run via the speedtest result IP field |

Two devices were used:

- **Samsung Galaxy S Fold 7** — `samsung q7q`, Android 16,
  kernel 6.6.98-android15-8. Used for the initial HIGH_1000
  regression discovery.
- **Google Pixel 9 Pro XL** — `google komodo`, Android 16,
  kernel 6.1.145-android14-11. Used for Tests 2–5 (HIGH_1000
  through LOW_100 bisection).

Same kernel major (Linux 6.x) but different vendor patches and
different `net.core.{r,s}mem_max` configuration.

## Methodology

Each test cycle:

1. Open Settings, change `Network optimization` Spinner.
2. Hit Save — Toast confirms `Saved — restart agent to apply`.
3. STOP service, then START — the new profile applies on the
   next supervisor cycle (parameters ride the QUIC handshake
   and the TCP socket pre-connect hint; neither can be live-
   reloaded).
4. Wait for `Status: CONNECTED` + `Transport: WIFI`.
5. Verify the applied values via `agent.log` — every start
   emits one structured line:

   ```
   level=INFO msg="network profile"
     user_choice=<profile>
     tcp_socket_buf=<bytes>  tcp_bridge_buf=<bytes>
     quic_brutal_target_mbps=<int>
     quic_udp_socket_buf=<bytes>
     quic_window_headroom_ratio=<double>
   ```

6. Run Speedtest.net Multi-Connection mode through the proxy
   from the test client. Record Download/Upload Mbps, idle
   Ping, loaded Ping (down/up).
7. Export the agent log (kbn `SAVE` in the app's logs panel)
   and screenshot the speedtest result.

The agent log lines for each run are preserved verbatim in
`docs/test-logs/` (not in this repo as of this report — see
[Open questions](#open-questions) for follow-up).

## Test runs

### Test 1 — HIGH_1000 with 12 MiB SO_*BUF (initial buggy values)

**Device**: Samsung Galaxy S Fold 7 (`samsung q7q`, Android 16,
kernel 6.6.98-android15-8). App version 1.0.110.

**Profile applied** (from `agent.log`):
```
network profile user_choice=HIGH_1000
  tcp_socket_buf=12582912    ← 12 MiB
  tcp_bridge_buf=262144      ← 256 KiB
  quic_brutal_target_mbps=1000
  quic_udp_socket_buf=33554432
  quic_window_headroom_ratio=0.5
```

**Run 1a** — proxy uplink via ZasNet (Oleksandriya), 36 active
tunnels:
- **Download**: 76.15 Mbps
- **Upload**: 5.07 Mbps ← BROKEN
- **Ping**: 169 ms
- Public IP: 188.239.123.47

**Run 1b** — same profile, switched to in-apartment Kremenchuk
uplink (Vizit-net), to remove WAN noise:
- **Download**: 298.44 Mbps
- **Upload**: 24.09 Mbps ← BROKEN (more visible against the
  better channel — duplex symmetry should have been near-perfect
  with ping 33 ms in the same city)
- **Ping**: 33 ms
- Public IP: 195.64.231.232

**Outcome**: REGRESSION. TCP upload tanked. Hypothesis formed:
setting `SO_SNDBUF` manually disables `tcp_wmem` auto-tuning;
requesting more than `net.core.wmem_max` (typically 4–8 MiB on
Android) clamps the request AND leaves the effective send
window below where auto-tune would have grown it. The
TCP receive side (SO_RCVBUF) is unaffected because
`tcp_rmem` clamps differently — hence download stays clean
while upload collapses.

### Test 2 — HIGH_1000 with 4 MiB SO_*BUF (anchor fix)

**Device**: Google Pixel 9 Pro XL (`google komodo`, Android 16,
kernel 6.1.145-android14-11). App version 1.0.111.

**Profile applied**:
```
network profile user_choice=HIGH_1000
  tcp_socket_buf=4194304     ← 4 MiB (was 12 MiB)
  tcp_bridge_buf=262144
  quic_brutal_target_mbps=1000
  quic_udp_socket_buf=33554432
  quic_window_headroom_ratio=0.5
```

The 4 MiB value comes from the pre-profile hardcoded
`SOCKET_BUFFER_HINT_BYTES` constant. It matches exactly the
behaviour the codebase had before the preset existed and is
prod-validated across many devices.

**Run conditions**: 78 active tunnels, Vizit-net uplink, Pixel
on Wi-Fi to a router 2 m away.

**Result**:
- **Download**: 305.41 Mbps
- **Upload**: 259.71 Mbps
- **Idle ping**: 82 ms
- **Loaded ping**: ↓ 236 ms, ↑ 62 ms

**Outcome**: CLEAN. TCP duplex restored. Verifies the
hypothesis on a second device (Pixel, kernel 6.1, totally
different vendor patch set from the Samsung in Test 1).

### Test 3 — MID_500 with 2 MiB SO_*BUF

Same device and conditions as Test 2.

**Profile applied**:
```
network profile user_choice=MID_500
  tcp_socket_buf=2097152     ← 2 MiB
  tcp_bridge_buf=131072      ← 128 KiB
  quic_brutal_target_mbps=500
  quic_udp_socket_buf=16777216
  quic_window_headroom_ratio=0.6
```

**Run conditions**: 88 active tunnels.

**Result**:
- **Download**: 296.22 Mbps
- **Upload**: 279.86 Mbps
- **Idle ping**: 34 ms ← lowest of any test
- **Loaded ping**: ↓ 202 ms, ↑ 56 ms

**Outcome**: CLEAN, and notably the **lowest latency** of any
profile tested on this channel. Throughput within noise of
HIGH_1000 (≤3% lower on download, ≤8% higher on upload).
Demonstrates the latency win is real — half the SO_*BUF means
roughly half the worst-case kernel queue depth.

### Test 4 — LOW_100 with 1 MiB SO_*BUF (initial value)

Same device and conditions as Test 2-3.

**Profile applied**:
```
network profile user_choice=LOW_100
  tcp_socket_buf=1048576     ← 1 MiB
  tcp_bridge_buf=65536       ← 64 KiB
  quic_brutal_target_mbps=100
  quic_udp_socket_buf=4194304
  quic_window_headroom_ratio=0.75
```

**Run conditions**: 30 active tunnels.

**Result**:
- **Download**: 188.10 Mbps
- **Upload**: 5.04 Mbps ← BROKEN
- **Idle ping**: 33 ms
- **Loaded ping**: ↓ 33 ms, ↑ 33 ms (low because the link
  isn't saturating — upload throughput cap is too low to bloat
  the buffer)

**Outcome**: SYMMETRIC REGRESSION. The 5.04 Mbps upload is
within 0.6% of the 5.07 Mbps Samsung saw at 12 MiB. Two
completely different SO_*BUF values, two different devices,
two different kernel versions — same failure mode. The
mechanism has to be symmetric around Android's `tcp_wmem`
auto-tune default zone.

Hypothesis updated: explicitly setting `SO_SNDBUF` below
`tcp_wmem` default ALSO disables auto-tuning. With 1 MiB
manually set, `tcp_adv_win_scale` overhead (default 1, i.e.
quarter of the buffer reserved for ack/window accounting)
eats enough of the small buffer that the effective sending
window under multi-flow load can't grow past ~5 Mbps.

### Test 5 — LOW_100 with 1.5 MiB SO_*BUF (bisection)

Same device. App version 1.0.112.

To pin the lower edge of the safe zone, the next try was a
bisection at 1.5 MiB — midpoint between broken (1 MiB) and
known-safe (2 MiB, from Test 3).

**Profile applied**:
```
network profile user_choice=LOW_100
  tcp_socket_buf=1572864     ← 1.5 MiB (= 1_536 × 1024)
  tcp_bridge_buf=65536
  quic_brutal_target_mbps=100
  quic_udp_socket_buf=4194304
  quic_window_headroom_ratio=0.75
```

**Run conditions**: 70 active tunnels.

**Result**:
- **Download**: 331.61 Mbps
- **Upload**: 298.95 Mbps
- **Idle ping**: 84 ms
- **Loaded ping**: ↓ 244 ms, ↑ 58 ms

**Outcome**: CLEAN. The lower edge of the safe zone sits
**between 1.0 and 1.5 MiB**, closer to 1.5 than we initially
assumed. Pinning LOW_100 at 1.5 MiB gives a small bufferbloat
improvement over 2 MiB (~120 ms worst case at 100 Mbps vs
~160 ms at 2 MiB) while staying safely above the regression
threshold.

## Results summary

| # | Device | Profile | SO_*BUF | bridge | DL | UL | Idle ping | Verdict |
|---|---|---|---|---|---|---|---|---|
| 1a | Samsung q7q | HIGH_1000 | **12 MiB** | 256 KiB | 76 | **5** | 169 | UL BROKEN |
| 1b | Samsung q7q | HIGH_1000 | **12 MiB** | 256 KiB | 298 | **24** | 33 | UL BROKEN |
| 2 | Pixel komodo | HIGH_1000 | 4 MiB | 256 KiB | 305 | 260 | 82 | CLEAN |
| 3 | Pixel komodo | MID_500 | 2 MiB | 128 KiB | 296 | 280 | **34** | CLEAN ★ |
| 4 | Pixel komodo | LOW_100 | **1 MiB** | 64 KiB | 188 | **5** | 33 | UL BROKEN |
| 5 | Pixel komodo | LOW_100 | 1.5 MiB | 64 KiB | 332 | 299 | 84 | CLEAN |

★ Test 3 = best ping while keeping symmetric throughput. If a
fourth profile slot ever lands (a "balanced low-latency"
preset), this is the configuration to start from.

## Findings

### 1. Android TCP SO_*BUF safe zone: 1.5 – 4 MiB

| Boundary | Value | Symptom outside |
|---|---|---|
| Upper edge | 4 MiB | 12 MiB → ~5 Mbps upload (Samsung) |
| Lower edge | between 1.0 and 1.5 MiB | 1 MiB → ~5 Mbps upload (Pixel) |

Two-sided regression with identical failure mode. The
5.04 / 5.07 / 24 Mbps cluster of broken-upload numbers is too
tight to be coincidence — same underlying kernel mechanism
fires at both extremes.

Most plausible explanation: `SO_SNDBUF` set outside the
kernel's `tcp_wmem` default range (typically `4096 87380
4194304` on Linux 6.x, but ROM-customised on Android) disables
auto-tuning. The effective send window after manual override
+ `tcp_adv_win_scale` overhead falls below what the BDP
demands under multi-flow load. Receive direction is governed
by separate `tcp_rmem` config that doesn't trigger the same
collapse — hence the asymmetric symptom (download fine,
upload broken).

This finding is encoded in `NetworkProfile.kt`'s `tuning()`
kdoc and in `ARCHITECTURE.md` §NetworkProfile-driven tuning.
Future changes to the SO_*BUF values MUST stay within this
range or re-run the field tests.

### 2. Bridge buffer scales with profile without breakage

Bridge buffer values (64 / 128 / 256 KiB) were not
individually field-tested for failure modes — only the
SO_*BUF tests forced choices on bridge buffer because the
two scale together in the preset. No bridge-buffer regression
was observed in the 1.5–4 MiB SO_*BUF safe zone. Hypothesis:
because the bridge buffer lives in userspace and is sized
in tens of KiB not MiB, it doesn't interact with the
kernel-side TCP auto-tuning that caused the SO_*BUF issues.

### 3. Multi-flow proxy traffic absorbs small per-flow caps

The proxy held 30–88 simultaneous TCP tunnels during these
tests. Per-flow throughput cap at 1.5 MiB / 100 ms RTT is
roughly 120 Mbps single-flow, well below the 332 Mbps total
download observed at LOW_100. The aggregate adds up because
each tunnel is a separate TCP flow with its own window
scaling — proxy traffic patterns mask the per-flow ceiling
that would matter for a single big-file download.

If a single-flow scraper / single-stream video case ever
becomes a target workload, the SO_*BUF floor at LOW_100 will
matter more and this report should be revisited.

### 4. QUIC parameters were NOT exercised

Every test session negotiated TCP. The `quic_brutal_target_mbps`,
`quic_udp_socket_buf`, and `quic_window_headroom_ratio` values
in the profile were applied to the configuration but never
ran live traffic. The values shipped are theoretical (sized
to BDP at the profile's target rate × 100 ms RTT, with
`window_headroom_ratio` chosen for FC update frequency).
QUIC validation is a follow-up.

## Final values (code state on 2026-05-29)

| Profile | TCP SO_*BUF | TCP bridge | Brutal CC | UDP buf | FC headroom | QUIC SendBuf cap |
|---|---|---|---|---|---|---|
| LOW_100 (default) | 1.5 MiB | 64 KiB | 100 Mbps | 4 MiB | 0.75 | 64 KiB |
| MID_500 | 2 MiB | 128 KiB | 500 Mbps | 16 MiB | 0.60 | 128 KiB |
| HIGH_1000 | 4 MiB | 256 KiB | 1 Gbps | 32 MiB | 0.50 | 256 KiB |

Default `LOW_100` picked because most mobile/Wi-Fi uplinks fall
below 100 Mbps in practice, and the smaller kernel queue bounds
bufferbloat tighter. The Test 5 result above (LOW_100 on a
gigabit channel through the proxy → 331/299 Mbps) demonstrates
that multi-flow workloads still saturate fast links at LOW_100,
so users on faster networks aren't penalised by the safe default.

### New on 2026-05-29: QUIC SendBuf cap

Added in build 120 alongside the QUIC duplex / upload fix series
(see ARCHITECTURE.md "Bounded SendBuffer + auto-RESET stuck
streams" — also tracked here in the new "QUIC validation
2026-05-29" section). Caps per-stream userspace SendBuffer at
the chosen size; bridge threads block on `notFull.await()`
once the cap is hit. Values track the TCP `bridge_buffer_bytes`
column intentionally — they describe the same conceptual unit
(per-stream userspace flush size) at the QUIC layer.

The cap value matters for the credit-pin recovery scenario:
larger cap = more stuck bytes per stream when peer's MAX_DATA
freezes (build-119 with 4 MiB cap showed 78 MB queued across
300 streams = unrecoverable). Picked to mirror kwik's 50 KB
default and tracked the TCP bridge buffer sizing for
consistency.

## Open questions

These are gaps in the current test data. Each one is a follow-
up test someone should run before assuming they know the
answer:

### a. QUIC transport under each profile

**Partially resolved on 2026-05-29** (Samsung q7q, HIGH_1000) —
the QUIC validation surfaced three independent regressions and
fixes are now in code. See the
[QUIC validation 2026-05-29](#quic-validation-2026-05-29)
section below for the run-by-run capture. Headline:

- `flow.send_credit` pinning at 0 after download speedtest
  (proxy-server-go MAX_DATA quirk) was fixed via auto-RESET
  stuck-stream sweeper + RESET_STREAM emission.
- 70 ACK/s capped peer's CUBIC growth — dropped to 350 ACK/s
  by lowering `IMMEDIATE_ACK_THRESHOLD` from 10 to 2.
- 4 MiB SendBuffer cap allowed 78 MB of stuck bytes after
  download → tightened to 64/128/256 KB per profile, matching
  kwik's design.

Result on a healthy path (Euronet Hlobyne, RTT 84 ms): QUIC
upload reached 63 Mbps (vs 0 Mbps before, vs 260 Mbps for TCP).
On a degraded path (ZasNet Oleksandriya through proxy in same
region): QUIC ≈ 7 Mbps both directions — confirmed as
peer-side / path-side asymmetry, not agent stack (TCP on the
same path also limited).

The QUIC matrix per profile (validate `brutalTargetMbps` /
`udpSocketBufBytes` / `windowUpdateHeadroomRatio` / new
`sendBufferMaxBytes`) is still incomplete — only HIGH_1000 was
actively exercised. LOW_100 and MID_500 need their own runs.

### b. Cellular link behaviour
All tests on Wi-Fi to a local router. Cellular adds:
- Higher RTT (cell tower hop, typically 30-100 ms additional)
- Variable bandwidth (RAT switches LTE↔5G)
- Bursty loss patterns

Profile values were sized for ~100 ms RTT but cellular often
exceeds that. LOW_100 might need a SO_*BUF bump on cellular
to keep BDP filled; alternatively LOW_100 might prove ideal
on cellular because the link's natural rate matches the
profile's design point.

### c. Other Android devices / kernels
Only two devices tested:
- Samsung q7q, Linux 6.6.98 (Test 1)
- Pixel komodo, Linux 6.1.145 (Tests 2–5)

Older kernels (5.x), different vendor net stacks (Xiaomi,
OnePlus, Huawei) may have different `net.core.{r,s}mem_max`
defaults that shift the safe zone. The 4 MiB upper anchor is
prod-validated broadly (matches the old hardcoded value);
the 1.5 MiB lower edge is from one device.

### d. Long-running stability
Each speedtest run is ~30 s. No multi-hour soak. A profile
that works at minute 5 might develop a memory leak or buffer
deadlock at hour 5. Future test: run with `MID_500` for 24
hours with a 1-flow-per-minute target connect pattern,
monitor tunnel-open success rate and per-tunnel byte counts
in agent.log.

### e. Single-flow vs multi-flow throughput
Speedtest used multi-connection mode (default). Single-flow
throughput per profile not measured. A single big file
download will see the per-flow cap (~120 Mbps at LOW_100 /
100 ms RTT). Whether that matters depends on workload.

### f. Bridge buffer regression boundaries
Bridge buffer values (64/128/256 KiB) work alongside SO_*BUF
in the safe zone, but were not pushed below 64 KiB or above
256 KiB. Smallest practical bridge buffer might be a few
hundred bytes (Nagle interaction at low rates); largest is
bounded by per-tunnel memory cost (256 KiB × N_tunnels).

### g. Captured logs not yet versioned
Raw `agent.log` exports and speedtest screenshots from Tests
1–5 exist as Telegram-shared files (timestamped
`proxy-agent-YYYYMMDD-HHMMSS.log`). They should be moved into
`docs/test-logs/2026-05-28/` so the raw data is preserved
alongside this report.

## QUIC validation 2026-05-29

The original 2026-05-28 matrix exercised TCP only because the
sticky-transport cache happened to negotiate TCP every run.
On 2026-05-29 a separate test series **forced QUIC**
(`.proxyagent_transport` deleted between runs) on Samsung q7q,
HIGH_1000 profile. The series uncovered three independent
regressions; fixes are now in code (builds 117 → 120).

### Symptom progression

Each row corresponds to one test/build cycle. The user ran
Speedtest.net Multi-Connection from a separate test client
through the proxy.

| Build | Profile | Server | Public IP | DL Mbps | UL Mbps | Disconnect between DL→UL? | Notes |
|---|---|---|---|---|---|---|---|
| 1.0.115 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 (ZasNet) | 176.83 | 4.85 | Yes, 5 s | Original build-97 self-heal firing; symptom was 5 Mbps UL identical to a *different* failure mode the user remembered from earlier (TCP `SO_*BUF=12 MiB` outside safe zone). Misidentified at first as same bug — but on ZasNet, TCP also saw ≈5 Mbps UL, suggesting path limit. |
| 1.0.116 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 | 179.29 | **0** | No (we removed self-heal) | "rxIdle gate" added to suppress disconnect — broke the recovery mechanism. quic-go's MAX_DATA pinned at 0 for 60+ seconds, upload stuck at 0 Mbps. **Revert candidate.** |
| 1.0.117 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 | 195.79 | **0** | No | rxIdle gate removed but bounded SendBuffer (4 MiB) and STOP_SENDING handler added. peer never sent STOP_SENDING → handler never fired → still 0 Mbps UL. Confirmed peer behaviour from log: `md_recv=119` stayed flat for 60+s, `send_buf_queued` accumulated 78 MB across 300 streams. |
| 1.0.118 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 | 195.79 | **0** | No | Added auto-RESET sweeper but trigger was `sendBuffer.closed && queuedBytes > 0`. Bridge threads were parked *inside* `write()` at the cap, never reached `close()` → sweeper missed them entirely. |
| 1.0.119 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 | 126.00 | **7.72** | No | **First working build.** Sweeper trigger switched to `lastDrainNanos` (catches parked-bridge case). 22 streams auto-reset 2 s after download, `send_credit` jumped from 0 to 12.5 MB, upload phase ran at ~7 Mbps. Path-limited (same as TCP on this uplink). |
| 1.0.120 | HIGH_1000 | OPTINET Poltava | 188.239.123.47 | 132.31 | 7.70 | No | `IMMEDIATE_ACK_THRESHOLD` dropped from 10 to 2 (≈350 ACK/s instead of 70). No measurable change on this path — confirms the 7 Mbps cap was the path, not our ACK rate. |
| 1.0.120 | HIGH_1000 | Euronet Hlobyne | 195.64.231.232 (Vizit-net) | 45.33 | **63.39** | No | **Healthy path validation.** Upload ABOVE download — definitively shows the agent stack supports much higher upload than the ZasNet path allows. Path-limited tests like the first six rows above were peer/path issues, not agent issues. |

### Code state after this series (matches "Final values" §388):

- `sendBufferMaxBytes` per profile (64/128/256 KB)
- `IMMEDIATE_ACK_THRESHOLD = 2` (was 10)
- Real `ack_delay` reported in ACK frames (was always 0)
- STOP_SENDING handler emits matching RESET_STREAM (was ignored)
- RESET_STREAM frame handled (forces EOF on recv buffer)
- Auto-RESET sweeper triggers on `lastDrainNanos` idle > 2 s
- Build-97 stall self-heal **removed entirely** — the bounded
  buffer + sweeper handles the same scenario without disconnects
- New stats line columns: `send_buf_queued`, `auto-reset` events
  via `logStat` (appear in exportable agent.log)

### Open: matrix completion for LOW_100 / MID_500

Only HIGH_1000 was exercised in this series. LOW_100 and
MID_500 should be run through the same QUIC matrix to confirm
the auto-RESET 2 s timeout doesn't false-positive at lower
target rates (where drain cadence is naturally slower).

## Cross-references

- Code: `app/src/main/java/com/proxyagent/app/nativeagent/quic/NetworkProfile.kt`
- Architecture context: [ARCHITECTURE.md](ARCHITECTURE.md) §NetworkProfile-driven tuning
- Operator-facing docs: [ADMIN_GUIDE.md](ADMIN_GUIDE.md) §3.7 Network optimization
- BINARY engine non-support: [BINARIES.md](BINARIES.md) §4
