# Tiyin

Tiyin is an Android app that shows where your money goes in Uzbekistan. It reads the payment SMS that HUMO, UZCARD, Kapitalbank, Asakabank and Payme send for every card operation, and builds your spending by month, year and category. You can also load a Payme export (.xlsx) instead of granting SMS access.

Everything stays on the phone. There is no account, server, ads or analytics. The only network request looks up the name of an unfamiliar shop on [orginfo.uz](https://orginfo.uz), a public registry of Uzbek organisations, to pick its category. The [privacy policy](https://ndrewnee.github.io/tiyin/privacy/) has the details in Russian, English and Uzbek.

The interface is available in Russian, English and Uzbek.

## Install

Tiyin is not on Google Play. Build it yourself and install it over adb:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

You need JDK 17 and the Android SDK (API 36). The release build is signed with your local debug key, so later builds install over earlier ones without losing data.

## Project layout

- `core` holds the SMS and Payme parsers, categories and the ledger. It is plain Kotlin with unit tests: `./gradlew :core:test`.
- `app` is the Jetpack Compose app: SMS import and receiving, storage, the orginfo.uz lookup and the screens.
- `docs/privacy` is the privacy policy, served by GitHub Pages.
- `play` has store listing texts and screenshots made with invented data.

## License

The code is under the [MIT License](LICENSE). The Jost and Golos Text fonts are under the SIL Open Font License, see `third_party/fonts`.
