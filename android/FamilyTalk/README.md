# FamilyTalk (Android)

Private voice/video calls, conference, chat and file sharing for your family only —
end-to-end encrypted, peer-to-peer media, internal extension numbers, optional SIP for
outside numbers.

- Design and security model: [docs/DESIGN.md](docs/DESIGN.md)
- Independent expert review and what was changed: [docs/REVIEW.md](docs/REVIEW.md)
- Optional own relay + TURN server: [server/README.md](server/README.md)

## Install
Every push to `android/FamilyTalk/` builds a signed APK in GitHub Actions
(`.github/workflows/familytalk-build.yml`) and publishes it as a GitHub pre-release.
Install it on each phone (Android 9 or newer).

### Signing key (do this once)
Updates only install over the old app if every build uses the same key. Make one on
your own computer and add it as repository secrets:
```
keytool -genkeypair -keystore familytalk.jks -alias familytalk -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 familytalk.jks   # -> secret FAMILYTALK_KEYSTORE_BASE64
                            # the password -> secret FAMILYTALK_KEYSTORE_PASSWORD
```
Keep `familytalk.jks` private and backed up. Never commit it.

## First use
1. On the parent's phone: **Start a new family** → you become extension **101**.
2. **Settings › Invite a family member** → the other person scans the QR with
   **Join with invite QR**. They get the next extension (102, 103, ...).
3. Tap the shield next to each name and compare safety codes in person once.
4. Call by tapping a name, or use the keypad: `102`, `0` for everyone, `*43` echo test.
5. Optional: **Settings › SIP account** to call phone numbers (`9...` or `+...`).

## Develop
```
FAMILYTALK_CORE_ONLY=1 ./gradlew :core:test   # pure-JVM tests, no Android SDK needed
./gradlew :app:assembleDebug                  # needs the Android SDK
```
