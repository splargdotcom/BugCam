# HAL JPEG snapshots: current duty-cycled build

`/camera/on` → `/snapshot.jpg` → `/camera/off` remains the unattended contract.
The camera stays open for preview/streaming until `/camera/off` or the existing
15-second failsafe. A successful snapshot closes its `Image`, not the live
session; a failed/timed-out still retires the session and both readers.

## What to check first on the Pixel 7 Pro

1. Build and install using the existing project instructions. Launch BugCam
   visibly once, retaining its foreground camera service and permissions.
2. Run the normal on/snapshot/off sequence promptly. `/snapshot.jpg` can wait
   for asynchronous camera startup. Allow a 20-second HTTP client timeout;
   the camera transaction itself has a 12-second deadline. Use the original
   full-resolution JPEG for quality comparisons, not a resized dashboard copy.
3. `/health` should report `physical_camera_id: "3"`,
   `still_pipeline: "camera2_hal_jpeg_v1"`, `still_jpeg_quality: 95`, and a
   native 4:3 `still_resolution`. Existing resolution/fps/quality fields still
   describe MJPEG preview, now capped at 1280×720 to reduce encoding work.
4. Inspect `adb logcat -s BugCam-Camera`. Each completed still logs the
   physical ID, JPEG dimensions, AF state, focus distance, focal length,
   exposure time in ns, ISO, zoom/crop, capture timestamp and byte count.
   Confirm approximately 1.95 mm focal length, zoom 1.0, and an uncropped
   physical active array (allow the HAL's normal lens correction margins).
5. Saved manual focus is intentionally restored on startup. To test triggered
   autofocus, call `/focus/auto` after the camera is ready, then take the
   snapshot immediately. The still uses MACRO if available, otherwise AUTO,
   and waits up to 4 seconds for FOCUSED_LOCKED. A saved/previously successful
   focus distance is used as a manual fallback. Without a known fallback,
   AF failure returns 503 instead of silently claiming a focused photo.
6. Autoexposure/white balance get up to 2 seconds to settle after focus.
   Capture proceeds with a logged warning if those algorithms do not report
   convergence but focus has settled. A moving/unsettled lens is an error.
7. Confirm `/focus/set?diopters=...`, `/focus/lock`, `/focus/get`,
   `/focus/restore`, and `/focus/auto`. `/focus/restore` now restores the saved
   value instead of the previous hard-coded 7.707 D. Check persistence across
   off/on. `/focus/auto` retains its existing behaviour of clearing the lock.
8. Test torch off/on and exposure compensation. Their current settings must
   carry into the JPEG. Test configured 0/90/180/270-degree orientation;
   JPEG orientation is handled by the HAL/EXIF, without software re-encoding.
9. Manual still exposure (one still only, nothing persisted):
   `/snapshot.jpg?exposure_ns=<ns>&iso=<iso>`. Both are required; one without
   the other is 400. Plain `/snapshot.jpg` is the unchanged automatic still.
   Unrelated query keys are ignored; any other key starting `exposure`/`iso`
   (e.g. `exposure_ms`) is 400. Values are clamped to camera 3's advertised
   exposure/ISO ranges (exposure also to 1 s). Sequence: focus first (AF under
   automatic exposure, or `focus_diopters`), then the repeating request
   switches to AE off with the same values, the settle waits until results
   show them (plus AWB/focus ready), and the JPEG uses the same values; the
   preview's AE mode is restored afterwards. Waits scale with frame length.
   Headers: `X-Exposure-Requested-Ns`/`X-ISO-Requested` (as sent),
   `X-Exposure-Target-Ns`/`X-ISO-Target` (after clamping), `X-Exposure-Clamped`,
   and `X-Exposure-Applied-Ns`/`X-ISO-Applied` from the JPEG's capture result
   (`X-Exposure-Applied-Source: capture-result`; every still gets these).
   The older `X-Still-*` headers remain. Check `X-Still-Manual-Honoured: true`
   before trusting a manual comparison. Combinations: none = AF + auto;
   `focus_diopters` = fixed focus + auto; `exposure_ns`+`iso` = AF + manual;
   all three = fixed focus + manual.

10. Positioning view: open `http://PIXEL_IP:8080/live` and press Start. The
   camera opens with a full-frame 4:3 preview (up to 1280x960) instead of the
   normal 16:9 one, and the browser draws the timelapse crop (default 16:9,
   centred, full width: 4032x2268 with 374 px trimmed top and bottom) plus
   optional thirds grid and centre mark. `?crop=3:2`, `1:1` or `full` also work.
   Nothing is drawn into frames, stills or `/stream`. Each `/live.jpg` fetch
   keeps the camera open; closing the tab lets the normal failsafe close it
   within about 15 s, and the view stops after 10 minutes until resumed.
   `/live/stop` equals `/camera/off`. Check: overlay visible and matching the
   crop; `/health` shows `positioning: true` and a falling
   `positioning_remaining_seconds`; a `/snapshot.jpg` taken meanwhile is still
   4032x3016; after closing the tab, `camera_state` returns to `idle`.
   Pause the timelapse schedule while positioning: its `/camera/off` ends the
   view (press Resume), and during positioning `/stream` shows the 4:3 frame.

Example (replace PIXEL_IP; always close the camera even if capture fails):

```bash
BUGCAM_URL=http://PIXEL_IP:8080
trap 'curl -fsS --max-time 3 "$BUGCAM_URL/camera/off" >/dev/null || true' EXIT
curl -fsS --max-time 3 "$BUGCAM_URL/camera/on"
curl -fsS --max-time 20 -D snapshot.headers "$BUGCAM_URL/snapshot.jpg" -o snapshot.jpg
# Experimental manual still, e.g. 1/30 s at ISO 100 (33,333,333 ns):
curl -fsS --max-time 20 -D manual.headers \
  "$BUGCAM_URL/snapshot.jpg?exposure_ns=33333333&iso=100" -o manual.jpg
curl -fsS --max-time 3 "$BUGCAM_URL/health" > snapshot-health.json
curl -fsS --max-time 3 "$BUGCAM_URL/camera/off"
trap - EXIT
```

## Lifecycle and regression checks for this change

- Repeat on/snapshot/off with the screen locked. Verify JPEGs have increasing
  `X-Frame-Sequence` values, no `camera_recoveries` increase, and camera/torch
  shut down after each cycle. Compare memory/temperature over repeated cycles.
- Take two successive snapshots while on: both must invoke a new still
  capture; neither may be the preview mailbox image. HEAD also captures, but
  returns only headers. MJPEG viewers must continue receiving preview frames.
- Request two snapshots concurrently: one capture is admitted, the other
  returns 409. Focus/torch/exposure changes during a still also return a busy
  response; `/camera/off` remains available and cancels the still with 503.
- Interrupt during AF, JPEG capture and camera startup. Stop the service too.
  Check all `Image`s/readers/devices close and late framework callbacks do not
  reopen resources. A subsequent `/camera/on` must work normally.
- Exercise autofocus failure, camera privacy, camera disconnection, JPEG
  failure/timeout, and an HTTP disconnect. Late JPEGs must never be served by
  a later request; matching uses capture-result/Image timestamps and session
  generation. A disconnected HTTP client can leave at most the bounded
  transaction running; server shutdown interrupts the waiter.
- Compare AF and a manual focus bracket from an unchanged mount, lighting
  and subject. Inspect leaf veins and moss at matched scale. No sharpening,
  noise-reduction, Camera Extensions or burst-fusion changes are included.

Run the existing JVM suite with `bash ./gradlew testDebugUnitTest`, and build
with `bash ./gradlew assembleDebug`. Socket regression tests cover fresh still
routing, exact returned bytes, capture-vs-header deadlines, error/busy results,
server shutdown, and continued preview/health behaviour. They do not prove
physical-camera/HAL compatibility or optical quality.

The historical continuous-capture tests below predate the on-demand camera
and its 15-second failsafe; for this build, use repeated on/snapshot/off cycles
instead of leaving the camera open for their stated soak durations.

---

# Physical Pixel acceptance test

The JVM tests validate CPU-side data handling and the real socket server. They
cannot establish camera HAL, rear-camera optics, Android permission dialogs,
screen-off power behaviour, Wi-Fi roaming or temperature on the physical Pixel.

## First 10 minutes

1. Grant all permissions while BugCam is visible. Start with 1920×1080, 5 fps,
   0° rotation. Check `/health` for the negotiated resolution and a growing count.
2. Check snapshot orientation, sharpness, distance, exposure and white balance
   against the actual terrarium. Verify rear-camera autofocus settles on the bugs
   rather than reflections in the glass.
3. Open `/` in the ThinkCentre browser. Compare `/snapshot.jpg`. Check that
   taking many snapshots does not increment `camera_recoveries`.
4. Go Home, press Back from the Activity, and swipe BugCam from Recents.
   The persistent service/notification and HTTP feed should continue.
5. Turn off “Stay awake while charging” in Developer options; press the power
   button. Keep the phone on mains. Confirm `/health` counts keep advancing for
   at least ten minutes while `dumpsys power` reports the display asleep.

## One hour, then overnight

From the ThinkCentre:

```bash
python3 scripts/soak-test.py http://PIXEL_IP:8080 --minutes 60 --stream | tee one-hour.jsonl
python3 scripts/soak-test.py http://PIXEL_IP:8080 --minutes 480 --stream | tee overnight.jsonl
```

Record failed samples, service uptime/reset, recovery count, fps, JPEG size,
encoding time, battery °C and thermal status. A healthy run has advancing frame
counts, no prolonged frame-age gaps, stable memory and temperature, and no
unexpected service restart. Do not call a few minutes of success an overnight
pass. Keep the same mount, lighting and power setup for rate comparisons.

## Controlled faults

| Test | Expected behaviour |
| --- | --- |
| Disconnect one browser mid-frame | Other viewers, snapshots and capture continue |
| Three viewers plus `/health` requests | All work; a fourth stream receives 503 |
| A client stops reading | Its write deadline closes the socket; capture continues |
| Turn Wi-Fi off for 30 seconds, then on | Capture keeps running; server rebinds; browser reconnects |
| DHCP address changes | New URL on phone/notification; use that address on Linux |
| Toggle Android camera privacy off/on | Health degrades; retry/backoff; capture resumes when allowed |
| Another app takes rear camera | BugCam reports failure/retry; resumes when camera is available |
| Stop in BugCam/notification | Camera privacy indicator, server and service cease |
| Start/Stop quickly five times | No leaked camera, port or client threads; normal start succeeds |
| Deny local-network permission | Clear prompt/message; no apparently successful inaccessible server |
| Deny camera/notification permission | Clear prompt/message; no half-started service |
| Reboot Pixel | No app self-start; verify ADB availability and first-unlock requirement, then use launch helper |
| Force-stop app | Feed stops; explicit external Activity launch required |

For disconnect/reconnect faults, soak-test failures during the deliberate outage
are expected. Inspect time-to-recovery after the fault is removed.

Use Logcat tags listed in README. Compare `dumpsys meminfo com.splarg.bugcam`
before/after repeated fault tests. An image stream retained by a browser after
disconnection is not evidence that capture is still active; check frame count
and age in `/health`.

## Wake-lock decision

If screen-off fails, first distinguish camera permission/session errors from
Wi-Fi loss and CPU suspend using Logcat and the health timeline. Mains charging
normally changes idle behaviour, but a foreground service isn't a CPU wake
lock. Do not add a blanket wake lock just because the screen is dark. If a
suspend-related failure is reproduced, add a partial lock scoped to active
capture, release it on Stop/error teardown, then repeat the same test.

## Torch experiment (future)

For this rear-camera build, prefer setting `CaptureRequest.FLASH_MODE_TORCH`
on the existing repeating request. Do not assume `CameraManager.setTorchMode`
can control a camera already owned by a capture session. Test during a
supervised run, observing exposure, focus and temperature. Verify the torch
turns off on service stop and camera failure. v1's reserved routes deliberately
return 501 until this has been established.
