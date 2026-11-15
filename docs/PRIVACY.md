# Privacy and security

## Summary

Freebuff Mobile is local-first. There is no account, no analytics, no crash
reporting and no advertising SDK. Conversations, projects, recordings and models
stay in app-private storage, and Android backup is disabled for the app.

## Permissions, and only these

| Permission | Why | When it is requested |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | model/voice downloads, opt-in web search | at download or search time |
| `RECORD_AUDIO` | tap-to-talk voice input | first microphone tap |
| `POST_NOTIFICATIONS` | download progress, task completion | onboarding (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager download worker | during a download |
| `WAKE_LOCK` | keep the download worker alive | during a download |

No location, contacts, calendar, SMS, call log, camera, external-storage or
`QUERY_ALL_PACKAGES` permission is declared. The manifest adds a single `<queries>`
entry so the platform text-to-speech engine can be discovered.

## Microphone rules

* Recording starts **only** from a microphone tap, after the permission check.
* The recorder is opened, then released in a `finally` block before transcription.
* Voice activity detection ends the turn after ~1.4 s of silence, or at 30 s.
* Nothing listens while the app is backgrounded, and there is no wake word.
* Speech output never runs while the capture path is live: `VoiceAssistant`
  enforces one turn at a time and stops capture before `SPEAKING`.
* Raw audio is discarded after transcription unless *Keep raw recordings* is
  switched on; even then it stays in app-private storage.

The microphone cannot identify a speaker. Tap-to-talk is the privacy boundary, not
speaker recognition, and the UI never claims otherwise.

## What leaves the device

| Feature | Data sent | Default |
| --- | --- | --- |
| Model/voice download | HTTPS request for a known public file | only what you consent to |
| Web search / deep research | your query text, to the selected provider | off until you use those modes |
| Hugging Face metadata lookup | repository + file name | on demand |
| Remote provider | the conversation text you send | disabled, must be configured and selected |
| Local chat / voice / agent / terminal | nothing | always local |

Project files are **never** attached to an outbound request. Remote providers get
only the messages in the current conversation, and every remote reply is stored
with `fromRemote = true` and shown with an "Online reply" chip.

## Credentials

API keys are encrypted with an AES-256-GCM key that lives in the Android Keystore
(`SecureStore`). Keys are never written to preferences in plaintext, never logged,
never included in backups and never placed in exported projects. The app is fully
functional with no key at all.

## Data protection

* `android:allowBackup="false"`, `fullBackupContent=false`, and an extraction
  rules file that excludes root/database/sharedpref/file from both cloud backup
  and device transfer.
* All app data lives under `filesDir/freebuff` (models, workspace, logs, exports)
  and `cacheDir/freebuff-tmp`.
* Deleting a model removes the weights and companion files it installed.
  "Delete all conversations" clears chat history. Both are explicit actions.
* Uninstalling the app removes everything, because nothing is stored outside the
  sandbox. Exported zips are written to app-private storage; sharing one is your
  action.

## Terminal and agent containment

* No shell. Commands run as an argument vector against `/system/bin/<binary>`.
* Allowlist: `ls cat head tail wc grep find du date mkdir touch cp mv rm echo pwd help clear`.
  Anything else is refused with exit code 126.
* Every path argument is resolved with `canonicalFile` and must stay inside the
  project directory; absolute paths, `~` and `..` escapes are rejected.
* `rm` refuses `.`, `./` and `*`; `rm`, `mv` require a confirmation dialog.
* 30 s timeout, 64 KB output ceiling, cancellable process.
* Agent tools are a closed enum; `write_file` and `delete_file` are marked as
  needing approval, and no tool can read outside the project.

## Honest limitations

* Downloads stop if you force-stop the app, revoke network access, or a
  manufacturer battery manager kills WorkManager. The app states this instead of
  claiming otherwise.
* Models are third-party artifacts under their own licences. The app verifies
  digests but does not audit model behaviour.
* A 270M–600M parameter model is weak at reasoning and code. The agent therefore
  verifies files on disk and refuses to report work it could not complete.
