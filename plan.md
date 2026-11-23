# PaperVault — Android Document Scanner & PDF Suite

An original Android app with the feature set of a full document-scanner suite
(scan to PDF, photo to PDF, PDF tools, format conversion, redaction, secure vault).
Name, branding and UI are original — it does not copy any existing app's assets.

Android project lives in [`android/`](android/).

## Tech stack

| Area | Choice | Why |
|---|---|---|
| Language / UI | Kotlin 2.0, Jetpack Compose, Material 3 | Modern, declarative UI |
| Min / target SDK | 26 / 35 | Covers ~97% of devices; `PdfRenderer`, Keystore AES-GCM available |
| Scanning | Google ML Kit Document Scanner | Edge detection, auto-crop, perspective fix, filters, multi-page — on-device |
| PDF render (view, thumbnails) | `android.graphics.pdf.PdfRenderer` | Built in, fast |
| PDF write (photo → PDF) | `android.graphics.pdf.PdfDocument` | Built in |
| PDF manipulation (merge, split, edit, text) | PdfBox-Android (Apache 2.0) | Page ops, text extraction, drawing |
| OCR | ML Kit Text Recognition | On-device, for scanned PDFs → Word/Text |
| Storage | Room (metadata) + app-private files | Private by default |
| Vault security | `BiometricPrompt` + PBKDF2 password, AES-256-GCM via Android Keystore | Files encrypted at rest |
| Sharing | `FileProvider` + `ACTION_SEND` / `ACTION_SEND_MULTIPLE` | No storage permission needed |
| Background work | Kotlin coroutines (+ WorkManager for long conversions) | |

## Data model

- `Document(id, title, filePath, pageCount, sizeBytes, source, inVault, createdAt, updatedAt)`
  The PDF file is the single source of truth; page thumbnails are rendered on demand.
- Files: `filesDir/documents/<uuid>.pdf` (normal), `filesDir/vault/<uuid>.enc` (encrypted).

## Phases

### Phase 1 — Foundation, scanning, photo → PDF, page breakdown, sharing  ✅ (this commit)
- Gradle project, Compose theme, navigation, manual DI container
- Room database for documents
- Home: document list (thumbnail, pages, size, date), search, FAB menu
- **Scan to PDF** with ML Kit (auto edge detect, crop, filters, multi-page)
- **Photo to PDF**: pick multiple photos → A4 PDF
- **Import PDF** from device storage
- **Document detail**: page-wise grid breakdown (thumbnails, page numbers)
- **PDF viewer**: swipe between pages, pinch-to-zoom
- Rename, delete, **share** (PDF) from list and detail

### Phase 2 — Page editor
- Reorder pages (drag), rotate, delete, duplicate
- Add pages to an existing document (scan more / add photos)
- Extract selected pages into a new PDF
- Share selected pages as images

### Phase 3 — PDF tools
- **Merge** multiple PDFs (choose order)
- **Split** PDF (every page, by ranges, or selected pages)
- Compress PDF (re-encode images at chosen quality)
- Password-protect / unlock PDF
- Add page numbers, watermark

### Phase 4 — Edit & mask
- **Mask / redact** content: draw boxes over sensitive regions (ID numbers, addresses),
  choose black/blur/pixelate; output is flattened (rasterised) so masked content is
  truly removed, not just covered. "Share masked copy" keeps the original intact.
- Annotate: freehand pen, highlighter, text boxes, shapes
- Signature: draw / save signatures, place on page
- Edit text on top of page (overlay)

### Phase 5 — Format conversion
- PDF → Word (.docx) — text extraction (PdfBox) + OCR fallback for scanned pages
- PDF → Images (JPG/PNG, per page or zip)
- PDF → Text (.txt) with OCR
- PDF → Excel (.xlsx, table heuristics) / PDF → PPT (one image per slide)
- Images → PDF, Word (.docx) → PDF, Text → PDF
- Image → Text (OCR, copy to clipboard)

### Phase 6 — Docs Vault
- First-run setup: create vault password (+ optional fingerprint/face)
- Unlock with **biometric** (BiometricPrompt, class 3) or **password** fallback
- Password stored as PBKDF2-SHA256 hash + salt; never plaintext
- Vault files encrypted with AES-256-GCM, key held in Android Keystore
- Move documents in/out of vault; import directly into vault
- Auto-lock on background / timeout; `FLAG_SECURE` (no screenshots, hidden in recents)
- Change password, failed-attempt lockout

### Phase 7 — Polish & release
- Folders & tags, sort/filter, recent files
- Settings (default scan filter, PDF page size, quality, theme)
- Onboarding, empty states, dark mode
- Unit + instrumentation tests, R8/ProGuard config, signed release build

## Build

```bash
cd android
./gradlew assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK 35 (`ANDROID_HOME` or `android/local.properties`).
The ML Kit scanner needs Google Play services on the device.
