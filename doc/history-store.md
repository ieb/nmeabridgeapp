# In-memory history store

## Why

The UI plots 5-min to 12-h time series for battery, engine and (in future)
nav. Earlier revisions stored each sample as a boxed Kotlin `data class`
(`BatterySample`, `EngineSample` with `Int?` / `Double?` fields) in an
`ArrayDeque`. That produced:

* ~32-140 B per sample — a 12-h ring at 1 Hz = 4-7 MB and high GC churn.
* Scale / offset / sentinel rules duplicated between the BLE decoder and
  the in-memory type.
* No nav history at all.

The store now holds samples **in the same byte layout the firmware sends
over BLE** — mostly u16 raw values — and decodes only the fields a chart
actually needs, on demand. Total RAM for nav + engine + BMS at 1 Hz /
12 h is ~3-5 MB (preallocated, zero per-sample heap churn), with full
fidelity preserved.

## Key types

Source tree: `app/src/main/java/uk/co/tfd/nmeabridge/`

| File | Role |
|------|------|
| `history/FrameRing.kt` | Fixed-stride circular byte buffer + parallel timestamps. Thread-safe (`@Synchronized`). |
| `history/RingSnapshot.kt` | Immutable read handle published via `StateFlow`. Bumps `version` on every publish so Compose recomposes. |
| `nmea/Sentinels.kt` | Shared `u16OrNull` / `s16OrNull` / `u32OrNull` / `s32OrNull` helpers + `RESERVED_*_MIN` constants. |
| `nmea/BinaryProtocol.kt` | Nav decoder + per-field `*At(snap, i)` accessors + `SENTINEL_FRAME`. |
| `nmea/EngineProtocol.kt` | Same shape for engine frames. |
| `nmea/BmsProtocol.kt` | Variable-length BMS decoder + `encodeHistorySlot()` + fixed-slot accessors + `SENTINEL_SLOT`. |
| `ui/ServerViewModel.kt` | Owns three `FrameRing`s + gap-filler ticker + `StateFlow<RingSnapshot>` per stream. |

## Storage layout

One `FrameRing(frameSize, capacity)` per stream. Backing storage is a
single preallocated `ByteArray(frameSize * capacity)` plus a parallel
`LongArray(capacity)` for capture timestamps. No per-append allocations.

```
stream     frameSize  capacity  bytes          retention @ 1 Hz
─────────  ─────────  ────────  ─────────────  ────────────────
nav             29    43 200    ~1.3 MB        12 h
engine          27    43 200    ~1.2 MB        12 h
battery         48    43 200    ~2.1 MB        12 h
```

All three rings are held on `ServerViewModel` for the lifetime of the
Activity (which itself spans Activity recreations, e.g. ChromeOS window
focus changes).

### Nav — 29 B wire frame, unchanged

Stored as emitted by the firmware (see `doc/ble-transport.md §Navigation
State`). Field offsets live as `internal const val OFF_*` on
`BinaryProtocol`.

### Engine — 27 B wire frame, unchanged

Stored as emitted by the firmware (see `doc/ble-transport.md §Engine
State`). Field offsets on `EngineProtocol`.

### BMS — 48 B fixed "history slot" derived from the variable-length wire frame

BMS frames are variable length (header + n_cells × 2 + n_ntc × 2). A
variable-stride ring would need length-prefixed records and a sidecar
index for binary search, so we canonicalise to a fixed 48 B slot:

```
off  size  field
───  ────  ─────
 0   1     magic (0xBB)
 1   2     packV u16 (0.01 V)
 3   2     currentA s16 (0.01 A)       ← signed, watch sentinel
 5   2     remainingAh u16 (0.01 Ah)
 7   2     fullAh u16 (0.01 Ah)
 9   1     soc u8 (%)
10   2     cycles u16
12   2     errors u16 (bitmask)
14   1     fet u8 (bit0=charge, bit1=discharge)
15   1     n_cells u8                  ← capped at MAX_CELLS (8)
16   16    cells[8] u16 (0.001 V)      ← unused slots padded with 0xFFFF
32   1     n_ntc u8                    ← capped at MAX_NTCS (7)
33   14    ntcs[7] u16 (0.1 K)         ← unused slots padded with 0xFFFF
47   1     reserved
```

Cap rationale: the BoatWatch firmware known at time of writing publishes
4 cells + 2 NTCs. `MAX_CELLS=8` / `MAX_NTCS=7` give comfortable headroom.
Peripherals exceeding these truncate on encode with the live
`BatteryState` (in `ServiceState.batteryState`) retaining the full set
for the battery screen's dial display.

The live BMS frame is also published to `_rawBatteryFrames` so a future
persistence layer could capture the full variable-length record.

## Per-field accessors

Each protocol object exposes `*At(snap: RingSnapshot, i: Int): T?`
methods that read only the 2-4 bytes they need, apply the reserved-band
sentinel check, and scale. Example from `BinaryProtocol.kt`:

```kotlin
fun depthAt(s: RingSnapshot, i: Int): Double? =
    u16OrNull(s.readU16(i, OFF_DEPTH))?.let { it * 0.01 }
```

`AccessorParityTest` guarantees each accessor returns an identical value
to the corresponding field of `decode(frame)`. Charts avoid the cost of
decoding the whole frame per point.

## NMEA 2000 reserved-band sentinels

All numeric fields can be "no data". Per NMEA 2000 convention the top
three values of each unsigned range are reserved:

* `0xFFFF` / `0x7FFF` — not available
* `0xFFFE` / `0x7FFE` — out of range / error
* `0xFFFD` / `0x7FFD` — reserved

The `u16OrNull` / `s16OrNull` / `u32OrNull` / `s32OrNull` helpers (in
`nmea/Sentinels.kt`) treat the whole reserved band as no-data. This
mattered historically: firmware clipping `-1E9` to `0xFFFE` used to be
rendered as a spurious `655.3 m` depth reading.

## Gap-filler ticker

`ServerViewModel` runs a 1 Hz background coroutine that inserts a
sentinel frame into each ring when no real frame arrived within that
stream's `*_GAP_THRESHOLD_MS` window. The sentinel is a fixed all-N/A
frame (`SENTINEL_FRAME` / `SENTINEL_SLOT`) whose accessors all return
`null`.

```
stream     threshold       notes
────────   ─────────────   ──────────────────────────────────────────
nav / eng  1.5 s           Real cadence is 1 Hz; jitter stays under 1.5 s.
battery    15 s            Real cadence is ~5 s; threshold above that
                           so normal jitter doesn't insert a gap.
```

Effect: the chart time axis stays contiguous. When the NMEA 2000 bus is
switched off the chart line drops (accessors return null → draw loop
breaks the line) and a gap fills until the bus comes back. Without the
ticker, charts would "hold" the last value indefinitely.

## Thread safety

* **Writes** (`append`) originate on `Dispatchers.Default` from the BLE
  source's `MutableSharedFlow<ByteArray>` (see `BleNmeaSource` →
  `NmeaForegroundService` → `ServerViewModel`).
* **Reads** are from the Compose render thread in chart accessors.
* Both paths go through `@Synchronized` methods on `FrameRing`. Holding
  the lock per read is cheap — chart slices are ≤300 points, each read
  is two bytes.
* `RingSnapshot` captures `head` + `count` + `version` + `newestMs` at
  publish time. The bytes themselves are read live from the shared ring
  under the lock; there's no defensive copy and no snapshot array
  allocation.

## Publish cadence

Every real append bumps the ring's internal `version`. A per-stream
throttle (`HISTORY_PUBLISH_INTERVAL_MS = 1 s`) gates how often a fresh
`RingSnapshot` is pushed to the `StateFlow`, so Compose recomposes at
most once per second per stream regardless of source rate. Sentinel
appends from the gap-filler publish unconditionally.

## Sizing in context

For a 12-hour / 1-Hz record of all three streams: ~4.5 MB of
preallocated `ByteArray` and ~1 MB of `LongArray` for timestamps. The
previous boxed-object design used 4-7 MB *plus* continuous GC pressure
(1,000+ young-GCs per hour). After the refactor, GC rate dropped to
~1 per 8 minutes in steady-state operation.

## Extension points

* **Persistence**: the wire-format bytes in each ring are byte-for-byte
  reloadable. A future disk-backed session log can `write(bytes[head..])`
  on stop and `append(frame)` on start without any decoder round-trip.
* **New fields**: adding a new accessor is one line per protocol and
  one line in `AccessorParityTest`. The ring itself doesn't need to
  change so long as the frame layout doesn't.
* **More streams**: each new stream is a new `FrameRing` + wire-frame
  emitter on `BleNmeaSource` + forwarder on `NmeaForegroundService` +
  ring + collector on `ServerViewModel`. Adding the nav ring (not yet
  charted) ran this path end-to-end.
