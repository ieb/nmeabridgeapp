"""BLE GATT peripheral that implements the full NMEA Bridge transport protocol
defined in doc/ble-transport.md.

Advertises two services:

* Nav Service (FF00) — FF01 navigation, FF02 engine.
* BoatWatch Service (AA00) — AA01 autopilot state / auth response,
  AA02 command / auth write, AA03 battery state.

Authentication is required for all data characteristics per the spec:
the central must write a 6-byte auth command to AA02 (magic 0xAA, cmd 0xF0,
4-byte ASCII PIN) before notifications on FF01 / FF02 / AA03 are sent.

Usage:
    cd simulator/
    uv run nmea_ble_sim.py [--pin 0000] [--lat 50.07] [--lon -9.50]
                           [--engine-off] [--fault over_temp ...]
"""

import argparse
import asyncio
import math
import random
import struct
import time
from typing import Optional

from ble_server import BleGattServer, GATTCharacteristic

# ---------- UUIDs (see doc/ble-transport.md) ----------

NAV_SERVICE_UUID = "0000FF00-0000-1000-8000-00805f9b34fb"
NAV_STATE_UUID = "0000FF01-0000-1000-8000-00805f9b34fb"
ENGINE_STATE_UUID = "0000FF02-0000-1000-8000-00805f9b34fb"

BW_SERVICE_UUID = "0000AA00-0000-1000-8000-00805f9b34fb"
BW_AUTOPILOT_UUID = "0000AA01-0000-1000-8000-00805f9b34fb"
BW_COMMAND_UUID = "0000AA02-0000-1000-8000-00805f9b34fb"
BW_BATTERY_UUID = "0000AA03-0000-1000-8000-00805f9b34fb"

# ---------- Protocol constants ----------

MAGIC_NAV = 0xCC
MAGIC_ENGINE = 0xDD
MAGIC_BATTERY = 0xBB
MAGIC_AUTOPILOT = 0xAA
MAGIC_AUTH_RESP = 0xAF
CMD_AUTH = 0xF0

NA_U16 = 0xFFFF
NA_U32 = 0xFFFFFFFF
NA_S16 = 0x7FFF
NA_S32 = 0x7FFFFFFF

# status1 (PGN 127489 Discrete Status 1)
STATUS1 = {
    "check_engine": 0x0001,
    "over_temp": 0x0002,
    "low_oil": 0x0004,
    "low_volt": 0x0020,
    "water_flow": 0x0080,
    "charge": 0x0200,
    "emergency": 0x8000,
}
# status2 (PGN 127489 Discrete Status 2)
STATUS2 = {
    "maintenance": 0x0008,
    "comm_error": 0x0010,
    "shutdown": 0x0080,
}
ALL_FAULTS = set(STATUS1) | set(STATUS2)

# Rotating demo sequence so every warning tell-tale lights up in turn when
# the simulator runs with no manual --fault flags. Each entry is (status1, status2, label)
# and is active for DEMO_FAULT_STAGE_SECONDS before advancing to the next.
DEMO_FAULT_STAGE_SECONDS = 20
DEMO_FAULT_STAGES: list[tuple[int, int, str]] = [
    (0, 0, "no warnings"),
    (STATUS1["check_engine"] | STATUS1["over_temp"], 0, "over temp"),
    (STATUS1["check_engine"] | STATUS1["low_oil"], 0, "low oil pressure"),
    (STATUS1["check_engine"] | STATUS1["charge"], 0, "charge indicator"),
    (STATUS1["check_engine"] | STATUS1["low_volt"], 0, "low system voltage"),
    (STATUS1["water_flow"], 0, "water flow"),
    (0, STATUS2["maintenance"], "maintenance needed"),
    (0, STATUS2["comm_error"], "engine comm error"),
    (0, STATUS2["shutdown"], "engine shutting down"),
    (STATUS1["check_engine"] | STATUS1["emergency"], 0, "emergency stop"),
]


# ---------- Frame packers ----------

def _speed_ms_to_u16(ms: float) -> int:
    return min(int(round(ms / 0.01)), 0xFFFE)


def _deg_to_rad_u16(deg: float) -> int:
    rad = math.radians(deg) % (2 * math.pi)
    return min(int(round(rad / 0.0001)), 0xFFFE)


def _deg_to_rad_s16(deg: float) -> int:
    val = int(round(math.radians(deg) / 0.0001))
    return max(-0x7FFE, min(0x7FFE, val))


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
    """29-byte Navigation State frame (magic 0xCC)."""
    return bytearray(struct.pack(
        "<BiiHHhHHHHHI",
        MAGIC_NAV,
        int(round(lat * 1e7)),
        int(round(lon * 1e7)),
        _deg_to_rad_u16(cog_deg),
        _speed_ms_to_u16(sog_ms),
        _deg_to_rad_s16(variation_deg),
        _deg_to_rad_u16(heading_deg),
        min(int(round(depth_m / 0.01)), 0xFFFE),
        _deg_to_rad_u16(awa_deg),
        _speed_ms_to_u16(aws_ms),
        _speed_ms_to_u16(stw_ms),
        min(log_m, 0xFFFFFFFE),
    ))


def _temp_k_u16(c: Optional[float]) -> int:
    if c is None:
        return NA_U16
    return min(int(round((c + 273.15) / 0.01)), 0xFFFE)


def _u16_or_na(val: Optional[float], scale: float) -> int:
    if val is None:
        return NA_U16
    return min(int(round(val / scale)), 0xFFFE)


def pack_engine_state(
    rpm: Optional[float],
    hours_sec: Optional[int],
    coolant_c: Optional[float],
    alt_c: Optional[float],
    alt_v: Optional[float],
    oil_bar: Optional[float],
    exhaust_c: Optional[float],
    engine_room_c: Optional[float],
    engine_batt_v: Optional[float],
    fuel_pct: Optional[float],
    status1: int = 0,
    status2: int = 0,
) -> bytearray:
    """27-byte Engine State frame (magic 0xDD)."""
    rpm_raw = NA_U16 if rpm is None else min(int(round(rpm / 0.25)), 0xFFFE)
    hrs_raw = NA_U32 if hours_sec is None else min(hours_sec, 0xFFFFFFFE)
    coolant_raw = _temp_k_u16(coolant_c)
    alt_t_raw = _temp_k_u16(alt_c)
    alt_v_raw = _u16_or_na(alt_v, 0.01)
    oil_raw = NA_U16 if oil_bar is None else min(int(round(oil_bar / 0.001)), 0xFFFE)
    exhaust_raw = _temp_k_u16(exhaust_c)
    room_raw = _temp_k_u16(engine_room_c)
    batt_raw = _u16_or_na(engine_batt_v, 0.01)
    fuel_raw = NA_U16 if fuel_pct is None else min(int(round(fuel_pct / 0.004)), 0xFFFE)
    return bytearray(struct.pack(
        "<BHIHHHHHHHHHH",
        MAGIC_ENGINE,
        rpm_raw,
        hrs_raw,
        coolant_raw,
        alt_t_raw,
        alt_v_raw,
        oil_raw,
        exhaust_raw,
        room_raw,
        batt_raw,
        fuel_raw,
        status1 & 0xFFFF,
        status2 & 0xFFFF,
    ))


def pack_battery_state(
    pack_v: float,
    current_a: float,
    remaining_ah: float,
    full_ah: float,
    soc: int,
    cycles: int,
    errors: int,
    fet: int,
    cells_v: list[float],
    ntc_c: list[float],
) -> bytearray:
    """Battery State frame (magic 0xBB, variable length)."""
    buf = bytearray()
    buf += struct.pack(
        "<BHhHHBHHBB",
        MAGIC_BATTERY,
        int(round(pack_v / 0.01)) & 0xFFFF,
        int(round(current_a / 0.01)),  # signed
        int(round(remaining_ah / 0.01)) & 0xFFFF,
        int(round(full_ah / 0.01)) & 0xFFFF,
        soc & 0xFF,
        cycles & 0xFFFF,
        errors & 0xFFFF,
        fet & 0xFF,
        len(cells_v) & 0xFF,
    )
    for v in cells_v:
        buf += struct.pack("<H", int(round(v / 0.001)) & 0xFFFF)
    buf += struct.pack("<B", len(ntc_c) & 0xFF)
    for c in ntc_c:
        k10 = int(round((c + 273.15) / 0.1))
        buf += struct.pack("<H", k10 & 0xFFFF)
    return buf


def pack_autopilot_state(
    mode: int,
    current_hdg_deg: float,
    target_hdg_deg: float,
    target_wind_deg: float,
) -> bytearray:
    """10-byte Autopilot State frame (magic 0xAA)."""
    return bytearray(struct.pack(
        "<BBHHhH",
        MAGIC_AUTOPILOT,
        mode & 0xFF,
        int(round(current_hdg_deg / 0.01)) & 0xFFFF,
        int(round(target_hdg_deg / 0.01)) & 0xFFFF,
        int(round(target_wind_deg / 0.01)),
        0x0000,
    ))


def pack_auth_response(accepted: bool) -> bytearray:
    return bytearray([MAGIC_AUTH_RESP, 0x01 if accepted else 0x00])


# ---------- Simulator ----------

class Simulator:
    def __init__(self, args):
        self.args = args
        self.authenticated = False
        self.server = BleGattServer(args.name)

        nav_service = self.server.add_service(NAV_SERVICE_UUID)
        self.nav_char = self.server.add_characteristic(
            nav_service, NAV_STATE_UUID,
            GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
            GATTCharacteristic.PERM_READ,
        )
        self.engine_char = self.server.add_characteristic(
            nav_service, ENGINE_STATE_UUID,
            GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
            GATTCharacteristic.PERM_READ,
        )

        bw_service = self.server.add_service(BW_SERVICE_UUID)
        self.autopilot_char = self.server.add_characteristic(
            bw_service, BW_AUTOPILOT_UUID,
            GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
            GATTCharacteristic.PERM_READ,
        )
        self.command_char = self.server.add_characteristic(
            bw_service, BW_COMMAND_UUID,
            GATTCharacteristic.PROP_WRITE | GATTCharacteristic.PROP_WRITE_NO_RESP,
            GATTCharacteristic.PERM_WRITE,
        )
        self.battery_char = self.server.add_characteristic(
            bw_service, BW_BATTERY_UUID,
            GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
            GATTCharacteristic.PERM_READ,
        )

        self.server.on_write = self._on_write
        self.server.on_connect = self._on_connect
        self.server.on_disconnect = self._on_disconnect

        # nav state
        self.bearing = 0.0
        self.radius = 0.005
        self.log_m = 0
        knots_to_ms = 0.514444
        self.sog_ms = args.speed * knots_to_ms
        self.stw_ms = self.sog_ms * 0.95

        # engine state
        self.engine_hours_sec = 1_954_800  # 543 h
        self.engine_t = 0.0
        self.coolant_c = 25.0

        # fault mask (OR'd into every engine frame)
        self.fault_status1 = 0
        self.fault_status2 = 0
        for f in args.fault:
            if f in STATUS1:
                self.fault_status1 |= STATUS1[f]
            elif f in STATUS2:
                self.fault_status2 |= STATUS2[f]

        # Last emitted demo-fault stage index (so we only log on change).
        self._last_demo_stage = -1

        # battery state
        self.battery_soc = 78
        self.battery_remaining = 78.0
        self.battery_cycles = 42

    # ---- write handler ----
    def _on_write(self, char_uuid: str, data: bytes):
        if char_uuid.lower() == BW_COMMAND_UUID.lower():
            self._handle_command(data)

    def _handle_command(self, data: bytes):
        if len(data) < 2 or data[0] != MAGIC_AUTOPILOT:
            print(f"[CMD] malformed command: {data.hex()}")
            return
        cmd = data[1]
        if cmd == CMD_AUTH:
            pin = data[2:6].decode("ascii", errors="replace")
            ok = pin == self.args.pin
            self.authenticated = ok
            resp = pack_auth_response(ok)
            # Auth response goes on both AA01 and AA03 per spec
            self.server.notify(self.autopilot_char, resp)
            self.server.notify(self.battery_char, resp)
            print(f"[AUTH] {'accepted' if ok else 'rejected'} PIN={pin!r}")
        else:
            print(f"[CMD] 0x{cmd:02X} payload={data[2:].hex()} (no-op)")

    def _on_connect(self):
        print("[BLE] Client connected")
        self.authenticated = False

    def _on_disconnect(self):
        print("[BLE] Client disconnected")
        self.authenticated = False

    # ---- notify loops ----
    async def nav_loop(self):
        tick = 0
        while True:
            lat = self.args.lat + self.radius * math.cos(math.radians(self.bearing))
            lon = self.args.lon + self.radius * math.sin(math.radians(self.bearing))
            depth = 13.0 + 5.0 * math.sin(math.radians(self.bearing * 2.0))
            awa_deg = 45.0 + 10.0 * math.sin(math.radians(self.bearing * 0.5))
            aws_ms = 8.0 + 1.5 * math.sin(math.radians(self.bearing))
            self.log_m += int(round(self.sog_ms))

            frame = pack_nav_state(
                lat=lat, lon=lon,
                cog_deg=self.bearing, sog_ms=self.sog_ms,
                variation_deg=0.5,
                heading_deg=self.bearing,
                depth_m=depth,
                awa_deg=awa_deg, aws_ms=aws_ms,
                stw_ms=self.stw_ms,
                log_m=self.log_m,
            )
            if self.authenticated and self.server.has_subscribers(self.nav_char):
                self.server.notify(self.nav_char, frame)
                if tick % 10 == 0:
                    print(f"[TX-NAV] LAT={lat:.5f} LON={lon:.5f} "
                          f"COG={self.bearing:.0f}° SOG={self.args.speed:.1f}kn")
            self.bearing = (self.bearing + 3.6) % 360.0
            tick += 1
            await asyncio.sleep(1.0)

    async def engine_loop(self):
        tick = 0
        while True:
            if self.args.engine_off:
                rpm = None
                coolant = None
                alt_c = None
                alt_v = None
                oil = None
                exhaust = None
                hours = self.engine_hours_sec
            else:
                # 2-minute sinusoidal ramp 0 -> 2800 -> 0
                phase = (self.engine_t % 120.0) / 120.0 * 2 * math.pi
                rpm = max(0.0, 1400.0 - 1400.0 * math.cos(phase))
                # coolant slowly climbs to 85C while running, cools when off
                if rpm > 200:
                    self.coolant_c = min(85.0, self.coolant_c + 0.3)
                    self.engine_hours_sec += 1
                else:
                    self.coolant_c = max(22.0, self.coolant_c - 0.05)
                coolant = self.coolant_c
                alt_c = 60.0 + rpm / 100.0 * 0.5   # up to ~75C
                alt_v = 14.2 if rpm > 200 else 13.2
                oil = 0.8 + (rpm / 4000.0) * 3.5    # 0.8 .. 4.3 bar
                exhaust = 60.0 + rpm / 16.0         # up to ~310C
                hours = self.engine_hours_sec

            fuel = 75.0 - (self.engine_t / 3600.0) * 0.5  # slow burn
            engine_room = 22.0 + 2.0 * math.sin(self.engine_t / 60.0)
            engine_batt = 12.6

            status1 = self.fault_status1
            status2 = self.fault_status2

            # Rotating demo of each warning tell-tale (disable with --no-demo-faults).
            if not self.args.no_demo_faults:
                stage = int(self.engine_t // DEMO_FAULT_STAGE_SECONDS) % len(DEMO_FAULT_STAGES)
                demo_s1, demo_s2, demo_label = DEMO_FAULT_STAGES[stage]
                status1 |= demo_s1
                status2 |= demo_s2
                if stage != self._last_demo_stage:
                    self._last_demo_stage = stage
                    print(f"[FAULT] stage {stage}/{len(DEMO_FAULT_STAGES) - 1}: {demo_label} "
                          f"(s1=0x{demo_s1:04X} s2=0x{demo_s2:04X})")

            frame = pack_engine_state(
                rpm=rpm,
                hours_sec=hours,
                coolant_c=coolant,
                alt_c=alt_c,
                alt_v=alt_v,
                oil_bar=oil,
                exhaust_c=exhaust,
                engine_room_c=engine_room,
                engine_batt_v=engine_batt,
                fuel_pct=max(0.0, fuel),
                status1=status1,
                status2=status2,
            )
            if self.authenticated and self.server.has_subscribers(self.engine_char):
                self.server.notify(self.engine_char, frame)
                if tick % 5 == 0:
                    rpm_s = f"{rpm:.0f}" if rpm is not None else "—"
                    t_s = f"{coolant:.0f}°C" if coolant is not None else "—"
                    p_s = f"{oil:.1f}bar" if oil is not None else "—"
                    print(f"[TX-ENG] RPM={rpm_s} T={t_s} P={p_s} "
                          f"Fuel={fuel:.0f}% Hrs={hours // 3600}h "
                          f"s1=0x{status1:04X} s2=0x{status2:04X}")
            self.engine_t += 1.0
            tick += 1
            await asyncio.sleep(1.0)

    async def battery_loop(self):
        tick = 0
        while True:
            # Slow sinusoidal V/I
            v = 13.2 + 0.6 * math.sin(tick / 12.0)
            i = -20.0 * math.sin(tick / 8.0)
            self.battery_remaining = max(0.0, min(100.0, self.battery_remaining - i / 3600.0))
            soc = int(round(self.battery_remaining))
            cells = [v / 4.0 + random.uniform(-0.002, 0.002) for _ in range(4)]
            ntcs = [22.0, 23.0, 23.5]
            frame = pack_battery_state(
                pack_v=v,
                current_a=i,
                remaining_ah=self.battery_remaining,
                full_ah=100.0,
                soc=soc,
                cycles=self.battery_cycles,
                errors=0,
                fet=0x03,
                cells_v=cells,
                ntc_c=ntcs,
            )
            if self.authenticated and self.server.has_subscribers(self.battery_char):
                self.server.notify(self.battery_char, frame)
                cell_mv = [int(c * 1000) for c in cells]
                print(f"[TX-BAT] {v:.2f}V {i:+.1f}A SOC={soc}% cells={cell_mv}mV")
            tick += 1
            await asyncio.sleep(5.0)

    async def autopilot_loop(self):
        while True:
            frame = pack_autopilot_state(
                mode=0,                    # STANDBY
                current_hdg_deg=self.bearing,
                target_hdg_deg=self.bearing,
                target_wind_deg=0.0,
            )
            if self.authenticated and self.server.has_subscribers(self.autopilot_char):
                self.server.notify(self.autopilot_char, frame)
            await asyncio.sleep(5.0)

    async def run(self):
        await self.server.start()
        print(f"[BLE] Simulator started — advertising as '{self.args.name}'")
        print(f"[BLE]   Nav service:      {NAV_SERVICE_UUID}")
        print(f"[BLE]     FF01 Nav State  (magic 0xCC, 29 bytes, 1 Hz)")
        print(f"[BLE]     FF02 Engine     (magic 0xDD, 27 bytes, 1 Hz)")
        print(f"[BLE]   BoatWatch:        {BW_SERVICE_UUID}")
        print(f"[BLE]     AA01 Autopilot  (+ auth response 0xAF)")
        print(f"[BLE]     AA02 Command    (write PIN {self.args.pin!r} to auth)")
        print(f"[BLE]     AA03 Battery    (magic 0xBB, every 5 s)")
        print(f"[BLE]   Nav center:       {self.args.lat:.4f}, {self.args.lon:.4f}")
        if self.args.engine_off:
            print(f"[BLE]   Engine:           forced OFF (sentinels)")
        if self.args.fault:
            print(f"[BLE]   Faults forced:    {', '.join(self.args.fault)}")
        if self.args.no_demo_faults:
            print(f"[BLE]   Fault demo:       disabled")
        else:
            print(f"[BLE]   Fault demo:       rotating every "
                  f"{DEMO_FAULT_STAGE_SECONDS}s "
                  f"({len(DEMO_FAULT_STAGES)} stages, "
                  f"~{DEMO_FAULT_STAGE_SECONDS * len(DEMO_FAULT_STAGES)}s cycle)")
        print()
        print("Waiting for connections... (Ctrl+C to stop)")
        print()

        try:
            await asyncio.gather(
                self.nav_loop(),
                self.engine_loop(),
                self.battery_loop(),
                self.autopilot_loop(),
            )
        except asyncio.CancelledError:
            pass
        finally:
            await self.server.stop()
            print("[BLE] Server stopped")


def main():
    parser = argparse.ArgumentParser(description="Full NMEA Bridge BLE transport simulator")
    parser.add_argument("--name", default="NMEA GPS", help="BLE device name (default: NMEA GPS)")
    parser.add_argument("--lat", type=float, default=50.07, help="Center latitude (default: 50.07)")
    parser.add_argument("--lon", type=float, default=-9.50, help="Center longitude (default: -9.50)")
    parser.add_argument("--speed", type=float, default=5.0, help="Speed in knots (default: 5.0)")
    parser.add_argument("--pin", default="0000", help="Expected auth PIN (default: 0000)")
    parser.add_argument("--engine-off", action="store_true",
                        help="Force engine sentinels (tests the '— —' dial state)")
    parser.add_argument("--fault", action="append", default=[], choices=sorted(ALL_FAULTS),
                        help="Set one or more engine fault bits (repeatable). "
                             "OR-combined with the rotating fault demo.")
    parser.add_argument("--no-demo-faults", action="store_true",
                        help=f"Disable the rotating fault-demo cycle that lights up "
                             f"each warning tell-tale in turn "
                             f"(every {DEMO_FAULT_STAGE_SECONDS}s).")
    args = parser.parse_args()

    sim = Simulator(args)
    try:
        asyncio.run(sim.run())
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
