# 🐛 BugCam

BugCam turns an Android phone into a simple, unattended LAN camera.

It was built for fixed installations where a phone remains powered, streams over Wi-Fi, and may need to recover remotely without somebody physically interacting with it.

BugCam was originally developed for a terrarium camera using a Google Pixel 7 Pro.

## Features

- Rear-camera capture
- MJPEG stream over HTTP
- Full-resolution JPEG snapshots
- JSON health/status endpoint
- Camera foreground service
- Continues operating with the display off
- Android Direct Boot support
- Remote launch using ADB after reboot
- Continuous autofocus
- Manual focus lock
- Persistent focus lock across app/device restarts
- Torch on/off control
- Adjustable torch strength on supported devices
- Battery and thermal reporting
- Camera recovery/watchdog logic
- Configurable resolution, frame rate and JPEG quality
- Small built-in status page

## Tested configuration

BugCam has been tested on:

- Google Pixel 7 Pro
- Android 17 / API 37
- Rear camera
- 1920×1080
- 15 fps
- Wi-Fi LAN streaming
- Continuous mains power

Other Android devices may work, but have not been tested to the same extent.

## Requirements

For building:

- JDK 17
- Android SDK 37
- Linux, macOS or Windows
- Gradle wrapper included

The app targets Android API 37 and has a minimum SDK of 28.

## Build

Clone the repository:

    git clone https://github.com/splargdotcom/BugCam.git
    cd BugCam

Create `local.properties` if your Android SDK is not automatically detected:

    sdk.dir=/path/to/Android/Sdk

Build with Gradle:

    ./gradlew assembleDebug

Or on Ubuntu/Linux:

    ./scripts/build-ubuntu.sh

## Install

Install with ADB:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Launch BugCam:

    adb shell am start \
      -n com.splarg.bugcam/.MainActivity \
      --ez auto_start true

Grant camera and local-network permissions when requested on first installation.

## HTTP interface

BugCam listens on port `8080` by default.

For a phone at `192.168.1.50`:

    http://192.168.1.50:8080/

### Health

    GET /health

Returns JSON containing camera state, frame rate, resolution, frame age,
battery and thermal information, HTTP state, focus information and torch state.

Example:

    curl -s http://192.168.1.50:8080/health | python3 -m json.tool

### Snapshot

    GET /snapshot.jpg

Example:

    curl http://192.168.1.50:8080/snapshot.jpg -o bugcam.jpg

### MJPEG stream

    GET /stream

Example:

    ffplay -fflags nobuffer http://192.168.1.50:8080/stream

### Torch

Turn the torch on:

    GET /torch/on

Turn it off:

    GET /torch/off

On supported Android versions and camera hardware, set the torch strength:

    GET /torch/on?level=1

The available range is reported by `/health`.

### Focus

Lock the current autofocus distance:

    GET /focus/lock

Return to continuous autofocus:

    GET /focus/auto

The locked focus distance is stored in device-protected storage and restored
when BugCam starts again, including during Direct Boot where supported.

## Screen-off operation

BugCam runs camera capture in a foreground service.

Once started, capture and HTTP serving continue when:

- the activity is closed
- the display goes to sleep
- the phone remains locked

BugCam does not hold a wake lock merely to keep the display awake.

## Reboot and Direct Boot

BugCam does not automatically start itself from `BOOT_COMPLETED`.

The intended unattended setup is for another machine on the LAN to launch it
using wireless ADB after the phone reboots:

    adb shell input keyevent KEYCODE_WAKEUP

    adb shell am start -W \
      -n com.splarg.bugcam/.MainActivity \
      --ez auto_start true

    adb shell input keyevent KEYCODE_SLEEP

On the tested Pixel 7 Pro / Android 17 setup, BugCam can start its camera
service before the first manual unlock after reboot.

See `scripts/launch-adb.sh` for an example.

## Soak testing

A soak-test helper is included:

    python3 scripts/soak-test.py \
      http://192.168.1.50:8080 \
      --minutes 60 \
      --stream

## Security

BugCam is intended for use on a trusted private LAN.

The built-in HTTP server currently provides no authentication or TLS.
Do not expose port 8080 directly to the public internet.

For remote access, use an appropriate authenticated VPN, reverse proxy or
other access-control layer.

## Thermal considerations

Continuous camera capture and JPEG encoding can warm a phone, particularly at
1080p and higher frame rates.

For permanent installations:

- provide reasonable airflow
- avoid insulating the phone
- monitor battery temperature and Android thermal state through `/health`
- reduce resolution or frame rate if necessary

## Architecture

See `ARCHITECTURE.md` for more information about the camera, frame-store and
HTTP-server design.

Testing information is in `TESTING.md`.

## License

BugCam is released under the MIT License. See `LICENSE`.
