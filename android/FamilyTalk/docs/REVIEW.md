# Independent expert review — FamilyTalk 0.1

Three reviewers worked independently from the code (not from the author's notes):
an **applied-cryptography / mobile security** reviewer, a **product design & accessibility**
critic, and a **senior Android / real-time-communications** engineer. Each was asked to
be a critic, not to approve. Below: their verdicts, every material finding, and what was done.

| Reviewer | Score (first version) | Main concern |
|---|---|---|
| Security | **5 / 10** | Public relay could see who talks to whom; no forward secrecy for messages; a conference member could keep receiving audio after "leaving" |
| Design & accessibility | **6.5 / 10** | Status colours unreadable in dark mode; permissions asked with no explanation; no Arabic yet |
| Android / systems | **6 / 10** | Microphone cut with screen off on Android 11–13; relay never retried after a failed first connect; SIP threading; missed calls not reported |

"Fixed" means changed in code and covered by a unit test where the logic is in `core/`.
"Open" items are honest limitations, listed so nobody is surprised.

## Security

| # | Finding (severity) | Status |
|---|---|---|
| 1 | Envelope header (family, sender, recipient, time) visible to anyone on the public broker; UI claimed "random-looking addresses" (High) | **Fixed** — sealed-sender outer layer + 1 KiB padding (`Sealed.sealOuter`), test `relaySeesNoIdentitiesOrExactSizes`; UI/claims rewritten. Public broker kept as the zero-setup default, clearly labelled; own hub recommended. |
| 2 | No forward secrecy for messages (High) | **Fixed (bounded)** — weekly signed prekeys, private halves deleted after 3 weeks (`Prekeys`), tests in `PrekeyTest`. A full ratchet remains a future step. |
| 3 | Conference: a member who declined/left could still negotiate media and receive audio; any member could rewrite the participant list (High) | **Fixed** — SDP/ICE only from accepted, not-left participants; only the caller can add people, never remove. |
| 4 | Joins auto-approved; invite could be shared via any app (Med-High) | **Fixed** — admin must tap Allow, with safety digits shown on both phones; text sharing removed. Relay credentials still travel in the QR (needed to connect) — shown in person only. |
| 5 | Admin is an unchecked single point of trust (Medium) | **Partly fixed** — every roster change is written into the family chat; verifications reset for removed members; wording no longer says "rule out impostors completely". Co-signing for admin changes: **open**. |
| 6 | Removal relies on everyone receiving the update; shared broker/TURN credentials (Medium) | **Open** — per-device broker credentials with topic ACLs and credential rotation are planned for the self-hosted hub. |
| 7 | Google STUN always added (Medium) | **Fixed** — with a TURN server configured, its own STUN is used instead; wording corrected everywhere. |
| 8 | SIP "SRTP" label shown even when keys travel over plain UDP/TCP; no explicit TLS certificate checks (Medium) | **Fixed** — "encrypted to provider" only with TLS + SRTP; certificate and host-name checks forced; incoming SIP shows "caller ID can be faked". |
| 9 | TURN server could be used to reach link-local / CGNAT / IPv6 private ranges (Medium) | **Fixed** in `server/turnserver.conf`. Time-limited TURN credentials: **open**. |
| 10 | Received files stored unencrypted (Low) | **Fixed** — files kept in the Keystore-encrypted vault; temporary decrypted copy only when opened. |
| 11 | Message text in notifications (Low) | **Fixed** — hidden by default, opt-in preview. |
| 12 | Flooding / memory exhaustion via many open file transfers (Low) | **Partly fixed** — max 3 incoming files per sender at once; outer layer rejects non-family traffic after one X25519. Broker-side rate limiting: **open** (self-hosted hub). |
| 13 | Replay of call signalling after app restart (Low) | **Fixed** — signalling older than app start is dropped (`signalNotBefore`), test `signallingFromBeforeRestartIsDropped`. |
| 14 | Ed25519 signatures make messages provable to third parties (Low) | **Accepted trade-off** — signatures stop a stolen key from impersonating others (KCI resistance); documented. |
| 15 | Exported activity could open chats; dangerous file types opened; one-tap "verified" (Low) | **Fixed** — per-process intent token, APK/HTML/script types blocked, manual verification needs explicit confirmation. Minified release builds: **open**. |

## Design & accessibility

| Finding | Status |
|---|---|
| Verified/warning colours 3.5:1 on dark surfaces; call-screen trust badge failed AA (High) | **Fixed** — theme-aware `trustOk()`/`trustWarn()` (#4ADE80 / #FCD34D on dark, 10:1 and 12:1). |
| "Read" vs "Delivered" by colour only (High) | **Fixed** — read uses a filled-circle tick (different shape). |
| No localisation / Arabic (High) | **Open (largest remaining item)** — strings still in code. Done so far: numbers forced left-to-right, wrapping rows for chips, no fixed line heights (no clipped diacritics), no letter-spacing on headings. Next: move all text to `strings.xml` and add `values-ar`. |
| All permissions requested at once on first launch; no battery exemption (High) | **Fixed** — one-step-at-a-time readiness card (mic → notifications → lock-screen calls → battery → optional camera) with a reason for each. |
| Name field could not be cleared; ring-everyone without confirmation; badge tap target ~18 dp | **Fixed** |
| In-call toggles inconsistent, low contrast, no on/off for TalkBack | **Fixed** — filled = ON everywhere, 28% fill + border, `Role.Switch` with state. |
| Raw internal states shown ("no answer", "in call", registration errors) | **Fixed** — friendly text. |
| Copy pointing at missing features (rename, settings names, Hide-IP without TURN) | **Fixed** — rename added, names aligned, switch disabled with explanation when no TURN. |
| Keypad clipped at large font; recents hidden under chats | **Fixed** — scrollable keypad; Recents tab. |
| Dark flash at launch in light mode; notification icon lost its handset | **Fixed** — `values-night`, dedicated monochrome icon. |
| Small 12–13 sp text on key notes | **Fixed** — 14 sp minimum for notes and labels. |

Palette verdict from the reviewer: the teal + sand "Oasis" palette "feels calm, trustworthy and appropriate for a Saudi family"; standard green/red for answer/decline kept.

## Android / systems

| Finding | Status |
|---|---|
| Compile errors (`ScheduledFuture.cancel()`, Linphone `Core.conference`) | **Fixed** — CI build #2 green, APK published. |
| Mic/camera silenced with screen off on Android 11–13 (High) | **Fixed** — microphone/camera service type from API 30. |
| Relay never retried after a failed first connect (High) | **Fixed** — retry with backoff, 15 s max reconnect delay, reconnect on network change. |
| Call invites lost when the link blips (QoS 0) (High) | **Fixed** — invite/accept/decline/hangup queued by the broker (QoS 1); stale ones dropped by the 2-minute window. |
| Linphone Core used from several threads (High) | **Fixed** — all SIP work on one Looper thread. |
| Missed calls rarely reported (High) | **Fixed** — caller giving up while we ring = missed. |
| Glare (both call each other) → both busy | **Fixed** — smaller call id wins and is auto-answered. |
| One dead link in a conference never recovered | **Fixed** — per-peer ICE restart. |
| Whole state rewritten for every file chunk; 25 MB file boxed into a List | **Fixed** — debounced save, `copyOfRange` chunking. |
| Collector leak on reconnect; failed publish silently dropped | **Fixed** |
| No way back to a call after pressing Home | **Fixed** — ongoing-call notification opens the call; call screen in recents. |
| AudioSource leak; echo test leaks on failure | **Fixed** |
| Clock skew > 2 min drops signalling | **Open** — phones on automatic network time are fine. |
| Outbox is memory-only | **Open** — the broker's persistent session covers most cases. |
| Jetpack `core-telecom` integration, OEM autostart guidance, ABI splits, pinned Linphone version, minified release | **Open** |

## Overall

After the fixes the reviewers' blocking items (all High findings) are resolved except
**Arabic localisation**. The remaining open items are hardening steps, mostly for the self-hosted hub.
Before relying on it, the family should: set up their own signing key (README), compare safety
numbers once in person, and — for the strongest privacy — run the Family Hub (`server/`).

## Round 2 — real-device test and calling (WebRTC/VoIP) expert

Field test (Saudi home Wi-Fi on STC with IPv6, tablet + phone): joining hung, then calls
stayed on "Connecting…" with no audio and the tablet did not ring. A fourth independent
reviewer (WebRTC/VoIP) audited the call path.

| Finding | Status |
|---|---|
| Relay unreachable: Paho tried only the IPv6 address; port 8883 may be filtered | **Fixed (0.2.1)** — own MQTT client, IPv4 first then IPv6; three relays on ports 8883 / 8884 / 443 used together |
| All relay connections shared one MQTT client id, so two connections to the same broker kicked each other off every ~2 s | **Fixed (0.2.4)** — one client id per relay |
| Call signalling dropped when device clocks differ by more than a few seconds | **Fixed (0.2.3)** — 30 min tolerance; replay protection by persisted message ids |
| SDP/ICE overtaking the "accept" (relays don't keep order) were discarded | **Fixed (0.2.3)** — parked until that person has accepted |
| Call messages sent while no relay was connected were lost silently (tablet never rang) | **Fixed (0.2.4)** — 60 s local queue for call signalling; offer + candidates re-sent every 3 s until answered; a repeated offer re-sends the answer |
| Network writes on WebRTC's signalling thread | **Fixed (0.2.4)** |
| ICE restart lost new-generation candidates; restart offer repeated on every state change | **Fixed (0.2.4)** |
| Sleeping tablet: keep-alive timers stop in Doze, broker drops the link unnoticed | **Fixed (0.2.4)** — Doze-friendly wake-up alarm pings or reconnects; keep-alive 600 s |
| Incoming-call channel could keep an old low importance; no mic permission check at answer; no audio focus; tablets without earpiece | **Fixed (0.2.4)** |
| Only Google STUN: an estimated 25–40 % of mobile-data ↔ home-Wi-Fi calls cannot connect without TURN | **Partly fixed** — Google + Cloudflare STUN by default. **TURN needs a free account** (recommended: metered.ca, 20 GB/month, TURN over TLS on port 443); the admin enters it once in Settings › Relay & TURN servers and it is shared, signed, with every family phone. |
