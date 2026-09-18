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
