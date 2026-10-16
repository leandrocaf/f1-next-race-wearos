# F1 Next Race — Wear OS

Wear OS port of the Zepp OS app (`../f1-next-race`). Standalone Kotlin + Compose for Wear OS
app: the watch calls the Jolpica API directly (Wi-Fi/LTE or through the phone's connection),
with no phone-side app.

- `F1Api.kt`: HTTP + JSON parsing (`current/next.json` and `current.json`)
- `MainViewModel.kt`: loading/error/data state
- `MainActivity.kt`: screens (next race → calendar; swipe right to go back)
- `Flags.kt` + `res/drawable-nodpi/flag_*.png`: flags (copied from the Bip Max assets)

Differences from the Zepp version: times use the watch's time zone (it used to be fixed at UTC-3),
the calendar opens scrolled to the next race, and the UI is in PT-BR (English for any other language) when
the watch's language is Portuguese (Brazil) (`res/values-pt-rBR/strings.xml`; race names come
in English from the API, country names are translated with `Locale`).

## Build
    ./gradlew assembleDebug        # add --offline if there's no network
APK: `app/build/outputs/apk/debug/app-debug.apk` (minSdk 30 = Wear OS 3+)

## Install on the watch
1. On the watch: Settings → System → About → tap "Build number" 7x.
2. Settings → Developer options → enable **ADB debugging** and **Wireless debugging**.
3. Under Wireless debugging → "Pair new device": note the IP:port and the code.
4. On the Mac:
       adb pair <ip>:<pairing-port>     # enter the code
       adb connect <ip>:<port>          # port shown on the Wireless debugging screen
       adb install -r app/build/outputs/apk/debug/app-debug.apk
5. Open "F1 Next Race" from the watch's app list.

Logs: `adb logcat | grep -i f1nextrace`
