# RFID Guard (Android)

Personal NFC / RFID tag reader, writer and security checker for Android 11+ phones with NFC.
Kotlin, Jetpack Compose, no third-party runtime libraries beyond AndroidX and kotlinx-serialization.

Phones read and write **13.56 MHz** tags only. 125 kHz proximity fobs (EM4100, HID Prox) and UHF
inventory tags need separate reader hardware and are out of scope.

## What it does

**Read**
- NTAG210/212/213/215/216, MIFARE Ultralight / EV1: identity, full page dump, configuration, NXP originality signature.
- MIFARE Classic 1K/4K (phones with an NXP NFC chip only): sectors that open with the public factory keys or keys you add.
- ISO 15693 (NFC Type 5): system information and blocks. FeliCa and ISO 14443-4 cards: identification only.
- NDEF content: text, links, smart posters, Wi-Fi credentials, contact cards, app-launch records, custom data.

**Security check on every scan** - plain-language findings ranked by severity:
link analysis (unencrypted, look-alike/punycode names, disguised addresses with `@`, shorteners, raw IP addresses,
hidden text-direction characters, `javascript:` / `intent:` / `file:` links), Wi-Fi passwords stored in clear text,
tags anyone can overwrite, factory-default MIFARE keys, broken Crypto-1, corrupt access bits, password-protection
state and brute-force limit, originality signature result, authenticity-seal result.
Links are never opened automatically; dangerous link types are refused outright.

**Write**
- Compose and write NDEF (text, link, phone, e-mail, Wi-Fi, contact, location, app launch); several records per tag.
- NDEF, page and data-block writes are read back and compared before success is reported. Where a read-back is
  impossible (first-time formatting, sector trailers, blocks the key cannot read) the result says so.
- Erase, permanent lock (read-only), restore a saved copy.
- Password-protect NTAG21x / Ultralight EV1 with a random per-tag password kept in the vault; remove it again.
- MIFARE Classic block write with access-bit validation. Card keys are stored per card and only ever presented
  to the card they were added for, so a fake card cannot harvest them.
- Optional **authenticity seal**: an extra record holding HMAC-SHA256 (truncated to 128 bits) over the tag UID and
  content, so the app can later tell whether content was edited or copied to a different tag.

## Security design

| Area | Measure |
| --- | --- |
| Network | Internet is used for one thing: the "Check for updates" button, which contacts GitHub (HTTPS only, system certificates only) when tapped. No background networking; nothing from the vault or a tag is ever sent. |
| Storage | One vault file, AES-256-GCM, random 256-bit data key. The data key is wrapped by a non-exportable Android Keystore key (StrongBox when available). |
| Unlock | On a phone with a screen lock, the Keystore key requires biometric (class 3) or screen-lock authentication **for every use**, enforced by secure hardware, not by app code. A vault created before a screen lock existed is bound to it at the next unlock. Without any screen lock the vault is encrypted but opens without a prompt - the app says so. |
| In memory | The vault is decrypted only while unlocked. Auto-lock on screen-off and after a background timeout (immediately / 1 min / 5 min); the data key is zeroed on lock and decrypted objects are released (the JVM cannot guarantee immediate erasure of released memory). |
| Screen | `FLAG_SECURE` (no screenshots, recording or recents preview). Touches that arrive through another app's overlay are ignored, on the main screen and in every dialog; on Android 12+ such overlays are hidden altogether while the app is on screen. |
| Backups | Excluded from Android cloud backup and device transfer. Manual export is passphrase-encrypted (PBKDF2-HMAC-SHA256, 600,000 rounds, AES-256-GCM, authenticated header). |
| Tag input | Tags are untrusted input. No NFC intent filters, so a tag cannot launch the app or feed it data while closed or locked. Parsers are bounded and fuzz-tested. |
| Writes | Need an explicit, expiring (45 s) request. Erase, lock, password, restore and block writes are bound to the UID that was scanned and need re-authentication. Logged in an encrypted activity log. |
| Clipboard | Copied values are flagged sensitive and cleared after 30 s if still on the clipboard. |

### Updates
Security → Updates → "Check for updates" asks GitHub for the newest RFID Guard release. If one exists and you agree,
the app downloads it into its private cache, verifies the SHA-256 checksum, package name, version and signing
certificate, then opens Android's installer for you to confirm. This needs a published release (run the workflow by
hand) and the permanent signing key described under "Signing"; builds signed with one-off keys cannot update each other.

### Deliberately not included
- No key cracking (nested / darkside / dictionary attacks) and no bundled list of leaked keys.
- No writing of MIFARE Classic block 0 and no UID changing or emulation ("magic" cards).
- No reading of payment-card (EMV) or identity-document data.

These would make the app a cloning tool for other people's credentials rather than a tool for your own tags.

### Honest limits
- NTAG / Ultralight EV1 passwords are 32 bits and travel unencrypted; they stop casual tampering, not a prepared attacker.
  The password is sent to any chip that presents the right UID and reports itself protected, so a purpose-built
  emulator can capture it. Write-only protection covers the whole chip; "hide content" protection starts at page 4,
  which leaves the lock bits outside the protected area.
- The authenticity seal and the NXP originality check cannot detect a copy on a chip that also fakes the UID, and
  the seal cannot tell an older sealed version of the same tag's content from the current one.
- MIFARE Classic's cipher is broken; the app says so on every Classic scan. Do not rely on it for access control.
- Removing or resetting the phone's screen lock destroys the vault key. Keep an encrypted export.
- Root detection is advisory. On a rooted or compromised phone, no app can protect data while it is unlocked.
- The NFC code paths were verified by unit tests and code review, not on every phone / chip combination.

## Project layout

```
android/RFIDGuard
├── core/   pure-JVM library: NDEF codec, link risk analysis, MIFARE Classic and Type 2 logic, tag audit,
│           authenticity seal, secp128r1 ECDSA originality check, vault and export crypto. Unit tested.
└── app/    Android app: Keystore vault, biometric prompt, NFC reader/writer, Compose UI.
```

## Building

GitHub Actions (`.github/workflows/rfidguard-build.yml`) runs the unit tests and builds the APK on every push that
touches this folder; the APK is kept as a workflow artifact. Starting the workflow by hand (Actions → Build RFID
Guard APK → Run workflow) also publishes it as a GitHub Release (`RFIDGuard-vX.Y.Z-buildN.apk`).

Local build (Android SDK 35, JDK 17):

```bash
cd android/RFIDGuard
./gradlew :core:test
./gradlew :app:assembleRelease
```

Only the core, on a machine without Google's Maven repository:

```bash
RFIDGUARD_CORE_ONLY=1 ./gradlew :core:test
```

## Signing

Android only installs an update over an existing app when both are signed with the same key, and uninstalling
erases the vault. Create one key and give it to the workflow as secrets. **Never commit it or upload it as an
artifact - this repository is public.**

```bash
keytool -genkeypair -keystore rfidguard.jks -alias rfidguard -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 rfidguard.jks   # value for the RFIDGUARD_KEYSTORE_BASE64 secret
```

Repository → Settings → Secrets and variables → Actions: add `RFIDGUARD_KEYSTORE_BASE64` and
`RFIDGUARD_KEYSTORE_PASSWORD`. Keep `rfidguard.jks` somewhere safe offline. Without the secrets each build is
signed with a one-off key that is discarded after the build.
