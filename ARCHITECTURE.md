# BugCam architecture

BugCam uses **Android Camera2 + one YUV ImageReader + one throttled JPEG encoder + a small bounded HTTP/1.1 server**.

The important architectural decision is that the foreground service may stay alive continuously, but the **physical camera does not**. Camera2 is normally closed and is opened only for a short capture transaction.

## Why the camera is on-demand

The first BugCam design kept Camera2 open continuously and exposed an MJPEG live view. That worked, but it was a poor fit for a permanently powered Pixel 7 Pro.

Continuous capture kept BugCam and Android's camera provider busy even when nobody was watching. In the terrarium deployment this caused sustained heat and eventually Android thermal charging mitigation: the phone could be connected to mains while charging very slowly or not charging at all.

The current design therefore separates the lightweight always-available parts from the expensive camera pipeline:

- the foreground service remains running
- the HTTP server remains available on the LAN
- Camera2 is normally closed
- an external controller opens Camera2 only when a photograph is required
- the controller retrieves a fresh JPEG
- Camera2 is closed again immediately afterwards

The public terrarium site consequently shows the **latest captured still image** rather than maintaining an always-on live video session.

The MJPEG route remains for short diagnostics, but continuous streaming is deprecated as the normal unattended operating mode.

## Service lifetime

A started camera foreground service owns BugCam, never the Activity. The Activity is only the visible launcher/configuration surface.

The service:

- validates permissions
- starts foreground operation
- loads device-protected configuration
- creates a reusable `CameraController`
- starts `WifiHttpHost`
- reports battery, thermal, camera and HTTP state through `/health`

The service does **not** open Camera2 at startup.

BugCam does not use `BOOT_COMPLETED`. Reboot/process-death relaunch is owned by an external supervisor, typically another machine on the LAN using ADB.

No CPU or Wi-Fi wake lock is acquired. A foreground service is not itself a wake lock.

## Camera lifecycle

`CameraController.start()` opens the main rear camera and creates a YUV `ImageReader`.

`CameraController.pause()` closes the capture session, `CameraDevice` and `ImageReader` while keeping the controller reusable for a later `start()`.

The normal external transaction is:

    GET /camera/on
    wait briefly for AE/AWB/focus to settle
    GET /snapshot.jpg
    GET /camera/off

### 15-second failsafe

Every camera-open operation arms an app-side 15-second maximum-open timer.

If the external controller crashes, Wi-Fi disappears, or `/camera/off` is never received, the failsafe:

- marks the controller idle
- cancels BugCam's watchdog
- closes the Camera2 session/device
- releases the active reader
- clears torch state

The camera therefore cannot remain accidentally open indefinitely.

### Stale callback protection

Each camera generation receives a token.

Late `CameraDevice.onOpened()` callbacks from an obsolete generation immediately close the supplied device. Late `CameraCaptureSession.onConfigured()` callbacks close the stale session.

Intentional pause removes only BugCam-owned scheduled callbacks. Camera2 lifecycle callbacks are not globally erased, so late callbacks are still able to run and clean up their resources.

`CameraDevice.onClosed()` is logged separately from the request to close it.

## Capture and encoding

The configured capture size is currently fixed at **1920×1080** for the reference deployment.

Default application settings are:

- requested capture: 1920×1080
- published JPEG rate while the camera is open: up to 10 fps
- JPEG quality: 85
- rotation: 0
- HTTP port: 8080

The Pixel may choose a different supported sensor AE range; the tested Pixel commonly reports 15–15 fps. Sensor rate and published JPEG rate are deliberately separate.

Only one JPEG may be encoding at a time. Incoming images are skipped when:

- they belong to an obsolete camera generation
- they arrive before the next configured publication slot
- the single JPEG encoder is already busy

This prevents an unbounded image/encoding queue.

The encoder:

- respects YUV row and pixel strides
- respects crop rectangles
- packs to NV21
- optionally rotates the frame
- compresses with `YuvImage.compressToJpeg`
- publishes one immutable JPEG into `FrameStore`

`/snapshot.jpg` and the diagnostic stream share the same encoded frame bytes. A snapshot does not open a second camera session.

“Full resolution” means the configured BugCam capture resolution, not the phone's full sensor resolution and not Google Camera HDR+ processing.

## Focus and exposure

Focus and exposure settings are stored in device-protected preferences so they can be reused by future short-lived camera sessions.

### Focus

A saved manual focus distance is stored in diopters.

When Camera2 opens, BugCam restores the saved distance when the rear camera supports manual focus. Otherwise it selects an available autofocus mode.

Controls include:

    GET /focus/set?diopters=7.707
    GET /focus/get
    GET /focus/lock
    GET /focus/auto

### Exposure

Exposure compensation is stored as Camera2 native AE compensation steps and is applied every time the camera wakes.

Controls include:

    GET /exposure/set?steps=3
    GET /exposure/get

On the tested Pixel, the tuning UI presents these as 1/6-EV steps.

## Torch

Torch control modifies the active repeating Camera2 request.

Controls include:

    GET /torch/on
    GET /torch/off
    GET /torch/on?level=1

Supported strength limits are reported through `/health`.

Torch use requires the camera to be open. The external night-capture controller therefore opens the camera, takes an ambient reading, enables the torch if needed, captures the illuminated JPEG, disables the torch and closes Camera2.

The 15-second camera failsafe also clears torch state.

## HTTP server

`BugCamHttpServer` is a small JDK-socket HTTP/1.1 implementation rather than a web framework.

It accepts GET and HEAD only, with:

- bounded request headers
- no request bodies
- no filesystem browsing or upload API
- 8 connection workers
- at most 3 MJPEG viewers
- absolute read/write deadlines
- one request per connection
- explicit cache-control and security headers

The root page provides status, camera tuning and test-shot controls.

Main routes are:

    GET /                 tuning/status page
    GET /health           JSON health and diagnostics
    GET /snapshot.jpg     most recent fresh JPEG
    GET /camera/on        open Camera2
    GET /camera/off       close Camera2
    GET /torch/...        torch control
    GET /focus/...        focus control
    GET /exposure/...     exposure control
    GET /stream           legacy diagnostic MJPEG

`/snapshot.jpg` returns HTTP 503 when there is no fresh frame. It does not silently serve an arbitrarily stale photograph.

The browser tuning page fetches a fresh JPEG as a Blob for its temporary preview. The Content Security Policy explicitly permits `blob:` image sources while keeping framing disabled.

## Wi-Fi binding

`WifiHttpHost` binds BugCam specifically to a current Wi-Fi IPv4 address rather than a wildcard/cellular address.

Android network callbacks track Wi-Fi address changes, and a periodic reconciliation loop recreates the HTTP listener as needed. Internet validation is not required.

Camera operation is independent of the HTTP listener once a camera transaction has begun, but an external controller naturally requires LAN reachability to initiate and complete normal transactions.

IPv6-only LAN operation is outside the current design.

## Health semantics

In the old continuous-streaming design, “healthy” meant a fresh active camera stream.

That definition is wrong for on-demand operation.

The normal resting state is:

    "camera_active": false
    "camera_state": "idle"
    "http_state": "listening"

An idle camera is intentional. The external supervisor should treat a responsive HTTP service as healthy even when Camera2 is closed.

`/health` also reports frame counters, resolution, JPEG size/encode time, focus state, torch capability, battery level/temperature, Android thermal status and HTTP client counts.

## External scheduling

Capture scheduling is intentionally outside the Android app.

For the reference terrarium deployment, a Linux ThinkCentre decides when to photograph. That keeps the Android app simple and allows different schedules for daylight and night operation without keeping Camera2 active between shots.

The external controller should always follow:

    camera on → settle → snapshot → camera off

The 15-second app-side failsafe is a safety net, not the normal close mechanism.

## Thermal validation

The move to on-demand capture was validated on the development Pixel 7 Pro.

During a 10-minute idle audit with automatic photography paused:

- BugCam reported Camera2 idle
- Android reported no active camera clients
- thermal status was normal
- battery temperature remained in the low 30s °C
- charging continued normally
- overall CPU use was essentially idle

Those measurements describe the reference device rather than a guaranteed result on all Android hardware, but they demonstrate why camera duty cycle is the primary power-control mechanism in this design.

## Component boundaries

The current boundaries are:

- `CameraController` — short-lived Camera2 sessions, focus/exposure/torch and JPEG production
- `FrameStore` — latest encoded JPEG plus capture/encode metrics
- `WifiHttpHost` — Wi-Fi IPv4 discovery and HTTP listener lifetime
- `BugCamHttpServer` — bounded LAN API, tuning page, snapshots and diagnostic stream
- `FocusMemory` / `ExposureMemory` — persisted camera tuning reused across sessions
- `BugCamService` — foreground lifetime, health reporting and component ownership

The UI uses platform Android Views/Kotlin. There is no Compose, CameraX, audio capture or analytics dependency.

Runtime dependencies are intentionally small: Android platform APIs and the Kotlin/JDK runtime.
