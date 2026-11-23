# PaperVault — Android Document Scanner & PDF Suite

An original Android app with the feature set of a full document-scanner suite
(scan to PDF, photo to PDF, PDF tools, format conversion, redaction, secure vault).
Name, branding and UI are original — it does not copy any existing app's assets.

Android project lives in [`android/`](android/). Build and test instructions: [`android/README.md`](android/README.md).

## Status

All 7 phases are implemented, compile, and pass unit tests. The app has **not yet been run on a
physical device or emulator** (the build environment has no Android emulator), so every flow
still needs a hands-on pass on a real phone before release.

## Tech stack

| Area | Choice | Why |
|---|---|---|
| Language / UI | Kotlin 2.0, Jetpack Compose, Material 3 | Modern, declarative UI |
| Min / target SDK | 26 / 35 | Covers ~97% of devices; `PdfRenderer`, Keystore AES-GCM available |
| Scanning | Google ML Kit Document Scanner | Edge detection, auto-crop, perspective fix, filters, multi-page — on-device |
| PDF render (view, thumbnails) | `android.graphics.pdf.PdfRenderer` | Built in, fast |
| PDF write (photo/text → PDF) | `android.graphics.pdf.PdfDocument` | Built in |
| PDF manipulation | PdfBox-Android 2.0.27 (Apache 2.0) | Merge, split, encrypt, text extraction, drawing |
| OCR | ML Kit Text Recognition (Play services) | On-device, for scanned pages and images |
| Office output | Own minimal OOXML writers (pure Kotlin) | No heavy Apache POI; JVM-testable |
| Storage | Room (metadata) + app-private files | Private by default |
| Vault security | `BiometricPrompt` + PBKDF2 + AES-256-GCM, Android Keystore | See Phase 6 |
| Sharing | `FileProvider` + `ACTION_SEND` / `ACTION_SEND_MULTIPLE` | No storage permission needed |

## Data model

- `Document(id, title, filePath, pageCount, sizeBytes, source, inVault, createdAt, updatedAt, folder, starred)`
  The PDF file is the single source of truth; page thumbnails are rendered on demand.
- Files: `filesDir/documents/<uuid>.pdf` (library), `filesDir/vault/<uuid>.pvault` (encrypted).
- Room schema v2 (migration from v1 adds `folder`, `starred`).

## Phases

### Phase 1 — Foundation ✅
- Compose app, navigation, manual DI, Room
- **Scan to PDF** (ML Kit), **Photo to PDF**, **Import PDF**
- **Page-wise breakdown** (numbered thumbnail grid), zoomable **PDF viewer**
- Rename, delete, **share**

### Phase 2 — Page editor ✅
- Select pages → rotate, move earlier/later, duplicate, delete
- Add pages by scanning, photos or another PDF
- Extract selected pages to a new PDF; share selected pages as JPG images

### Phase 3 — PDF tools ✅
- **Merge** (choose order), **Split** (every page / ranges like `1-3, 5` / every N pages)
- **Compress** (3 levels; re-encodes pages as JPEG — best for scans)
- **Password protect** (AES-256 copy for sharing); importing a protected PDF asks for its password and removes it
- **Page numbers** (3 positions, 4 formats), **Watermark** (text + opacity)

### Phase 4 — Edit & mask ✅
- **Mask content**: drag boxes — black, white-out, pixelate, blur. Masked pages are flattened
  to images so hidden text is removed from the file, not just covered. Save or share a masked
  copy; original untouched.
- **Annotate & sign**: pen, highlighter, text, signatures (draw once, reuse), move, erase, undo.
  Written as overlays so the underlying text stays selectable.

### Phase 5 — Format conversion ✅
- PDF → **Word** (editable text, or exact look as page images), **JPG/PNG**, **Text**, **Excel**
  (columns split on wide gaps), **PowerPoint** (one page per slide)
- **Word → PDF**, **Text → PDF**, **Image → Text** (OCR, copy/share)
- OCR fallback for scanned pages; results shareable or saved via the system file picker (zip for many files)
- Generated .docx/.xlsx/.pptx verified to open in python-docx, openpyxl, python-pptx and LibreOffice

### Phase 6 — Docs Vault ✅
- Set up with a password (min. 6) and optional fingerprint/face
- A random 256-bit data key encrypts files; it is stored only wrapped:
  by a PBKDF2-SHA256 (150k iterations) password key, and by an Android Keystore key that requires
  strong biometric auth per use and is invalidated when new biometrics are enrolled
- Files: chunked AES-256-GCM with chunk index + final flag in AAD (detects tampering/truncation)
- 5 wrong passwords → timeout (30 s, doubling, max 1 h)
- Auto-lock on background (immediately / 30 s / 1 min / 5 min); temporary decrypted copies wiped on lock
- `FLAG_SECURE` on vault screens (no screenshots, hidden in recents)
- Scan / photos / PDF straight into the vault, move in/out, share a copy, change password,
  reset (forgotten password — deletes vault contents; there is no recovery by design)
- App data excluded from cloud backup and device-to-device transfer

### Phase 7 — Polish & release ✅
- Folders, starred documents, filter chips, sort (recent / created / name / size)
- Settings: theme (system/light/dark), photo-to-PDF page size (A4 / Letter / fit), privacy note
- First-run onboarding; All tools hub
- 24 unit tests (page ranges, OOXML writers, vault crypto, PdfBox ops via Robolectric)
- R8 minified release build (9.4 MB) with keep rules; optional signing via `keystore.properties`

## Known limitations

- ML Kit scanner and OCR need Google Play services (not on e.g. recent Huawei devices).
- OCR covers Latin-script languages only (other scripts need extra ML Kit models).
- PDF → Word/Excel extract text; complex layouts, tables and fonts are not reproduced exactly.
  "Exact look" mode keeps appearance but isn't editable.
- Word → PDF keeps text, headings and line breaks; images and rich formatting are dropped.
- Compress and masking rasterise affected pages (text there is no longer selectable).
- Watermark/page-number text supports Latin characters only (standard PDF fonts).
- Page reordering uses move buttons rather than drag-and-drop.

## Next steps

1. Install on 2–3 real devices (incl. one without fingerprint hardware) and walk every flow.
2. Add instrumentation tests for scanner/vault UI flows.
3. Add drag-and-drop page reordering and more OCR languages if needed.
4. Create a signing key and publish an internal test build.
