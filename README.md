# Hooch's Dash

<img src="icon.png" width="120" alt="Hooch's Dash icon">

An Android app that talks to a cheap Bluetooth ELM327 OBD2 dongle and shows what your car is doing. I wanted a simple dashboard I could stick on a phone mount, and the existing apps were either full of ads or did way more than I needed. So I built my own.

It started as "show RPM, speed, coolant temp and throttle" and just kept growing from there.

## What it does

- Connects to a paired Bluetooth ELM327 adapter and reconnects if the link drops
- Shows live connection status for both the dongle and the car, so you can tell if the adapter is fine but the ignition is off
- Pick any 4 values to show from around 60 (RPM, speed, temps, fuel trims, O2 sensors, load and so on). Anything your car doesn't support just shows a dash
- Gauge skins: plain text, or a few dial styles (cluster, minimal, segmented, classic needle) with their own animations, plus an optional peak marker for the highest value that session
- Full-screen dashboard that only shows the numbers or gauges, and an option to launch straight into it
- Dark mode for night driving
- Protocol selection with a test button and an auto scan, with plenty of protocols to choose from. There's also an ISO 14230-4 KWP (5 baud init) one tagged KZN185, which is the car I made this for
- Fault codes: read stored, pending and permanent codes, look at the freeze frame, check the emission monitor status and clear codes
- Shows the VIN when the ECU gives it up (some older ones don't)
- Press back twice to exit

## Using it

1. Plug the ELM327 adapter into the car and turn the ignition on
2. Pair it in Android's Bluetooth settings first (the PIN is usually 1234 or 0000)
3. Open the app, pick the adapter and it connects
4. The gear in the top right opens settings: protocol, metrics, gauge skins, faults and dark mode

If it says the car isn't responding, go to Settings, then Protocol, and run the scan.

## Building

You need Android Studio. Open the project, let Gradle sync, and hit run. Or from the command line:

```
gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. To install it on a phone you'll have to allow installs from unknown sources.

Min Android version is 8.0 (API 26). There are some unit tests for the parsing code that you can run with `gradlew testDebugUnitTest`.

## Things to know

- I've only really tested it on my own car and one adapter. Cheap clone dongles can be flaky, so your mileage may vary
- Clearing fault codes also resets the emission monitors, so don't do it right before an inspection
- It's a hobby project. Don't mess with it while you're driving, that's what the full-screen mode is for

## Licence

GPL v3, see [LICENSE](LICENSE).

