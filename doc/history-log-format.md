# History log file format

## Overview

The app writes every BLE history frame to disk as it arrives. One
append-only binary file per stream per UTC day, rotated at midnight
UTC. Designed so that a crashed / killed process leaves a file whose
every complete record is still valid, readable by a later tool without
any recovery dance.

See also:
- `doc/history-store.md` — the in-memory ring format. The per-record
  frame bytes used in both places are identical.
- Source: `app/src/main/java/uk/co/tfd/nmeabridge/history/persist/`

## Location

On the device:

```
/sdcard/Android/data/uk.co.tfd.nmeabridge/files/history/
```

(this is the value of `Context.getExternalFilesDir("history")`). The
directory is visible without any Android permission to anything that
can access the device storage — the Files app, `adb pull`, or USB MTP.
It is wiped on app uninstall; pull the files off-device before
uninstalling if you want to keep them.

If external-files storage is not available (some emulators, very old
devices), the writer falls back to the app-private `filesDir` and logs
a warning. Files in that location still record correctly but aren't
externally reachable.

## File names

One file per stream per UTC day:

```
nav-<yyyy-MM-dd>.bin
engine-<yyyy-MM-dd>.bin
bms-<yyyy-MM-dd>.bin
```

The date is the UTC date of the file's 00:00:00 UTC start. If a file
with that name already exists when the writer opens (e.g. the app
restarted), the writer reopens it, validates the header, truncates any
partial trailing record, and appends new records at the correct slot.

## Layout

```
+------------------+ offset 0
|  14 B header     |
+------------------+ offset 14
|  record[0]       |  ← UTC second startTimeSec + 0 × secondsPerRecord
+------------------+ offset 14 + recordSize
|  record[1]       |  ← UTC second startTimeSec + 1 × secondsPerRecord
+------------------+
|  ...             |
+------------------+
|  record[N-1]     |  ← UTC second startTimeSec + (N-1) × secondsPerRecord
+------------------+ EOF
```

**No per-record timestamp.** The UTC second a record represents is
encoded by its position in the file. Random access to any time is a
single subtraction + multiply.

## Header (14 bytes)

```
off  size   field              notes
 0    7     magic              ASCII "navdata" (no null terminator)
 7    4     startTimeSec       u32 LE — UTC seconds of this file's
                               00:00:00 UTC boundary
11    1     streamType         u8 — 1 = nav, 2 = engine, 3 = bms
12    1     recordSize         u8 — 29 (nav), 27 (engine), 48 (bms)
13    1     secondsPerRecord   u8 — 1 (nav/engine), 5 (bms)
```

`secondsPerRecord` is the interval of wall-clock time each record slot
represents. It matches the source BLE cadence so most slots hold real
frames rather than sentinel padding.

A reader that doesn't recognise `streamType` / `recordSize` /
`secondsPerRecord` should log a warning and skip the file. The "navdata"
magic pins the format family so future incompatible extensions can
either redefine the u8 fields or adopt a new file extension.

### Header constants per stream

| stream | streamType | recordSize | secondsPerRecord | ~bytes/24 h |
|--------|-----------:|-----------:|-----------------:|------------:|
| nav    |          1 |         29 |                1 |       2.4 MB |
| engine |          2 |         27 |                1 |       2.2 MB |
| bms    |          3 |         48 |                5 |       0.8 MB |

Total ~5.4 MB per 24 h across all three streams.

## Records

Each record is exactly `recordSize` bytes long, in the same byte layout
the firmware emits over BLE. Field-by-field layouts are in
`doc/ble-transport.md`:

- **Nav (29 B)** — magic 0xCC, S32 lat/lon, various U16 cog/sog/…, U32
  log. Reserved-band sentinels (≥ 0xFFFD for u16, ≥ 0x7FFFFFFD for s32
  etc.) mean "no data".
- **Engine (27 B)** — magic 0xDD, U16 rpm, U32 hours, various U16
  temps/pressures/voltages, two U16 status bitmasks.
- **BMS (48 B)** — a canonical history slot (see `doc/history-store.md
  §BMS`). Header matches the wire frame through byte 15; cells and
  temps padded to fixed 8 cells and 7 NTCs.

### Sentinel records (no-data slots)

When the writer advances time past the current slot without receiving
a real frame, it fills the intervening slots with the stream's
**sentinel frame**: a fixed byte pattern whose fields all fall in the
reserved band so the per-field accessors return null.

- **Nav sentinel** — `BinaryProtocol.SENTINEL_FRAME` (29 B). Magic
  0xCC, then each field encoded at its type-specific "not available"
  value (0x7FFFFFFF for s32, 0x7FFF for s16, 0xFFFF for u16,
  0xFFFFFFFF for u32).
- **Engine sentinel** — `EngineProtocol.SENTINEL_FRAME` (27 B).
  All-0xFF body after the magic byte.
- **BMS sentinel** — `BmsProtocol.SENTINEL_SLOT` (48 B). Magic 0xBB,
  signed / unsigned N/A values in their fields, `n_cells = n_ntc = 0`
  so readers don't try to decode the padding.

A reader sees sentinel records as "no data for this slot" and should
skip or gap them rather than treating them as measurements.

## Crash safety

The writer uses `FileOutputStream(file, append=true)` with
`fd.sync()` after every record. This flushes dirty pages through the
kernel to the block device before `append()` returns, so a crash or
power cut preserves everything up to the most recently returned
`append()` call.

Partial records (OS killed mid-byte during a write) leave the file
with `(fileLen - 14) % recordSize != 0`. A reader must detect this
and truncate to the nearest slot boundary. The writer itself does
this automatically on reopen — `fileLen` is rounded down to the
nearest slot before appending resumes.

## Rules for writers

When a frame arrives:

1. Compute the current wall-clock `nowSec = now / 1000`.
2. Determine the file for `dateFor(nowSec)`:
   - If the current file's date doesn't match, close it and open the
     file for today. Create a fresh file with a new 14-byte header
     if none exists; otherwise validate the existing header and
     truncate any partial trailing record.
3. Compute `targetSlot = (nowSec - startTimeSec) / secondsPerRecord`.
4. If `targetSlot < nextSlotIndex`: drop the frame (duplicate within
   the slot, or clock regressed). Otherwise pad sentinel records into
   `[nextSlotIndex, targetSlot)` and write the real frame.
5. Advance `nextSlotIndex` to `targetSlot + 1`.
6. Flush + `fd.sync()`.

## Rules for readers (not implemented here)

Pseudocode for reading one record at `UTC time T_sec`:

```python
if file.read(7) != b"navdata":
    raise FormatError
start = read_u32_le(file)
stream = read_u8(file)
record_size = read_u8(file)
spr = read_u8(file)
if (T_sec - start) < 0 or (T_sec - start) % spr != 0:
    return "between slots — use floor(T_sec - start, spr)"
slot = (T_sec - start) // spr
body_len = file_len - 14
n_slots = body_len // record_size
if slot >= n_slots:
    return None  # no data yet
file.seek(14 + slot * record_size)
frame = file.read(record_size)
# Decode via the same *At accessors the in-memory ring uses.
```

Any record whose fields all fall in the reserved sentinel bands is a
gap-filler and should be reported as "no data for this slot".

## Rotation behaviour

The writer opens the file whose date matches the wall-clock UTC
second of the most recent `append` call. When that date rolls over
(midnight UTC), the next `append` closes the current file and opens
tomorrow's. The first `append` into the new file pads sentinel
records from slot 0 to the current slot, so cross-midnight timelines
reconstruct cleanly.

## Known limitations

- `secondsPerRecord` is fixed per-file. If a stream's source cadence
  changes, existing files keep their old value; new files adopt the
  new value if the writer is redeployed.
- The writer drops frames that arrive faster than the per-slot
  cadence (first-in-slot wins). This is correct for the 1 Hz /
  0.2 Hz BLE cadences targeted; revisit if a source ever bursts
  above the configured rate.
- Clock jumps forward → many sentinel slots padded, may cross midnight.
  Clock jumps backward → frame dropped. In both cases the log remains
  consistent, but individual frames may be lost.
