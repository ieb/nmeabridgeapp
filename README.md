# NMEA Bridge

Android app for Chromebooks that reads NMEA0183 data from an external Bluetooth GPS receiver (or a built-in simulator) and re-broadcasts it over a TCP server port. Other Android apps on the same Chromebook can connect to the TCP port and receive the NMEA stream.

## Features

- **Bluetooth GPS mode** — connects to an external Bluetooth SPP GPS receiver, reads raw NMEA sentences, and relays them to TCP clients
- **Simulator mode** — generates realistic NMEA sentences (GGA, RMC, GSA, VTG) at 1 Hz with a moving position, no hardware required
- **Multi-client TCP server** — multiple apps can connect simultaneously on port 10110 (configurable)
- **Foreground service** — keeps streaming while the app is in the background

## Requirements

- Android 8.0+ (API 26+)
- For Bluetooth mode: a Bluetooth Classic (SPP) GPS receiver (e.g., Bad Elf, Garmin GLO, generic NMEA puck)
- BLE-only GPS devices are not recommended on Chromebooks due to known ARC++ compatibility issues

## Building

### Prerequisites

- [Android SDK](https://developer.android.com/studio) with platform 35 and build tools installed
- JDK 17+

### Build

```bash
# Clone the repo
git clone <repo-url> && cd NmeaBridge

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

```bash
./gradlew installDebug
# or
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch **NMEA Bridge** on your Chromebook
2. Select a source:
   - **Simulator** — no setup needed, generates fake moving GPS data
   - **Bluetooth GPS** — select a paired Bluetooth GPS device from the dropdown
3. Set the TCP port (default: 10110)
4. Tap **Start Server**
5. Connect from another Android app using TCP to `localhost:10110`

## Connecting from other apps

Other Android apps on the same Chromebook can connect via:

- **Host:** `localhost` or the Android container IP (typically `100.115.92.x`, shown in the app UI)
- **Port:** 10110 (default, configurable)
- **Protocol:** Raw TCP — each line is a complete NMEA0183 sentence terminated by `\r\n`

Example using a TCP client:
```
$ nc localhost 10110
$GPGGA,142356.00,4736.3724,N,12219.9326,W,1,08,1.2,25.0,M,,M,,*6A
$GPRMC,142356.00,A,4736.3724,N,12219.9326,W,5.0,90.0,100426,,,A*5B
$GPGSA,A,3,02,05,09,12,15,18,21,24,,,,,,1.8,1.2,1.3*3D
$GPVTG,90.0,T,,M,5.0,N,9.3,K,A*10
```

## Permissions

| Permission | When | Purpose |
|---|---|---|
| `INTERNET` | Always | TCP server socket |
| `BLUETOOTH_CONNECT` | Bluetooth mode (API 31+) | Connect to paired GPS device |
| `BLUETOOTH_SCAN` | Bluetooth mode (API 31+) | Discover GPS devices |
| `ACCESS_FINE_LOCATION` | Bluetooth mode (API < 31) | Required for BT scan on older APIs |
| `FOREGROUND_SERVICE` | Always | Keep server running in background |
| `WAKE_LOCK` | Always | Prevent CPU sleep while streaming |
| `POST_NOTIFICATIONS` | API 33+ | Foreground service notification |

Simulator mode requires only `INTERNET`, `FOREGROUND_SERVICE`, and `WAKE_LOCK` — all granted automatically at install time.

## Chromebook notes

- Most Chromebooks lack built-in GPS hardware. Use an external Bluetooth GPS receiver or the simulator.
- Stick to **Bluetooth Classic (SPP)** GPS devices. BLE GPS receivers have known issues on Chromebooks.
- Other **Android apps** on the same Chromebook can connect directly via `localhost`. **Linux (Crostini) apps** cannot reach the Android network without port forwarding.

## Architecture

```
  Bluetooth GPS (SPP)          Simulator
        |                         |
        v                         v
  BluetoothGpsSource      SimulatorNmeaSource
        |                         |
        +-----> SharedFlow <------+
                    |
              NmeaTcpServer
                    |
            +-------+-------+
            |       |       |
         Client  Client  Client
```

Data flows one direction: NMEA source -> SharedFlow -> TCP server -> connected clients. Each client gets its own coroutine that independently collects from the SharedFlow.

## Project structure

```
app/src/main/java/com/example/nmeabridge/
  nmea/           NmeaSource interface, NmeaChecksum
  bluetooth/      BluetoothGpsSource, BluetoothDeviceSelector
  simulator/      SimulatorNmeaSource, NmeaSentenceBuilder
  server/         NmeaTcpServer (coroutine-per-client)
  service/        NmeaForegroundService, ServiceState
  ui/             MainActivity, ServerScreen, ServerViewModel
```

## License

MIT
