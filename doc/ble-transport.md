# BLE Protocol Spec



## GATT Service

| UUID | Name | Properties | Purpose |
|------|------|-----------|---------|
| `0000FF00-0000-1000-8000-00805f9b34fb` | NMEABridge Service | — | Container service |
| `0000FF01-...` | Navigation State | NOTIFY, READ | Firmware → NMEABridge |

## Navigation State (0xCC) — Characteristic `0000FF01`

Sent every second. All multi-byte values are little-endian.
29 bytes.

Following NMEA 2000 conventions, reserved values indicate "data not available":

| Type | Not Available Value |
|------|---------------------|
| U16  | `0xFFFF`            |
| U32  | `0xFFFFFFFF`        |
| S16  | `0x7FFF`            |
| S32  | `0x7FFFFFFF`        |

```
Offset  Size  Type   Field            Scale         Range
─────────────────────────────────────────────────────────
 0      1     U8     magic            0xCC          identifier
 1      4     S32    latitude         1E-7 deg      ±90 deg
 5      4     S32    longitude        1E-7 deg      ±180 deg
 9      2     U16    cog              0.0001 rad    0-6.2832 rad
11      2     U16    sog              0.01 m/s      0-655.34 m/s
13      2     S16    variation        0.0001 rad    ±3.1416 rad
15      2     U16    heading          0.0001 rad    0-6.2832 rad
17      2     U16    depth            0.01 m        0-655.34 m
19      2     U16    awa              0.0001 rad    0-6.2832 rad
21      2     U16    aws              0.01 m/s      0-655.34 m/s
23      2     U16    stw              0.01 m/s      0-655.34 m/s
25      4     U32    log              1 m           0-4294967294 m
```

### Example

Vessel at 50.0700°N 9.5000°W, heading east at 5 knots, depth 12.5m,
apparent wind 8 m/s from 45° off the port bow, 15 km on the log.

| Field     | Value          | Encoded (dec) | Encoded (hex LE)  |
|-----------|----------------|---------------|--------------------|
| magic     | 0xCC           | —             | `CC`               |
| latitude  | 50.0700000°    | 500700000     | `60 13 D8 1D`      |
| longitude | −9.5000000°    | −95000000     | `40 6A 56 FA`      |
| cog       | 90° (π/2 rad)  | 15708         | `5C 3D`            |
| sog       | 2.57 m/s       | 257           | `01 01`            |
| variation | +0.5° (0.0087 rad) | 87        | `57 00`            |
| heading   | 90° (π/2 rad)  | 15708         | `5C 3D`            |
| depth     | 12.50 m        | 1250          | `E2 04`            |
| awa       | 45° (π/4 rad)  | 7854          | `AE 1E`            |
| aws       | 8.00 m/s       | 800           | `20 03`            |
| stw       | 2.40 m/s       | 240           | `F0 00`            |
| log       | 15000 m        | 15000         | `98 3A 00 00`      |

```
hex: CC 60 13 D8 1D 40 6A 56 FA 5C 3D 01 01 57 00 5C 3D E2 04 AE 1E 20 03 F0 00 98 3A 00 00
```

Decodes to: 50.0700°N 009.5000°W COG 90.0° SOG 2.57m/s (5.0kn) VAR 0.5°E
HDG 90.0° DEPTH 12.50m AWA 45.0° AWS 8.00m/s STW 2.40m/s LOG 15000m

