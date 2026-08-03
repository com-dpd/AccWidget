# ACC Widget (`com.dp.accwidget`)

Companion app + homescreen widgets for [ACC](https://github.com/VR-25/acc) on Redmi Note 12 Pro 4G (`sweet2`, LineageOS23.2 Android16).

## Features

- Master charging control ON/OFF (ACC hysteresis when on; unrestricted when off)
- Resume % / Stop % / max current (up to 3000 mA)
- One-shot smart charge to a target % by HH:MM
- Standard + compact widgets (SoC, signed current green/red, profile, Stop/Start/smart)
- Hide launcher icon; open settings from the widget
- Root probed once; battery % / current readable without root

## Energy use

Widgets do **not** poll every few seconds with exact alarms.

- Refresh on `POWER_CONNECTED` / `POWER_DISCONNECTED`
- Throttled `BATTERY_CHANGED` while widgets are placed
- Inexact backup alarms: ~45 s while charging/plugged, ~4 min on battery
- Battery reads use BatteryManager / sysfs (no `su` on the tick path)


APK: `app\build\outputs\apk\release\app-release.apk`

Grant root to **ACC Widget** in Magisk when prompted. Add **ACC Charge** widgets to the home screen.

## Smoke checklist

1. Root granted banner in settings; live battery updates
2. Apply hysteresis; Stop / Start / smart on large widget
3. Unplug → current shows minus + red; plug/charge → plus + green
4. Hide launcher icon; open app from widget tap
5. After reboot, widgets still refresh (boot + power events)

## ACC module

Magisk module e.g. `acc_v2025.5.18-dev`. Prefer switch `battery/input_suspend 0 1`.
