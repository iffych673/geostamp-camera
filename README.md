# GeoStamp Camera

Own-code Android timestamp camera. Same functions as the GPS map camera
category, own brand, own code, own design — no copied text, logos, or assets.

## Features (v1)
- CameraX capture with live preview — modern camera UI: full-screen preview, big round shutter, gallery + settings buttons
- Live stamp preview on screen before capture (date/time, address, coordinates)
- GPS stamp: date/time, address (Geocoder), coordinates, compass heading
- Map snapshot on the stamp (OpenStreetMap tiles, no API key)
- Weather on the stamp (Open-Meteo, no API key)
- Trusted time: network time via worldtimeapi.org, device-time fallback labelled
- Mock-location detection: mocked fixes are flagged on the stamp
- Named places (POI): save a job site name; photos there carry the name
- Signature line: company + contact on every photo
- Saves stamped copy + clean original to Pictures/GeoStamp
- Branded PDF report of today's photos (Android PdfDocument, shareable)
- No ads — school version, ad-free by design

## No keys needed
No Google Maps key, no weather key, no Play-services dependency for core
features. Core capture/stamp/PDF works on any Android 7.0+ device, including
devices without Google Play services (ads simply will not fill there).

Map tiles: © OpenStreetMap contributors.

## Build
Debug APK is built by GitHub Actions (`.github/workflows/android.yml`):
push to `main` → Actions → artifact `geostamp-debug-apk`.

Local build needs Android SDK + JDK 17:
`gradle assembleDebug`

## Before a Play release
- Replace the AdMob test app id / banner id with real ones
- App icon, screenshots, privacy policy, Play Console listing
- Bump versionCode/versionName, sign with a release keystore
