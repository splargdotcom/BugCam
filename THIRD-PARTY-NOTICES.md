# Third-party components

- Gradle wrapper: Gradle project, Apache License 2.0. The original launcher
  copyright/license headers are retained. Official files copied from the
  Gradle `v9.4.1` release tag. Wrapper JAR SHA-256:
  `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`.
- Kotlin standard library: JetBrains/Kotlin contributors, Apache License 2.0.
  Resolved by the Android Gradle plugin's built-in Kotlin support.
- JUnit 4.13.2: Eclipse Public License 1.0; test-only dependency, not in the APK.
- Android SDK / AGP and the JDK are external build tools, not bundled here.
  Their respective terms and licenses apply.

No camera or HTTP framework is bundled. The camera, JPEG compressor, JSON,
notifications, UI widgets and sockets use Android platform/JDK APIs.
