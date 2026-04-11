"""BLE GATT peripheral that streams navigation state using the binary protocol
defined in doc/ble-transport.md.

Simulates a Bluetooth navigation device for testing the NMEA Bridge Android app.
Advertises a custom GATT service with a single notify characteristic that sends
a 29-byte binary Navigation State frame at 1 Hz.

Usage:
    cd simulator/
    uv run nmea_ble_sim.py [--name "NMEA GPS"] [--lat 50.07] [--lon -9.50]
"""

import argparse
import asyncio
import math
import struct

from ble_server import BleGattServer, GATTCharacteristic

# BLE UUIDs — from doc/ble-transport.md
SERVICE_UUID = "0000FF00-0000-1000-8000-00805f9b34fb"
NAV_STATE_UUID = "0000FF01-0000-1000-8000-00805f9b34fb"

# Protocol constants
MAGIC = 0xCC

# NMEA 2000 "not available" sentinel values
NA_U16 = 0xFFFF
NA_U32 = 0xFFFFFFFF
NA_S16 = 0x7FFF
NA_S32 = 0x7FFFFFFF


def deg_to_rad_u16(degrees: float) -> int:
    """Encode an angle in degrees to U16 in 0.0001 rad units."""
    rad = math.radians(degrees) % (2 * math.pi)
    return min(int(round(rad / 0.0001)), 0xFFFE)


def deg_to_rad_s16(degrees: float) -> int:
    """Encode a signed angle in degrees to S16 in 0.0001 rad units."""
    rad = math.radians(degrees)
    val = int(round(rad / 0.0001))
    return max(-0x7FFE, min(0x7FFE, val))


def speed_ms_to_u16(speed_ms: float) -> int:
    """Encode speed in m/s to U16 in 0.01 m/s units."""
    return min(int(round(speed_ms / 0.01)), 0xFFFE)


def pack_nav_state(
    lat: float,
    lon: float,
    cog_deg: float,
    sog_ms: float,
    variation_deg: float,
    heading_deg: float,
    depth_m: float,
    awa_deg: float,
    aws_ms: float,
    stw_ms: float,
    log_m: int,
) -> bytearray:
    """Pack a Navigation State frame (29 bytes) per the BLE protocol spec."""
    return bytearray(struct.pack(
        "<BiiHHhHHHHHI",
        MAGIC,
        int(round(lat * 1e7)),           # S32 latitude
        int(round(lon * 1e7)),           # S32 longitude
        deg_to_rad_u16(cog_deg),         # U16 cog
        speed_ms_to_u16(sog_ms),         # U16 sog
        deg_to_rad_s16(variation_deg),   # S16 variation
        deg_to_rad_u16(heading_deg),     # U16 heading
        min(int(round(depth_m / 0.01)), 0xFFFE),  # U16 depth
        deg_to_rad_u16(awa_deg),         # U16 awa
        speed_ms_to_u16(aws_ms),         # U16 aws
        speed_ms_to_u16(stw_ms),         # U16 stw
        min(log_m, 0xFFFFFFFE),          # U32 log
    ))


def decode_nav_state(data: bytes) -> str:
    """Decode a Navigation State frame for display."""
    (magic, lat, lon, cog, sog, var_, hdg, depth,
     awa, aws, stw, log) = struct.unpack("<BiiHHhHHHHHI", data)

    def fmt_angle_u16(val: int) -> str:
        if val == NA_U16:
            return "N/A"
        return f"{math.degrees(val * 0.0001):.1f}°"

    def fmt_angle_s16(val: int) -> str:
        if val == NA_S16:
            return "N/A"
        return f"{math.degrees(val * 0.0001):.1f}°"

    def fmt_speed(val: int) -> str:
        if val == NA_U16:
            return "N/A"
        return f"{val * 0.01:.2f}m/s"

    def fmt_pos(val: int) -> str:
        if val == NA_S32:
            return "N/A"
        return f"{val / 1e7:.7f}°"

    return (
        f"LAT={fmt_pos(lat)} LON={fmt_pos(lon)} "
        f"COG={fmt_angle_u16(cog)} SOG={fmt_speed(sog)} "
        f"VAR={fmt_angle_s16(var_)} HDG={fmt_angle_u16(hdg)} "
        f"DEPTH={'N/A' if depth == NA_U16 else f'{depth * 0.01:.1f}m'} "
        f"AWA={fmt_angle_u16(awa)} AWS={fmt_speed(aws)} "
        f"STW={fmt_speed(stw)} LOG={'N/A' if log == NA_U32 else f'{log}m'}"
    )


async def run(args):
    server = BleGattServer(args.name)
    service = server.add_service(SERVICE_UUID)

    nav_char = server.add_characteristic(
        service,
        NAV_STATE_UUID,
        GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
        GATTCharacteristic.PERM_READ,
    )

    def on_connect():
        print("[BLE] Client connected")

    def on_disconnect():
        print("[BLE] Client disconnected")

    server.on_connect = on_connect
    server.on_disconnect = on_disconnect

    await server.start()
    print(f"[BLE] Navigation simulator started — advertising as '{args.name}'")
    print(f"[BLE]   Service UUID:     {SERVICE_UUID}")
    print(f"[BLE]   Nav State UUID:   {NAV_STATE_UUID}")
    print(f"[BLE]   Frame:            29 bytes binary, magic=0x{MAGIC:02X}")
    print(f"[BLE]   Center:           {args.lat:.4f}, {args.lon:.4f}")
    print(f"[BLE]   Speed:            {args.speed} knots")
    print()
    print("Waiting for connections... (Ctrl+C to stop)")
    print()

    bearing = 0.0
    radius = 0.005       # ~500m circle
    log_m = 0            # cumulative log in metres
    tick = 0

    knots_to_ms = 0.514444
    sog_ms = args.speed * knots_to_ms
    stw_ms = sog_ms * 0.95   # STW slightly less than SOG (current)

    try:
        while True:
            lat = args.lat + radius * math.cos(math.radians(bearing))
            lon = args.lon + radius * math.sin(math.radians(bearing))
            depth = 13.0 + 5.0 * math.sin(math.radians(bearing * 2.0))

            # Apparent wind: 8 m/s from 45° varying slowly
            awa_deg = 45.0 + 10.0 * math.sin(math.radians(bearing * 0.5))
            aws_ms = 8.0 + 1.5 * math.sin(math.radians(bearing))

            # Accumulate log (SOG * 1 second)
            log_m += int(round(sog_ms))

            frame = pack_nav_state(
                lat=lat,
                lon=lon,
                cog_deg=bearing,
                sog_ms=sog_ms,
                variation_deg=0.5,
                heading_deg=bearing,
                depth_m=depth,
                awa_deg=awa_deg,
                aws_ms=aws_ms,
                stw_ms=stw_ms,
                log_m=log_m,
            )

            if server.has_subscribers(nav_char):
                server.notify(nav_char, frame)

                if tick % 10 == 0:
                    print(f"[TX] {decode_nav_state(frame)}")
            else:
                if tick % 10 == 0 and tick > 0:
                    print("[--] No subscribers, waiting...")

            bearing = (bearing + 3.6) % 360.0
            tick += 1
            await asyncio.sleep(1.0)

    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()
        print("[BLE] Server stopped")


def main():
    parser = argparse.ArgumentParser(description="BLE navigation state simulator (binary protocol)")
    parser.add_argument("--name", default="NMEA GPS", help="BLE device name (default: NMEA GPS)")
    parser.add_argument("--lat", type=float, default=50.07, help="Center latitude (default: 50.07)")
    parser.add_argument("--lon", type=float, default=-9.50, help="Center longitude (default: -9.50)")
    parser.add_argument("--speed", type=float, default=5.0, help="Speed in knots (default: 5.0)")
    args = parser.parse_args()

    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
