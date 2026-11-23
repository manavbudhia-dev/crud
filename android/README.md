# PaperVault (Android)

Document scanner and PDF toolkit: scan/photo to PDF, page editor, merge/split/compress,
password protection, masking, annotation and signatures, format conversion, and an encrypted
Docs Vault with fingerprint or password unlock. See [`../plan.md`](../plan.md) for the full feature list.

## Requirements

- JDK 17+
- Android SDK 35 (set `ANDROID_HOME`, or `sdk.dir` in `local.properties`)
- A device or emulator **with Google Play services** (ML Kit scanner and OCR)

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified; unsigned unless keystore.properties exists
./gradlew testDebugUnitTest    # 24 JVM/Robolectric tests
```

Install: `adb install app/build/outputs/apk/debug/app-debug.apk`

### Release signing

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=release.jks
storePassword=...
keyAlias=papervault
keyPassword=...
```

Generate a key with `keytool -genkeypair -v -keystore release.jks -alias papervault -keyalg RSA -keysize 4096 -validity 10000`.
Keep the keystore safe — losing it means you can't ship updates under the same app ID.

## Code map

| Package | Contents |
|---|---|
| `data` | Room entity/DAO, `DocumentRepository`, `SettingsRepository` |
| `pdf` | `PdfUtils` (render, images→PDF), `PdfOps` (PdfBox ops), `Redactor`, `Annotations`, `PageRanges` |
| `convert` | `Converter` (OCR, PDF↔formats), OOXML writers (`DocxWriter`, `XlsxWriter`, `PptxWriter`), `DocxReader` |
| `vault` | `VaultCrypto` (key wrapping, chunked AES-GCM), `VaultManager` (unlock, biometrics, auto-lock, storage) |
| `ui/*` | Compose screens: home, detail, viewer, editor, edit (mask/annotate), tools, vault, settings |

## Notes

- Everything runs on-device; no network calls are made by the app itself.
- The app ID is `com.papervault.scanner` — change `applicationId` before publishing if needed.
