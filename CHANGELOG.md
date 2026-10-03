# Changelog

## 1.4.7-tuning-preview — 2026-09-27

Major camera-pipeline and unattended-operation overhaul for the Pixel 7 Pro terrarium deployment.

### Camera and still capture

- Changed normal operation to an on-demand camera workflow:
  `/camera/on` → `/snapshot.jpg` → `/camera/off`.
- Added explicit use of the Pixel 7 Pro physical ultrawide camera `"3"`.
- Replaced preview-frame JPEG snapshots with a dedicated Camera2 HAL still-capture path.
- Added native full-frame 4:3 JPEG still capture; the target Pixel 7 Pro selects 4032×3016.
- Added fresh `CameraDevice.TEMPLATE_STILL_CAPTURE` capture for every `/snapshot.jpg` request.
- JPEGs are returned directly from the camera/HAL at quality 95 rather than being software-encoded from the preview stream.
- Preserved 1.0× digital zoom so the complete ultrawide frame is retained.

### Thermal and unattended-operation changes

- Removed continuous live camera operation from the normal unattended workflow.
- The continuous preview/feed kept the camera pipeline active, increased CPU and camera-provider load, raised battery temperature and Android thermal status, and could leave the device in a thermally constrained state.
- Normal timelapse capture now opens the camera only when needed, takes the still, and closes it again. This substantially reduces sustained camera and thermal load between frames.
- Added camera-open and torch failsafes so abandoned control sessions do not leave the camera or light running indefinitely.

### Focus, exposure and torch controls

- Added still-capture autofocus sequencing and improved handling of physical-camera AF results.
- Added persistent/manual focus support for close terrarium subjects.
- Added per-still manual exposure and ISO controls while retaining automatic capture as the default.
- Hardened manual exposure against unsupported sensor ranges and impractical frame durations.
- Improved torch state handling, strength reporting and shutdown behaviour.

### Positioning view

- Added `/live`, `/live.jpg`, `/live/start` and `/live/stop` as a temporary positioning aid rather than restoring continuous live operation.
- Added a browser positioning page with a full-frame 4:3 view and crop overlay.
- Positioning mode has a 10-minute safety limit and must be deliberately resumed, preventing the temporary live view from silently becoming a permanent high-load camera session.
- Added `app/src/main/assets/live.html` for the positioning interface.

### HTTP, health and testing

- Expanded `/health` with physical-camera ID, still resolution, still JPEG quality, still-pipeline state, snapshot errors, torch state, focus state and positioning state.
- Added and expanded HTTP tests for still capture, manual controls, camera lifecycle and positioning endpoints.
- Expanded testing notes for the on-demand and HAL JPEG workflows.

### Development history

- **2026-09-21:** moved toward the low-load/on-demand architecture and added camera-open failsafes.
- **2026-09-26:** added the physical-camera HAL JPEG still pipeline, autofocus sequencing, manual exposure work and torch failsafe.
- **2026-09-27:** hardened fixed-focus/manual-exposure behaviour and added the bounded positioning view.

## 1.0.0

Initial public release.

- Rear-camera MJPEG streaming over Wi-Fi
- Full-resolution JPEG snapshot endpoint
- JSON health endpoint
- Android foreground camera service
- Continues operating with the display off
- Android Direct Boot support
- Remote launch via ADB
- Continuous autofocus and persistent manual focus lock
- Torch on/off control
- Adjustable torch strength on supported Android devices
- Configurable resolution, frame rate and JPEG quality
- Camera and HTTP recovery/watchdog logic
- Battery and thermal reporting
