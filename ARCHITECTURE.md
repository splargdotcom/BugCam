# BugCam v1 architecture

Chosen before implementation: **Android Camera2 + one YUV ImageReader + one
throttled JPEG encoder + a small, bounded, read-only HTTP/1.1 server**.

Camera2 is maintained as part of Android. It exposes the rear camera and its
normal ISP auto-exposure, white balance and supported autofocus directly. A
started `camera` foreground service owns the camera, never the Activity. There
is no preview Surface tied to a window and no boot receiver. The visible
Activity starts the service after runtime permissions have been granted.

The preferred capture size is 1920×1080. The default published rate is **5 fps**,
JPEG quality **85**. The sensor uses a supported AE range around 15 fps where
available; publishing and sensor rates are deliberately separate. Images that
arrive while encoding or before the next publishing slot are closed immediately.
Only one image can be encoding; there is no growing image queue.

The encoder respects YUV row/pixel strides and crop rectangles, optionally
rotates the pixels, and publishes one immutable JPEG at the negotiated capture
resolution. `/snapshot.jpg` and all `/stream` clients share those bytes. A
snapshot never opens a second capture session. “Full resolution” means the full
configured capture resolution, not a full sensor-resolution still or Google Camera HDR+.

The HTTP implementation uses maintained Android/JDK sockets, with no web
framework or abandoned embedded-server dependency. Its scope is deliberately
small: GET/HEAD, four read-only routes, no uploads, filesystem access, persistent
request connections, or HTTP request bodies. There are 8 connection workers,
at most 3 MJPEG viewers, bounded request headers, and absolute read/write
deadlines. A watchdog closes blocked writers; socket read timeouts alone would
not solve that problem. Slow viewers skip frames instead of queuing JPEGs.

The listener binds specifically to the current Wi-Fi IPv4 address, never a
wildcard or cellular address. Android network callbacks and periodic retries
recreate it after Wi-Fi/address changes. Internet validation is not required.
Camera capture continues while Wi-Fi is absent. IPv6-only LANs are outside v1.

Camera errors, configuration failures and missing-frame watchdogs trigger
generation-guarded teardown and exponential retry (1–30 seconds). Unsupported
or failing sizes fall back to smaller supported sizes. Late callbacks cannot
revive old sessions. Intentional Stop releases all resources. Force-stop or
process death requires the external supervisor to relaunch the Activity; v1
does not promise unattended restart through a locked boot or revoked permission.

No CPU or Wi-Fi wake lock is acquired. A foreground service is not itself a
wake lock. Mains power, active camera capture and the actual Pixel's screen-off
behaviour must be measured. Only add a scoped lock if the physical-device soak
test demonstrates a suspend-related failure.

The boundaries are `CameraController`, `FrameStore`, `WifiHttpHost` and
`BugCamHttpServer`. A future hardware encoder can consume another Camera2
Surface while retaining the snapshot path. `TorchController` is an explicit
capability interface; v1 reports “not tested” and the two reserved torch routes
return 501 without disturbing the camera. After the user's correction, v1 uses
the main rear camera at 1×. Future torch control should use FLASH_MODE_TORCH in
that camera's repeating request and be tested on the Pixel first.

The UI is platform Views and Kotlin. No Compose, CameraX, network framework,
coroutines, audio capture or analytics is needed. Runtime dependencies are the
Android platform and Kotlin standard library only.
