#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "bleak>=0.22",
# ]
# ///
# ble-diagnostic.py — Python port of the Android "BLE Diagnostic Test"
#
# Mirrors the scan → connect → request MTU → discover services → enumerate
# sequence from BleTestViewModel.kt so you can validate the bridge firmware
# from a laptop, without an Android device.
#
# Runs via uv (https://docs.astral.sh/uv/) — the PEP 723 block above tells uv
# which Python version and dependencies to fetch on first run. No manual venv
# or pip install needed.
#
# Firmware advertises the BoatWatch service (AA00), not the nav service
# (FF00). The Android picker matches on "FF00-advertised OR named" but the
# FF00 clause is effectively dead — it only finds the bridge because the
# firmware also advertises a device name. This script defaults to filtering
# on AA00, which is the more reliable primary signal.
#
# Typical uses:
#   uv run scripts/ble-diagnostic.py                          # 10 s scan, AA00 advertisers only
#   uv run scripts/ble-diagnostic.py --filter none            # list every advertiser
#   uv run scripts/ble-diagnostic.py --filter android         # what the Android picker keeps
#   uv run scripts/ble-diagnostic.py --address <UUID/MAC>     # full diagnostic on one device
#   uv run scripts/ble-diagnostic.py --name NmeaBridge        # first match by name substring
#   uv run scripts/ble-diagnostic.py --address X --repeat 20  # stress connect/discover
#   uv run scripts/ble-diagnostic.py --address X --forever --subscribe --auth-pin 0000
#                                                              # reconnect-storm test; each
#                                                              # cycle is a real drop the
#                                                              # firmware's on_disconnect sees
#   uv run scripts/ble-diagnostic.py --address X --subscribe  # subscribe nav(FF01)+engine(FF02)+battery(AA03) and dump every frame
#   uv run scripts/ble-diagnostic.py --address X --mtu 256    # match production MTU (Android app uses 256)
#   uv run scripts/ble-diagnostic.py --address X --auth-pin 1234 --subscribe
#                                                              # full production-style handshake
#
# The shebang uses `env -S uv run --script`, so if uv is on PATH you can also
# invoke it directly: `scripts/ble-diagnostic.py --scan-only`.
#
# Address / MAC caveat:
#   - Linux (BlueZ) and Windows expose the peripheral's real BD_ADDR
#     (e.g. "AA:BB:CC:DD:AD:1E").
#   - macOS / CoreBluetooth strips the BD_ADDR for privacy and returns a
#     per-host UUID like "A1B2C3D4-....". There is no user-space API to
#     recover the real MAC from a Mac. If the Android app shows the device
#     with a MAC ending :AD:1E, that same peripheral will appear here under
#     an opaque CoreBluetooth UUID — same device, different identifier.

import argparse
import asyncio
import statistics
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.device import BLEDevice
    from bleak.backends.scanner import AdvertisementData
    from bleak.exc import BleakError
except ImportError:
    sys.stderr.write(
        "error: bleak is not installed. Run this script via `uv run` so the\n"
        "       inline PEP 723 metadata resolves dependencies automatically:\n"
        "         uv run scripts/ble-diagnostic.py --scan-only\n"
    )
    sys.exit(2)

# ---------- constants (kept in sync with BleTestViewModel.kt / BleNmeaSource.kt) ----------

NAV_SERVICE_UUID    = "0000ff00-0000-1000-8000-00805f9b34fb"
NAV_NOTIFY_UUID     = "0000ff01-0000-1000-8000-00805f9b34fb"
ENGINE_STATE_UUID   = "0000ff02-0000-1000-8000-00805f9b34fb"

BW_SERVICE_UUID     = "0000aa00-0000-1000-8000-00805f9b34fb"
BW_AUTOPILOT_UUID   = "0000aa01-0000-1000-8000-00805f9b34fb"
BW_COMMAND_UUID     = "0000aa02-0000-1000-8000-00805f9b34fb"
BW_BATTERY_UUID     = "0000aa03-0000-1000-8000-00805f9b34fb"

# Auth wire-protocol constants from BleNmeaSource.kt
MAGIC_AUTOPILOT = 0xAA
MAGIC_AUTH_RESP = 0xAF
CMD_AUTH        = 0xF0

# The Android diagnostic screen defaults to these:
DEFAULT_SCAN_TIMEOUT = 10.0    # BleTestViewModel.SCAN_TIMEOUT_MS = 10_000
DEFAULT_MTU          = 64      # BleTestViewModel.kt:104 (production BleNmeaSource uses 256)


# ---------- small helpers ----------

def now_str() -> str:
    return time.strftime("%H:%M:%S")

def log(msg: str) -> None:
    print(f"[{now_str()}] {msg}", flush=True)

def short_uuid(u: str) -> str:
    u = u.lower()
    if len(u) == 36 and u.endswith("-0000-1000-8000-00805f9b34fb"):
        return u[4:8].upper()
    return u

def props_str(char) -> str:
    order = ["read", "write", "write-without-response", "notify", "indicate"]
    short = {"read": "R", "write": "W", "write-without-response": "WNR",
             "notify": "N", "indicate": "I"}
    got = [short[p] for p in order if p in char.properties]
    return ",".join(got) if got else "-"


# ---------- scan ----------

@dataclass
class SeenDevice:
    address: str            # BD_ADDR on Linux/Windows; opaque CoreBluetooth UUID on macOS
    name: Optional[str]     # first non-empty of adv.local_name or device.name
    rssis: list = field(default_factory=list)
    service_uuids: set = field(default_factory=set)          # lowercase full UUIDs
    service_data: dict = field(default_factory=dict)         # uuid -> hex string
    manufacturer_data: dict = field(default_factory=dict)    # company_id -> hex string
    tx_power: Optional[int] = None
    adv_count: int = 0
    first_seen_ms: float = 0.0

    @property
    def advertises_nav(self) -> bool:
        return NAV_SERVICE_UUID in self.service_uuids

    @property
    def advertises_bw(self) -> bool:
        return BW_SERVICE_UUID in self.service_uuids

    def matches_android_picker(self) -> bool:
        # Mirrors ServerViewModel.kt:180-183 / MonitorViewModel.kt:88-96:
        # "advertisesNavService OR deviceName != null"
        return self.advertises_nav or bool(self.name)


def _mac_hint() -> str:
    if sys.platform == "darwin":
        return ("[macOS] identifier is a CoreBluetooth UUID — Apple hides real BD_ADDRs. "
                "The Android app on the same LAN will show the real MAC (e.g. ..:AD:1E).")
    return ""


async def scan(timeout: float, filter_mode: str) -> dict:
    """
    filter_mode:
      'none'      — surface every advertiser
      'bw'        — default: only devices advertising the BoatWatch service (AA00).
                    That is what the firmware actually broadcasts.
      'android'   — mimic ServerViewModel/MonitorViewModel: FF00-adv OR named.
                    Kept for parity, but note the FF00 clause is effectively
                    dead — firmware only advertises AA00, so those apps only
                    match by name.
      'nav'       — strict: only devices advertising FF00 (unlikely to match).
    """
    log(f"Starting BLE scan for {timeout:.0f}s (filter={filter_mode})...")
    hint = _mac_hint()
    if hint:
        log(hint)

    seen: dict[str, SeenDevice] = {}
    t0 = time.monotonic()

    def cb(device: BLEDevice, adv: AdvertisementData) -> None:
        addr = device.address
        entry = seen.get(addr)
        is_new = entry is None
        if is_new:
            entry = SeenDevice(
                address=addr,
                name=None,
                first_seen_ms=(time.monotonic() - t0) * 1000.0,
            )
            seen[addr] = entry

        entry.adv_count += 1
        if adv.rssi is not None:
            entry.rssis.append(adv.rssi)
        # bleak coalesces local_name into adv.local_name; some backends only
        # fill device.name once macOS' cache warms up (which is often after
        # scan-response arrives).
        new_name = adv.local_name or device.name
        if new_name and entry.name != new_name:
            entry.name = new_name

        for u in (adv.service_uuids or []):
            entry.service_uuids.add(u.lower())
        for u, blob in (adv.service_data or {}).items():
            entry.service_data[u.lower()] = bytes(blob).hex()
        for cid, blob in (adv.manufacturer_data or {}).items():
            entry.manufacturer_data[cid] = bytes(blob).hex()
        if adv.tx_power is not None:
            entry.tx_power = adv.tx_power

        if is_new:
            marker = ""
            if entry.advertises_nav:
                marker = " << advertises FF00"
            elif entry.name:
                marker = " << has name"
            log(f"  New:  {(entry.name or '<no name>'):<24s} id={addr}  RSSI={adv.rssi}{marker}")

    # Active scanning is required to pull the scan-response payload — the
    # firmware puts the device name ("BoatWatch") in the scan response
    # because the 128-bit AA00 UUID already fills the 31-byte adv envelope.
    # macOS CoreBluetooth in particular will not surface the local_name at
    # all under passive scanning, so Android sees a name and bleak does not.
    async with BleakScanner(detection_callback=cb, scanning_mode="active"):
        await asyncio.sleep(timeout)

    # Filter AFTER collecting everything so we can also report what was dropped.
    if filter_mode == "bw":
        kept = {a: d for a, d in seen.items() if d.advertises_bw}
    elif filter_mode == "nav":
        kept = {a: d for a, d in seen.items() if d.advertises_nav}
    elif filter_mode == "android":
        kept = {a: d for a, d in seen.items() if d.matches_android_picker()}
    else:
        kept = dict(seen)

    log(f"Scan complete: {len(seen)} advertiser(s) seen; {len(kept)} pass filter '{filter_mode}'.")
    _print_scan_table(kept)

    # Diagnostic: if the user asked for a nav filter but got nothing, dump
    # the devices that were dropped so they can spot the near-match.
    if filter_mode != "none" and len(kept) < len(seen):
        dropped = {a: d for a, d in seen.items() if a not in kept}
        log(f"({len(dropped)} device(s) filtered out — pass --filter=none to see them.)")

    return kept


def _print_scan_table(devices: dict) -> None:
    if not devices:
        print("  (no devices)")
        return
    # Sort strongest-signal first so the target you're standing next to floats up.
    def key(item):
        d = item[1]
        return -(statistics.mean(d.rssis) if d.rssis else -999)
    print()
    print(f"  {'name':<24s} {'address (or CB UUID on macOS)':<40s} {'RSSI':>5s}  services / notes")
    print(f"  {'-'*24} {'-'*40} {'-'*5}  {'-'*40}")
    for addr, d in sorted(devices.items(), key=key):
        rssi_mean = statistics.mean(d.rssis) if d.rssis else float("nan")
        svc_short = ",".join(sorted(short_uuid(u) for u in d.service_uuids)) or "-"
        tags = []
        if d.advertises_nav: tags.append("NAV(FF00)")
        if d.advertises_bw:  tags.append("BW(AA00)")
        if d.manufacturer_data: tags.append(f"mfr={len(d.manufacturer_data)}")
        if d.service_data:      tags.append(f"svcdata={len(d.service_data)}")
        if d.tx_power is not None: tags.append(f"txpwr={d.tx_power}")
        tag_str = " ".join(tags)
        print(f"  {(d.name or '<no name>'):<24s} {addr:<40s} {rssi_mean:>5.0f}  "
              f"svcs=[{svc_short}] {tag_str}")
    # Detailed dump for the (at most) 5 most-promising devices — enough context
    # to explain why an app matches / mismatches them.  Firmware advertises
    # AA00 (BoatWatch service), so that is our primary "is this the bridge?"
    # signal; FF00 advertisement is not expected.
    promising = sorted(
        devices.values(),
        key=lambda d: (not d.advertises_bw, not d.advertises_nav, not d.name,
                       -(statistics.mean(d.rssis) if d.rssis else -999)),
    )[:5]
    for d in promising:
        print()
        print(f"  -- {d.name or '<no name>'} ({d.address}) --")
        print(f"     adv_count={d.adv_count}  first_seen_ms={d.first_seen_ms:.0f}  "
              f"tx_power={d.tx_power}")
        if d.rssis:
            print(f"     rssi: n={len(d.rssis)} min={min(d.rssis)} "
                  f"mean={statistics.mean(d.rssis):.0f} max={max(d.rssis)}")
        if d.service_uuids:
            for u in sorted(d.service_uuids):
                mark = ""
                if u == NAV_SERVICE_UUID: mark = "  << NAV DATA"
                elif u == BW_SERVICE_UUID: mark = "  << BoatWatch"
                print(f"     service uuid: {u}  ({short_uuid(u)}){mark}")
        else:
            print("     service uuid: <none advertised>")
        for u, hx in d.service_data.items():
            print(f"     service data [{short_uuid(u)}]: {hx}")
        for cid, hx in d.manufacturer_data.items():
            print(f"     manufacturer data [0x{cid:04x}]: {hx}")


# ---------- diagnostic ----------

@dataclass
class DiagResult:
    connected: bool = False
    connect_ms: float = 0.0
    discover_ms: float = 0.0
    services: list = field(default_factory=list)          # (uuid, [(char_uuid, props)])
    nav_service_found: bool = False
    bw_service_found: bool = False
    negotiated_mtu: Optional[int] = None
    notifications: int = 0        # legacy: FF01-only count
    nav_frames: int = 0           # FF01 notifications
    engine_frames: int = 0        # FF02 notifications
    battery_frames: int = 0       # AA03 notifications
    auth_ok: Optional[bool] = None
    error: Optional[str] = None

async def diagnostic(
    address: str,
    requested_mtu: int,
    subscribe: bool,
    subscribe_seconds: float,
    auth_pin: Optional[str],
) -> DiagResult:
    """One connect → MTU → discover → (optional subscribe/auth) cycle."""
    r = DiagResult()

    log(f"Connecting to {address} ...")
    t0 = time.monotonic()
    # bleak requests MTU internally on Linux/BlueZ; on macOS/Windows the MTU
    # is negotiated automatically. We pass mtu_size where supported and print
    # what actually got negotiated so mismatches versus the Android request
    # (64 diag / 256 production) are visible.
    try:
        client = BleakClient(address, timeout=15.0)
        await client.connect()
    except Exception as e:
        r.error = f"connect failed: {e}"
        log(f"ERROR: {r.error}")
        return r

    r.connect_ms = (time.monotonic() - t0) * 1000.0
    r.connected = True
    log(f"CONNECTED in {r.connect_ms:.0f} ms")

    try:
        # MTU negotiation. On some backends the exchange happens implicitly on
        # connect; on Linux we can force it. Don't fail hard if unavailable.
        try:
            if hasattr(client, "_backend") and hasattr(client._backend, "exchange_mtu"):
                await client._backend.exchange_mtu(requested_mtu)
        except Exception as e:
            log(f"  (mtu exchange not supported on this backend: {e})")
        try:
            r.negotiated_mtu = client.mtu_size
            log(f"MTU: requested={requested_mtu} negotiated={r.negotiated_mtu}")
        except Exception:
            log(f"MTU: requested={requested_mtu} negotiated=<unknown>")

        # Discover services.
        log("Discovering services...")
        t1 = time.monotonic()
        services = client.services  # already populated on connect for bleak >= 0.20
        r.discover_ms = (time.monotonic() - t1) * 1000.0

        svc_list = list(services)
        log(f"Discovered {len(svc_list)} service(s) in {r.discover_ms:.0f} ms:")
        for svc in svc_list:
            annot = ""
            u = svc.uuid.lower()
            if u == NAV_SERVICE_UUID:
                annot = "   << NAV DATA (FF00)"
                r.nav_service_found = True
            elif u == BW_SERVICE_UUID:
                annot = "   << BoatWatch (AA00)"
                r.bw_service_found = True
            chars = []
            log(f"  Service: {short_uuid(svc.uuid)}{annot}")
            for ch in svc.characteristics:
                p = props_str(ch)
                chars.append((ch.uuid, p))
                log(f"    Char: {short_uuid(ch.uuid)}  [{p}]")
            r.services.append((svc.uuid, chars))

        if r.nav_service_found:
            log("SUCCESS: Nav Data service (FF00) present.")
        else:
            log("FAIL: Nav Data service (FF00) NOT present.")

        # Optional: subscribe to nav (FF01), engine (FF02) and battery (AA03)
        # and print every received frame. Mirrors BleNmeaSource behaviour:
        # nav is subscribed unconditionally; engine and battery require the
        # BoatWatch service to be present (and, on production firmware,
        # authenticated first).
        if subscribe:
            # Auth first if requested. BleNmeaSource authenticates before
            # subscribing engine/battery — see BleNmeaSource.kt:330-335.
            if auth_pin is not None and r.bw_service_found:
                await _run_auth(client, auth_pin, log_line=log, result=r)
                if r.auth_ok is False:
                    log("  auth rejected — engine/battery notifies will still be attempted, "
                        "but firmware may refuse them until auth succeeds.")

            await _subscribe_streams(client, r, subscribe_seconds)

    except Exception as e:
        r.error = f"post-connect failure: {e}"
        log(f"ERROR: {r.error}")
    finally:
        try:
            await client.disconnect()
            log("Disconnected.")
        except Exception as e:
            log(f"disconnect issue: {e}")

    return r


def _fmt_nav_frame(data: bytes) -> str:
    """29-byte binary nav frame from FF01 (magic 0xCC). See BleNmeaSource.processBinaryFrame."""
    if len(data) == 29 and data[0] == 0xCC:
        # Print a compact hex dump + a note that the magic/length looks right.
        return f"NAV[29] {data.hex(' ')}    (magic 0xCC OK)"
    return f"NAV[{len(data)}] {data.hex(' ')}    (fragment — will be accumulated on Android)"

def _fmt_engine_frame(data: bytes) -> str:
    """Engine telemetry on FF02 — variable per firmware; print raw."""
    return f"ENG[{len(data)}] {data.hex(' ')}"

def _fmt_battery_frame(data: bytes) -> str:
    """BMS on AA03 — variable per firmware; print raw."""
    return f"BAT[{len(data)}] {data.hex(' ')}"


async def _subscribe_streams(client: BleakClient, result: DiagResult, seconds: float) -> None:
    """Subscribe to nav (FF01), engine (FF02) and battery (AA03) and print every frame.

    Each characteristic is optional — start_notify may fail if the characteristic
    doesn't exist on this firmware build, or (on AA03/FF02) if auth is required
    and hasn't happened yet. Failures are logged, not fatal."""

    # Track first-seen and cadence per stream so the summary at the end is
    # useful for spotting "notified once then went silent" firmware bugs.
    stats = {
        "nav":     {"n": 0, "bytes": 0, "first_ms": None, "last_ms": None},
        "engine":  {"n": 0, "bytes": 0, "first_ms": None, "last_ms": None},
        "battery": {"n": 0, "bytes": 0, "first_ms": None, "last_ms": None},
    }
    t0 = time.monotonic()

    def make_cb(kind: str, fmt):
        def cb(_char, data: bytearray) -> None:
            s = stats[kind]
            now_ms = (time.monotonic() - t0) * 1000.0
            if s["first_ms"] is None:
                s["first_ms"] = now_ms
            s["last_ms"] = now_ms
            s["n"] += 1
            s["bytes"] += len(data)
            log(f"  +{now_ms:>6.0f}ms  {fmt(bytes(data))}")
        return cb

    subs = [
        ("nav",     NAV_NOTIFY_UUID,   make_cb("nav",     _fmt_nav_frame)),
        ("engine",  ENGINE_STATE_UUID, make_cb("engine",  _fmt_engine_frame)),
        ("battery", BW_BATTERY_UUID,   make_cb("battery", _fmt_battery_frame)),
    ]

    started: list[str] = []
    for kind, uuid, cb in subs:
        try:
            await client.start_notify(uuid, cb)
            log(f"  subscribed to {kind} ({short_uuid(uuid)})")
            started.append(uuid)
        except Exception as e:
            log(f"  start_notify({kind} / {short_uuid(uuid)}) failed: {e}")

    if not started:
        log("  no notify subscriptions succeeded — nothing to observe.")
        return

    log(f"Listening for {seconds:.0f}s ...")
    await asyncio.sleep(seconds)

    for uuid in started:
        try:
            await client.stop_notify(uuid)
        except Exception:
            pass

    # Roll stats into the result and print a per-stream summary.
    result.nav_frames     = stats["nav"]["n"]
    result.engine_frames  = stats["engine"]["n"]
    result.battery_frames = stats["battery"]["n"]
    result.notifications  = result.nav_frames  # keep legacy field meaningful

    log("Per-stream summary:")
    for kind in ("nav", "engine", "battery"):
        s = stats[kind]
        if s["n"] == 0:
            log(f"  {kind:<8s}: (no frames)")
            continue
        span = ((s["last_ms"] - s["first_ms"]) / 1000.0) if s["last_ms"] and s["first_ms"] else 0.0
        rate = (s["n"] - 1) / span if span > 0 else 0.0
        log(f"  {kind:<8s}: {s['n']} frames, {s['bytes']} bytes, "
            f"first@{s['first_ms']:.0f}ms last@{s['last_ms']:.0f}ms  "
            f"rate={rate:.2f} Hz")


async def _run_auth(client: BleakClient, pin_str: str, log_line, result: DiagResult) -> None:
    """Emulate the production PIN auth exactly as BleNmeaSource.sendAuthCommand does:
    subscribe AA01, write [0xAA, 0xF0, p0, p1, p2, p3] to AA02 where p0..p3 are the
    *ASCII bytes* of the PIN string (e.g. "0000" -> 0x30 0x30 0x30 0x30). Missing
    positions are padded with ASCII '0' — see BleNmeaSource.kt:524-530.

    Uses a write-WITH-response (WRITE_TYPE_DEFAULT on Android). Some firmware paths
    only wire up the ATT handler for one write type, so this matters."""
    pin_ascii = pin_str.encode("ascii", errors="strict")
    # Android pads/truncates to exactly 4 bytes, filling missing slots with ASCII '0'.
    pad = ord("0")
    p = [pin_ascii[i] if i < len(pin_ascii) else pad for i in range(4)]

    got_response = asyncio.Event()

    def aa01_cb(_ch, data: bytearray) -> None:
        if len(data) >= 2 and data[0] == MAGIC_AUTH_RESP:
            ok = data[1] == 0x01
            result.auth_ok = ok
            log_line(f"  AA01 auth response: {'OK' if ok else 'REJECTED'} ({data.hex(' ')})")
            got_response.set()
        else:
            log_line(f"  AA01 notify: {data.hex(' ')}")

    try:
        await client.start_notify(BW_AUTOPILOT_UUID, aa01_cb)
    except Exception as e:
        log_line(f"  start_notify(AA01) failed: {e}")
        return

    frame = bytes([MAGIC_AUTOPILOT, CMD_AUTH, p[0], p[1], p[2], p[3]])
    log_line(f"  writing auth frame to AA02: {frame.hex(' ')}  "
             f"(pin '{pin_str}' as ASCII: {bytes(p).hex(' ')})")
    try:
        # WRITE_TYPE_DEFAULT on Android == write-with-response. bleak's
        # response=True matches; response=False is write-without-response.
        await client.write_gatt_char(BW_COMMAND_UUID, frame, response=True)
    except Exception as e:
        log_line(f"  auth write failed: {e}")
        return

    try:
        await asyncio.wait_for(got_response.wait(), timeout=3.0)
    except asyncio.TimeoutError:
        log_line("  auth timed out (no AA01 response within 3s)")


# ---------- picking a target device from scan ----------

async def pick_device(name_substr: Optional[str], filter_mode: str,
                      scan_timeout: float) -> Optional[str]:
    devices = await scan(scan_timeout, filter_mode)
    if not devices:
        return None
    if name_substr:
        needle = name_substr.lower()
        for addr, d in devices.items():
            if d.name and needle in d.name.lower():
                log(f"Selected {d.name} ({addr}) by --name '{name_substr}'")
                return addr
        log(f"No device name contains '{name_substr}'.")
        return None
    bw_devices = [a for a, d in devices.items() if d.advertises_bw]
    if len(bw_devices) == 1:
        addr = bw_devices[0]
        log(f"Selected {devices[addr].name or 'Unknown'} ({addr}) — only AA00 advertiser")
        return addr
    if len(bw_devices) > 1:
        log(f"Multiple AA00 advertisers found ({len(bw_devices)}) — specify --address or --name.")
        return None
    log("No AA00 (BoatWatch) advertiser found — specify --address or --name, "
        "or use --filter=none to see everything.")
    return None


async def _rescan_for_address(prev_address: str, name_substr: Optional[str],
                              timeout: float) -> Optional[str]:
    """Refresh the OS BLE cache by re-observing advertisements.

    Called from --forever mode when connect has failed repeatedly. On macOS
    a stale CoreBluetooth peripheral cache is a common failure mode after
    many connect/disconnect cycles — the BleakClient(<CB UUID>) lookup fails
    with 'Device not found' until CoreBluetooth re-observes the advertisement.

    Returns the (possibly new) address to reconnect with, or None if the
    device wasn't seen. Prefers to match by name substring when provided so
    a CoreBluetooth UUID reshuffle doesn't strand us on a dead identifier.
    """
    devices = await scan(timeout, filter_mode="none")  # accept anything; we filter here
    if not devices:
        return None

    # 1) Exact-address match (fast path when CB UUID / MAC is stable).
    if prev_address in devices:
        return prev_address

    # 2) Name-substring match (falls through when CB reshuffled the UUID).
    if name_substr:
        needle = name_substr.lower()
        for addr, d in devices.items():
            if d.name and needle in d.name.lower():
                return addr

    # 3) Sole AA00 advertiser — safe assumption given the firmware only
    #    advertises the BoatWatch service.
    bw_devices = [a for a, d in devices.items() if d.advertises_bw]
    if len(bw_devices) == 1:
        return bw_devices[0]

    return None


# ---------- main ----------

def summarize(results: list[DiagResult]) -> None:
    print()
    print("=" * 60)
    print(f"Summary over {len(results)} run(s):")
    ok = sum(1 for r in results if r.connected and not r.error and r.nav_service_found)
    print(f"  fully-successful runs: {ok}/{len(results)}")
    connect_fails = sum(1 for r in results if not r.connected)
    print(f"  connect failures:      {connect_fails}")
    discover_fails = sum(1 for r in results if r.connected and not r.nav_service_found)
    print(f"  discovery/FF00 misses: {discover_fails}")
    connect_times = [r.connect_ms for r in results if r.connected]
    if connect_times:
        print(f"  connect ms  min/median/max: "
              f"{min(connect_times):.0f} / {statistics.median(connect_times):.0f} / "
              f"{max(connect_times):.0f}")
    discover_times = [r.discover_ms for r in results if r.connected]
    if discover_times:
        print(f"  discover ms min/median/max: "
              f"{min(discover_times):.0f} / {statistics.median(discover_times):.0f} / "
              f"{max(discover_times):.0f}")
    for kind, attr in (("nav", "nav_frames"),
                       ("engine", "engine_frames"),
                       ("battery", "battery_frames")):
        counts = [getattr(r, attr) for r in results if getattr(r, attr)]
        if counts:
            print(f"  {kind:<7s} frames per run: "
                  f"min={min(counts)} median={statistics.median(counts):.0f} max={max(counts)}")
    auth_runs = [r for r in results if r.auth_ok is not None]
    if auth_runs:
        okc = sum(1 for r in auth_runs if r.auth_ok)
        print(f"  auth handshakes:            {okc}/{len(auth_runs)} accepted")
    print("=" * 60)


async def main_async(args) -> int:
    if args.scan_only or (not args.address and not args.name):
        await scan(args.scan_timeout, args.filter)
        return 0

    address = args.address
    if not address:
        address = await pick_device(args.name, args.filter, args.scan_timeout)
        if not address:
            log("No target device selected — aborting.")
            return 1

    # --forever loops until Ctrl-C; each iteration performs a full
    # connect → subscribe → disconnect cycle, so the firmware sees a real
    # BLE drop between cycles (the peripheral's on_disconnect handler
    # runs, its client slot is freed, and advertising restarts). This is
    # the intended reconnect-storm test.
    #
    # macOS-specific caveat: BleakClient(<CoreBluetooth-UUID>).connect()
    # requires the peripheral to still be in CoreBluetooth's discovered-
    # peripherals cache. After many cycles bluetoothd may evict the entry
    # and every subsequent connect fails instantly with "Device ... was
    # not found" (bleak's default 15s connect timeout expires waiting for
    # a didConnect callback that never fires). The fix is to re-scan
    # before reconnecting so CoreBluetooth re-observes the advertisement.
    # We do this proactively after args.rescan_after consecutive failures.
    results: list[DiagResult] = []
    total = args.repeat if not args.forever else -1  # -1 == run forever
    i = 0
    consec_fail = 0
    consec_empty_rescans = 0     # rescans in a row that saw *no* candidate device
    firmware_advisory_shown = False
    try:
        while True:
            if total > 0 and i >= total:
                break

            if args.forever:
                log(f"--- Cycle {i + 1} (forever mode; Ctrl-C to stop) ---")
            elif total > 1:
                log(f"--- Run {i + 1}/{total} ---")

            if consec_fail >= args.rescan_after > 0:
                log(f"({consec_fail} consecutive connect failures — rescanning to "
                    f"refresh the CoreBluetooth cache before retrying)")
                new_address = await _rescan_for_address(
                    address, args.name, args.rescan_timeout
                )
                if new_address is None:
                    log("  rescan did not surface the device — retrying anyway.")
                    consec_empty_rescans += 1
                else:
                    if new_address != address:
                        log(f"  address changed after rescan: {address} → {new_address}")
                        address = new_address
                    else:
                        log("  device seen in advert stream — cache should be fresh.")
                    consec_empty_rescans = 0
                    firmware_advisory_shown = False
                consec_fail = 0

                # If several rescans in a row see nothing, the peripheral is
                # almost certainly not the client's fault — Mac's radio is
                # fine (it sees plenty of neighbouring devices), the target
                # simply isn't in the adv stream. On this firmware that's
                # the tell for the "advertising silently stopped" wedge that
                # only a peripheral soft-reset clears. Loud, one-shot advisory.
                if (consec_empty_rescans >= args.advisory_after
                        and not firmware_advisory_shown):
                    log("")
                    log("=" * 60)
                    log(f"ADVISORY: {consec_empty_rescans} rescans in a row saw NO matching")
                    log("peripheral, though other devices are visible. The BLE bridge")
                    log("firmware appears to have stopped advertising.")
                    log("")
                    log("Most likely cause: NimBLE resource exhaustion after many")
                    log("connect/disconnect cycles. Recovery: soft-reset the ESP32")
                    log("(or trigger it via the firmware's watchdog if enabled).")
                    log("")
                    log("This tool will keep retrying; further empty rescans will not")
                    log("re-emit this advisory until the peripheral is seen again.")
                    log("=" * 60)
                    log("")
                    firmware_advisory_shown = True

            r = await diagnostic(
                address=address,
                requested_mtu=args.mtu,
                subscribe=args.subscribe,
                subscribe_seconds=args.subscribe_seconds,
                auth_pin=args.auth_pin,
            )
            results.append(r)
            i += 1

            # Track connect-fail streak for the rescan trigger. A run
            # that connects but fails later (e.g. auth rejected) still
            # counts as "cache is fine" and resets the streak. A successful
            # connection also resets the empty-rescan streak / advisory
            # latch — clearly the peripheral is back.
            if r.connected:
                consec_fail = 0
                consec_empty_rescans = 0
                firmware_advisory_shown = False
            else:
                consec_fail += 1

            # Rolling summary every N iterations so a long run stays useful
            # even before you Ctrl-C.
            if args.forever and args.summary_every > 0 and i % args.summary_every == 0:
                log(f"(rolling summary after {i} cycle(s))")
                summarize(results)

            # Cap the retained result set so this doesn't grow without
            # bound during multi-day runs; stats printed by summarize() are
            # already over "recent" only in that case, which is honest.
            if len(results) > 1000:
                dropped = len(results) - 1000
                del results[:dropped]

            still_going = args.forever or (i < total)
            if still_going:
                await asyncio.sleep(args.repeat_delay)
    except KeyboardInterrupt:
        log(f"Interrupted after {i} cycle(s).")

    if args.forever or total > 1:
        summarize(results)

    if not results:
        return 1
    last = results[-1]
    if last.error or not last.connected or not last.nav_service_found:
        return 1
    return 0


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Python port of the NmeaBridge Android 'BLE Diagnostic Test'.",
    )
    p.add_argument("--scan-only", action="store_true",
                   help="Just scan and list devices; do not connect.")
    p.add_argument("--address",
                   help="Target device address. On macOS this is a CoreBluetooth UUID; "
                        "on Linux/Windows it is a BD_ADDR.")
    p.add_argument("--name",
                   help="If --address is not given, pick the first scan hit whose "
                        "name contains this substring.")
    p.add_argument("--filter", choices=("bw", "android", "nav", "none"), default="bw",
                   help="Scan filter mode: "
                        "'bw' (default) — only devices advertising AA00 (what the firmware "
                        "actually broadcasts); "
                        "'android' — mimic ServerViewModel/MonitorViewModel (FF00-or-named); "
                        "'nav' — strict FF00 required (unlikely to match — firmware does "
                        "not advertise FF00); "
                        "'none' — list every advertiser.")
    p.add_argument("--scan-timeout", type=float, default=DEFAULT_SCAN_TIMEOUT,
                   help=f"Scan duration in seconds (default: {DEFAULT_SCAN_TIMEOUT:.0f}).")
    p.add_argument("--mtu", type=int, default=DEFAULT_MTU,
                   help=f"MTU to request after connect (default: {DEFAULT_MTU}; "
                        "production Android uses 256).")
    p.add_argument("--subscribe", action="store_true",
                   help="After discovery, subscribe to nav (FF01), engine (FF02) and "
                        "battery (AA03) notifications and print every frame received.")
    p.add_argument("--subscribe-seconds", type=float, default=10.0,
                   help="How long to observe notifications (default: 10).")
    p.add_argument("--auth-pin",
                   help="If set (and --subscribe), performs the BoatWatch auth handshake "
                        "(subscribe AA01, write [0xAA,0xF0, ASCII pin bytes] to AA02) "
                        "matching BleNmeaSource.sendAuthCommand. The PIN is sent as its "
                        "ASCII bytes, padded to 4 chars with '0' — '0000' goes on the "
                        "wire as 0x30 0x30 0x30 0x30, not 0x00 0x00 0x00 0x00.")
    p.add_argument("--repeat", type=int, default=1,
                   help="Repeat the connect/discover cycle N times to catch flakiness.")
    p.add_argument("--repeat-delay", type=float, default=2.0,
                   help="Seconds between cycles — the peripheral is DISCONNECTED during "
                        "this window, so raising it lengthens each simulated outage. "
                        "Default: 2.")
    p.add_argument("--forever", action="store_true",
                   help="Run the connect/subscribe/disconnect cycle forever until "
                        "Ctrl-C. Each cycle is a genuine BLE drop-and-reconnect from "
                        "the firmware's point of view — the peripheral sees "
                        "on_disconnect and its slot is freed. Overrides --repeat.")
    p.add_argument("--summary-every", type=int, default=10,
                   help="In --forever mode, print a rolling summary every N cycles "
                        "(0 disables). Default: 10.")
    p.add_argument("--rescan-after", type=int, default=3,
                   help="After N consecutive connect failures, re-scan for the device "
                        "to refresh the OS BLE cache before the next attempt. This is "
                        "the standard workaround for macOS CoreBluetooth evicting stale "
                        "peripheral entries after many connect/disconnect cycles "
                        "(Android is not affected). Set 0 to disable. Default: 3.")
    p.add_argument("--rescan-timeout", type=float, default=15.0,
                   help="Seconds to scan when refreshing the cache (default: 15). "
                        "Needs to be long enough to catch an advert cycle even when "
                        "the peripheral has active connections and its adv duty cycle "
                        "has dropped.")
    p.add_argument("--advisory-after", type=int, default=3,
                   help="After N consecutive rescans see nothing (while other BLE "
                        "advertisers ARE being seen), print a loud advisory pointing "
                        "at firmware-side advertising-silence. Default: 3.")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    try:
        return asyncio.run(main_async(args))
    except KeyboardInterrupt:
        return 130
    except BleakError as e:
        sys.stderr.write(f"bleak error: {e}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
