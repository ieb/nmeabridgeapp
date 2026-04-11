# NMEA Bridge

Android app for Chromebooks that receives navigation data from a BLE device using a compact binary protocol, converts it to NMEA0183 sentences, and re-broadcasts over a TCP server port. Other Android apps on the same Chromebook can connect to the TCP port and receive the NMEA stream. Also supports Bluetooth Classic SPP GPS receivers and a built-in simulator.

## Features

- **BLE Nav mode** — connects to a BLE device running firmware compliant with the [binary transport protocol](./doc/ble-transport.md). A single 29-byte notification per second carries position, heading, speed, depth, wind, and log data.
- **Bluetooth Classic mode** — connects to a Bluetooth SPP GPS receiver, forwards raw NMEA sentences to TCP clients
- **Simulator mode** — generates NMEA sentences (DBT, GGA, GLL, RMC, VTG, ZDA) at 1 Hz with a moving position, no hardware required
- **Navigation display** — main screen shows decoded data in nautical units (knots, degrees, metres, Nm)
- **Multi-client TCP server** — multiple apps can connect simultaneously on port 10110 (configurable)
- **Foreground service** — keeps streaming while the app is in the background
- **Auto-start** — remembers the last used BLE Nav device and reconnects on launch

## Requirements

- Android 8.0+ (API 26+)
- BLE Nav mode requires custom firmware (see N2KNMEA0183Wifi). Some older Chromebooks may not support BLE — use the BLE Test on the settings screen to verify.
- For Bluetooth Classic mode: a Bluetooth SPP GPS receiver (e.g., Bad Elf, Garmin GLO, generic NMEA puck)

## Building

### Prerequisites

- [Android SDK](https://developer.android.com/studio) with platform 35 and build tools installed
- JDK 17+

### Build

```bash
# Clone the repo
git clone https://github.com/ieb/nmeabridgeapp.git && cd nmeabridgeapp

# Set SDK location (if ANDROID_HOME is not set)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Build debug APK
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Run tests

```bash
./gradlew test
```

### Install on device

Android 11 will ADB Wifi Pair, but once that is done the device does not appear in the list of available devices. Connect is required also.

Developer Options -> ADB over Wifi -> Pair displays a dedicated port and pin for pairing and

```bash
adb pair <ip>:<port>
```
Once paired using the port shown on the ADB over Wifi Screen, which is static

```bash
adb connect <ip>:<main port>
```

Confirm the device is listed

```bash
adb devices -l
```

Install, using -s to specify the device if you have more than 1 listed

```bash
./gradlew installDebug
# or
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Retrieving logs

With an ADB connection established, fetch logs for the app:

```bash
# Dump recent logs for the app process
adb logcat -d --pid=$(adb shell pidof uk.co.tfd.nmeabridge)

# Save to a file (last 500 lines)
adb logcat -d --pid=$(adb shell pidof uk.co.tfd.nmeabridge) | tail -500 > logcat.txt

# Stream logs live (Ctrl+C to stop)
adb logcat --pid=$(adb shell pidof uk.co.tfd.nmeabridge)

# Filter to Bluetooth-related entries only
adb logcat -d --pid=$(adb shell pidof uk.co.tfd.nmeabridge) | grep -i bluetooth
```

If using multiple ADB devices, add `-s <device>` after `adb`, e.g. `adb -s 192.168.1.117:40161 logcat ...`


## Usage

1. Launch **NMEA Bridge** on your Chromebook
2. Tap **Settings** on the navigation screen
3. Select a source:
   - **BLE Nav** — scan for and select a BLE navigation device
   - **BT Classic** — select a paired Bluetooth SPP GPS device
   - **Simulator** — no setup needed, generates fake moving GPS data
4. Set the TCP port (default: 10110)
5. Tap **Start Server** — the app returns to the navigation display showing live data
6. On subsequent launches, the app auto-connects to the last used BLE Nav device

## Connecting from other apps

Other Android apps on the same Chromebook can connect via:

- **Host:** `localhost` or the Android container IP (typically `100.115.92.x`, shown in the app UI)
- **Port:** 10110 (default, configurable)
- **Protocol:** Raw TCP — each line is a complete NMEA0183 sentence terminated by `\r\n`

Example using a TCP client:
```
$ nc localhost 10110
$SDDBT,41.0,f,12.5,M,6.8,F*0B
$GPGGA,142356.00,5004.2000,N,00930.0000,W,1,08,1.0,0.0,M,,M,,*6E
$GPGLL,5004.2000,N,00930.0000,W,142356.00,A,A*7E
$GPRMC,142356.00,A,5004.2000,N,00930.0000,W,5.0,90.0,110426,0.5,E,A*18
$GPVTG,90.0,T,89.5,M,5.0,N,9.3,K,A*21
$GPZDA,142356.00,11,04,2026,00,00*6C
```

## Permissions

| Permission | When | Purpose |
|---|---|---|
| `INTERNET` | Always | TCP server socket |
| `BLUETOOTH` | Always | Bluetooth access |
| `BLUETOOTH_CONNECT` | API 31+ | Connect to BLE/BT devices |
| `BLUETOOTH_SCAN` | API 31+ | Discover BLE devices |
| `ACCESS_FINE_LOCATION` | API < 31 | Required for BLE scan on older APIs |
| `FOREGROUND_SERVICE` | Always | Keep server running in background |
| `WAKE_LOCK` | Always | Prevent CPU sleep while streaming |
| `POST_NOTIFICATIONS` | API 33+ | Foreground service notification |

Simulator mode requires only `INTERNET`, `FOREGROUND_SERVICE`, and `WAKE_LOCK` — all granted automatically at install time.

## Chromebook notes

- Most Chromebooks lack built-in GPS hardware. Use BLE Nav, an external Bluetooth GPS receiver, or the simulator.
- Other **Android apps** on the same Chromebook can connect directly via `localhost`. **Linux (Crostini) apps** cannot reach the Android network without port forwarding.
- The Android container IP (typically `100.115.92.x`) is generally stable across reboots but may change after ChromeOS updates.

## Architecture

```
  BLE Nav Device        BT Classic GPS       Simulator
        |                     |                  |
        v                     v                  v
  BleNmeaSource      BluetoothGpsSource  SimulatorNmeaSource
   (binary 0xCC)       (NMEA text)        (NMEA text)
        |                     |                  |
        +--------> SharedFlow <---------+--------+
                       |
                 NmeaTcpServer
                       |
               +-------+-------+
               |       |       |
            Client  Client  Client

  BleNmeaSource also exposes NavigationState
  for the navigation display screen.
```

Data flows one direction: NMEA source -> SharedFlow -> TCP server -> connected clients. Each client gets its own coroutine that independently collects from the SharedFlow. The BLE Nav source decodes the binary protocol and generates NMEA sentences for TCP clients while also providing structured navigation data to the UI.

## Project structure

```
app/src/main/java/uk/co/tfd/nmeabridge/
  nmea/           NmeaSource, NmeaChecksum, NavigationState, BinaryProtocol
  bluetooth/      BleNmeaSource, BluetoothGpsSource, BluetoothDeviceSelector
  simulator/      SimulatorNmeaSource, NmeaSentenceBuilder
  server/         NmeaTcpServer (coroutine-per-client)
  service/        NmeaForegroundService, ServiceState
  ui/             MainActivity, NavigationScreen, ServerScreen, BleTestScreen, ViewModels
  ui/theme/       Dark Material3 theme
simulator/        Python BLE simulator for testing (macOS, uses PyObjC CoreBluetooth)
doc/              BLE binary transport protocol specification
```

## BLE Simulator

A Python BLE simulator is included for testing without hardware:

```bash
cd simulator/
uv run nmea_ble_sim.py --name "NMEA GPS" --lat 50.07 --lon -9.50
```

This advertises a BLE GATT service on macOS that sends the binary navigation protocol at 1 Hz. The Android app can discover and connect to it via the BLE Nav source.

## License

MIT
