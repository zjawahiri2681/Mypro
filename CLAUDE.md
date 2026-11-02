# Project: Mypro - Second Brain (desktop side)

This repo is the desktop half of my Second Brain. The Drive half is mapped in
Google Drive > Second Brain > AGENTS.md. Read that first when Drive is connected.

## Rules (always apply)
- Cost stays in its separate confidential file. Never put cost in technical documents.
- Documents sent to Aramco carry only Contract and Specification references.
- CONFIDENTIAL_TGP_Cost.sqlite is restricted. Do not read, copy or summarise it unless I ask.
- Do not move or delete files. Propose changes first.
- Notes: YYYY-MM-DD Short Title. Projects: PROJECT - Name. Resources: RES - Topic.

## Drive map (links)
- AGENTS.md: https://drive.google.com/file/d/1o4aqm6pnhhOCKxwG9DryXdjXAmjG1ytj/view
- INDEX - Projects: https://drive.google.com/file/d/1I3xX2g_bGUQ45oziQIpbSw9FGC91Birq/view
- INDEX - Resources: https://drive.google.com/file/d/1YUKa14qw6X-uHsDbKu-MVeucPBICPQir/view
- Dashboard: https://claude.ai/artifact/FBHRf1r9svXeXKWNER8j5j

## Active projects
- TGP Disposal Wells Enhancement (Tanajib) - due 2027-02-28
- Jafurah Type-D Additional 40 Rooms - due 2026-12-06
- Archived: Hyundai OWEP (2026-10-30)

## Apps in this repo
- `android/IPTVBox/` - Android IPTV player (M3U / Xtream / Stalker, EPG, watch-together). APK is built by
  `.github/workflows/android-build.yml` and published as a GitHub Release on every push to that folder.
  Core parsers/protocol are pure JVM: `IPTVBOX_CORE_ONLY=1 ./gradlew :core:test` runs without the Android SDK.
- `android/MeetingMinutes/` - Android meeting recorder (live transcript or audio + notes, speakers, topics, replies,
  decisions, actions) exporting `YYYY-MM-DD Title.md` for AI minute drafting. APK from
  `.github/workflows/meeting-minutes-build.yml`, release tag `minutes-v*`. Core tests: `MINUTES_CORE_ONLY=1 ./gradlew :core:test`.

## Desktop folders
Filled in once this repo is shared through Remote Control. One line per folder:
path - what it holds - which project - matching Drive folder.
- _None recorded yet._

## Never
- Commit secrets, tokens, `.env` files or credentials.
- Commit cost files, supplier price data or client drawings to this repo.
- Push to any branch other than the designated working branch without explicit permission.

## Known gotchas
Format: symptom, cause, fix.
- **Symptom:** Drive file cannot be edited by Claude. **Cause:** Drive connector creates files but cannot change their content. **Fix:** create a new copy, trash the old one.
