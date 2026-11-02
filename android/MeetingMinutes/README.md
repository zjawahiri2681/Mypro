# Meeting Minutes (Android)

Small Android app that records a meeting as a structured conversation (who said what, under which
agenda topic, what it replies to, and what was decided or assigned) and exports it as one Markdown
file built for an AI to turn into formal Minutes of Meeting.

- **Live transcript:** speech becomes text as people talk. You tap who is speaking.
- **Audio recording and notes:** records an `.aac` file (works with the screen off). You type the
  key points, and each note is stamped with its time in the audio.
- Every entry is labelled **Discussion, Reply, Question, Decision, Action** or **Note**.
- Replies link to the entry they answer, so the export shows threads.
- Actions carry an owner and a due date.
- **Export:** you can share the `.md` file, save it anywhere (including a Google Drive folder),
  or copy a ready-made AI prompt.

## Using it

1. **New meeting:** add a title, project, reference and location. Add the participants (★ marks
   the chair; set attendance to present, apologies or absent) and the agenda topics. Choose the
   capture mode and the speech language, then tick the consent box.
2. **During the meeting:**
   - Tap the topic chip at the top. Tap the speaker chip at the bottom.
   - Press the mic to listen, or type.
   - When the voice changes, tap the new speaker. This starts a new turn and resets the label to
     Discussion.
   - Tap **Reply** on any card to answer that entry.
   - Pick **Decision** or **Action** before the statement. For an action, also set the owner and
     the due date.
   - Tap a card to correct its text, speaker, topic, label or reply link.
3. **End:** optionally enter the next meeting date, then share the `.md` file. **Copy AI prompt**
   puts an instruction on the clipboard that tells the AI how to draft minutes from the file.

File name: `YYYY-MM-DD Short Title.md`, which matches the Second Brain note convention.

## Export format (`schema: mypro-minutes/1`)

```
---                          YAML front matter: title, date, start/end (ISO 8601 with offset),
type: meeting-transcript     duration, timezone, project, reference, location, chair, language,
schema: mypro-minutes/1      capture method, consent, participants, topics, counts, status
...
---
# Title
> Note for AI and readers    how to treat the content (raw record, speech may be wrong, IDs)
## Meeting details
## Attendees                 table: ID, name, role, organization, attendance, entry count
## Agenda                    T1, T2, ...
## Decisions                 derived list, each pointing at its entry ID
## Action items              table: Ref, Action, Owner, Due, Raised by, Topic
## Open questions            questions nobody replied to
## Discussion by topic       every entry exactly once, replies nested under what they answer:
### T1 — Safety
- `E-001` **Sara** · 10:34:20 · question · typed: Are the scaffold tags up to date?
  - `E-002` **Ahmed** · 10:34:50 · reply re E-001 · typed: Two towers still need inspection.
## Chronological transcript  optional (switch on the export screen)
```

Design rules:
- **Stable IDs:** `E-001`, `P1` and `T1` are never reused, even after a deletion.
- **Explicit reply links:** a reply is written as `re E-xxx`, even when it is also nested under
  its parent.
- **Provenance on every line:** each entry is marked `speech` or `typed`, plus `edited` if a
  person corrected it and `low confidence` if the recogniser was unsure.
- **Safe for any spoken text:** escaping stops it from creating headings, tables, links or HTML,
  and from breaking the front matter. A test feeds hostile input through a real CommonMark+GFM
  parser to check this.

A full sample is written to `core/build/sample-transcript.md` by the tests.

## Privacy

- **No `INTERNET` permission:** the app itself cannot send anything anywhere. Data leaves the
  phone only when you share or save it.
- **Private, unbacked storage:** meetings and audio sit in app-private storage. They are excluded
  from cloud backup and device-to-device transfer.
- **Speech recognition and the network:** live transcript uses the phone's speech service. "Keep
  speech on this phone" (the default) asks for offline recognition. If the offline language pack
  is missing, the app tells you, and only turns on online recognition if you allow it. Online
  recognition sends audio to that service, usually Google.
- **Consent:** a meeting cannot start until you confirm that everyone was told. The transcript
  records this.

## Known limits

- **Speaker identification is manual:** the app does not identify voices; you tap the speaker.
  Android has no on-device speaker identification.
- **Live transcript needs the app open:** the screen is kept on. Listening stops if you leave the
  app; press the mic again when you return. Some phones play a beep each time the recogniser
  restarts.
- **One capture mode per meeting:** Android can't reliably give the microphone to the recorder
  and the recogniser at once. Use audio mode when you need the recording as evidence.
- **Arabic offline recognition** depends on the language pack being installed on the phone.

## Build

- **Core tests (no Android SDK needed):** `MINUTES_CORE_ONLY=1 ./gradlew :core:test`
- **APK:** `./gradlew :app:assembleRelease`
- **CI:** `.github/workflows/meeting-minutes-build.yml` runs the tests and builds the APK on every
  push to this folder, then publishes it as a GitHub Release tagged `minutes-v<version>-build<n>`.
- **Signing key:** without the `MINUTES_KEYSTORE_BASE64` and `MINUTES_KEYSTORE_PASSWORD` secrets,
  each build gets a new signing key. Uninstall the old build before installing a new one, or save
  the generated key as those secrets.

## Review log

Each reviewer's method, what it found, and what changed:

| Reviewer | Method | Finding | Change |
|---|---|---|---|
| Minute-taker | Standard MoM structure | Minutes need attendance, chair, agenda, decision vs action, owner and due date, next meeting | Data model and export sections |
| Speech/audio engineer | Platform constraints | SpeechRecognizer stops after every silence | Restart loop with error-specific back-off |
| Speech/audio engineer | Platform constraints | Mic can't feed the recorder and the recogniser at once | Two explicit capture modes |
| Speech/audio engineer | Platform constraints | MP4 is unplayable if the app is killed | ADTS AAC, one file per recording session |
| Speech/audio engineer | Platform constraints | Speech arrives in small chunks | Chunks of the same turn under 10 s apart are merged |
| AI-document architect | Schema design | Duplicated content wastes tokens and confuses models | Each entry appears once; summary lists point at IDs; chronological view optional |
| AI-document architect | Schema design | A model needs context before content | Front matter plus a reading note |
| Privacy reviewer (red team) | Threat model of data at rest and in transit | Cloud speech recognition and backups leak meeting content | No INTERNET permission; backup and transfer excluded; offline speech by default |
| Privacy reviewer (red team) | Threat model | Recording without telling people | Consent gate, recorded in the file |
| Privacy reviewer (red team) | Threat model | Android 11+ can't see the speech service without a `<queries>` entry | Added |
| QA (red team) | FMEA on the meeting flow | Process death mid-meeting | Atomic save after every change; in-progress meeting reopens on launch |
| QA (red team) | FMEA | A "Decision" label leaking into the next speaker's turn | Changing speaker resets the label |
| QA (red team) | FMEA | Reply cycles and deleted parents | Replies only point backwards; a delete re-links children to the grandparent |
| QA (red team) | FMEA | Same-name participants | Rejected |
| QA (red team) | Hostile-input tests | Markdown injection, Arabic text, empty meetings, deep threads, corrupted files | Covered by unit tests through a real Markdown parser |
