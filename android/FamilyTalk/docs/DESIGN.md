# FamilyTalk — design

A private calling, chat and file-sharing app for one family. No accounts, no phone
numbers, no cloud backup, no ads, no Google push. Every family phone holds its own keys.

## 1. What it does

| Feature | How |
|---|---|
| Voice and video calls between family phones | WebRTC, direct phone-to-phone (P2P), DTLS-SRTP |
| Conference (up to 6 voice / 4 video) | Full mesh: each pair of phones connects directly |
| Chat (1:1 and family group) | Sealed envelopes via the relay; queued for offline phones |
| File sharing (up to 25 MB) | Chunked, each chunk sealed; SHA-256 checked on arrival |
| Internal extensions | 101–199 per person, 0 = ring everyone, *43 = echo test |
| Outside calls | Any SIP account (provider / PBX): dial 9 or + for numbers, or a sip: address |
| SIP conference | Several SIP calls merged on the phone (Linphone local conference) |
| Verification | 60-digit safety number / QR per pair, compared in person |

## 2. Numbering plan

```
101-199   family members (101 = whoever created the family; next free number is given to each new member)
0         ring everyone -> conference
9<number> outside number via the SIP account   (like "9 for an outside line" on an office PBX)
+<number> outside number via the SIP account
sip:...   any SIP address via the SIP account
*43       echo test - checks microphone, speaker and the WebRTC stack locally
```

## 3. Architecture

```
 Phone A                               Relay (MQTT over TLS)                  Phone B
 ┌───────────────┐   sealed envelope   ┌──────────────────────┐   sealed     ┌───────────────┐
 │ Compose UI    │ ──────────────────▶ │ topic = H(family,B)  │ ───────────▶ │ Compose UI    │
 │ CallManager   │                     │ sees only ciphertext │              │ CallManager   │
 │ ChatRepo      │ ◀────────────────── │ queues for offline B │ ◀─────────── │ ChatRepo      │
 │ FamilyRepo    │                     └──────────────────────┘              │ FamilyRepo    │
 │ Vault(Keystore)│                                                          │ Vault         │
 │ WebRTC  ◀═════╪════════ DTLS-SRTP media, direct P2P (or via TURN) ═══════▶│ WebRTC        │
 │ Linphone(SIP) │──── TLS/SRTP ───▶ SIP provider ───▶ phone network (outside calls only)
 └───────────────┘
```

- `core/` (pure Kotlin, unit-tested): crypto, roster/invites, dial plan, protocol, replay guard, file chunking, call state machine, mesh rules, MQTT relay.
- `app/` (Android): encrypted storage, foreground service, WebRTC engine, Linphone SIP engine, Compose UI.
- `server/` (optional): Mosquitto + coturn for families who want their own relay.

## 4. Security model

**Keys.** Each phone generates an Ed25519 signing key and an X25519 key. The member id is a hash of the signing key. Private keys are stored in an AES-256-GCM file whose key lives in the Android Keystore. Backup and device transfer are disabled.

**Family roster.** A JSON directory (names, extensions, public keys, roles, relay settings), signed by an admin. Phones accept a new roster only if it is signed by an admin of the roster they already trust, has a higher version and is for the same family. The very first roster is checked against the admin key pinned from the invite QR. A member cannot promote themselves; a removed phone stops being able to send anything that others will accept.

**Joining.** The admin shows a QR code (24 h, single use, shown only — never shared as text) containing the family id, the admin's public keys, relay settings and a 256-bit secret. The new phone pins the admin key and sends a join request containing an HMAC of its own keys and name under that secret. The admin's phone then asks "Let X join?" and shows the first digits of their safety number, which also appear on the newcomer's waiting screen. Every roster change is written into the family chat.

**Envelopes.** Each message: fresh X25519 ephemeral key; key = HKDF-SHA256(DH(eph, B's signed prekey) ‖ DH(A, B), salt = eph ‖ B, info = header hash); ChaCha20-Poly1305 with the header as associated data; Ed25519 signature over header, nonce and ciphertext. Receivers verify the signature against the roster key **before** decrypting, then drop anything replayed, older than 2 minutes (call signalling) or 7 days (chat), from the future, or call signalling sent before the app started.

**Sealed sender.** The signed envelope is wrapped again in an anonymous box to the recipient (fresh X25519 + ChaCha20-Poly1305) and padded to 1 KiB buckets. The relay sees only the recipient's hashed inbox topic, a size bucket and timing — no family id, sender, recipient id or timestamp.

**Forward secrecy.** Each phone makes a new X25519 prekey every week, signs it and sends it to the family; senders encrypt to the newest one. Private prekeys are deleted after three weeks, after which recorded messages to that phone cannot be decrypted even with the phone's identity key.

**Calls.** WebRTC media is DTLS-SRTP. The SDP that carries the DTLS fingerprint travels only inside signed, encrypted envelopes, so a relay or TURN operator cannot insert themselves (no man-in-the-middle). Media is only negotiated with participants who accepted and have not left; only the caller can add people. "Hide my IP address" forces media through TURN.

**What servers see.** The relay sees one anonymous inbox topic per phone, padded sizes, timing and IP addresses — not names, extensions, who sent a message, or content. TURN sees encrypted media and IP addresses. STUN (Google's, unless the admin configures a TURN server, which then also provides STUN) sees IP addresses when a call starts. Self-hosting removes all three third parties.

**On the phone.** Keys, chats, call history and received files live in AES-256-GCM files keyed by the Android Keystore. Opening a file decrypts a temporary copy that is wiped at the next start. Installer/script/web file types are never handed to other apps. Notifications say only who wrote, unless you switch previews on. Screenshots are blocked by default.

**Outside calls (SIP).** SIP calls are encrypted only between the phone and the SIP provider (TLS + SRTP when enabled). The provider and the phone network can hear them. The app says so on every SIP screen.

## 5. Honest limits

- **Conference with a normal mobile call or WhatsApp call:** Android does not let one app take audio from another app's call or from the cellular call. A third-party app cannot merge into WhatsApp or the phone's own dialer. What is possible:
  - Call the outside person **through your SIP account** (they get a normal phone call), and merge several SIP calls on your phone.
  - Bridging an outside caller into an end-to-end encrypted family conference needs a server that decrypts and mixes audio (Asterisk/FreeSWITCH). That breaks end-to-end encryption, so it is documented in `server/README.md` but not enabled.
  - The low-tech way still works: put your phone on speaker.
- **"Fully P2P":** media is P2P. Phones still need a meeting point to find each other and to ring while asleep — that is the relay. It is blind (sealed envelopes) and can be your own server.
- **Forward secrecy for chat:** weekly prekeys, deleted after 3 weeks. A stolen phone exposes at most the last ~3 weeks of recorded messages to it. Calls have full forward secrecy (DTLS ECDHE). A per-message ratchet (Double Ratchet / MLS) would shrink that window to zero.
- **Admin trust:** an admin (or a stolen admin phone) can add a device. Every change appears in the family chat and new members show as "Not verified", but nothing cryptographically prevents it. Keep admin phones locked; make only trusted adults admins.
- **Metadata on the public relay:** hidden as far as possible (sealed sender, padding, hashed topics), but someone watching the public broker can still see that an inbox receives messages and when. Run your own hub for full privacy.
- **Mesh conference size:** each phone uploads one stream per participant, so 6 voice / 4 video is the practical limit.
- **Legal:** check that VoIP and encrypted calling apps are allowed where you use them.

## 6. Visual design

Palette "Oasis": deep teal `#0F766E` (trust, calm; brand and security colour), warm sand `#B45309` / `#FBBF24` (family warmth, "not verified yet"), standard call green `#15803D` and red `#DC2626`. Dark call screen `#071311` so video is the focus. Light and dark themes follow the system. Avatars use 8 distinct hues with white initials. Status is never shown by colour alone: shield icons and words go with it. Large touch targets (call buttons 56–72 dp, keypad keys 76 dp).
