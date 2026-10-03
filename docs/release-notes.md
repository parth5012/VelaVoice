# Release notes

## Quarantined sessions: local-only badge + egress exclusions (map #130, ticket #136)

Privacy-sensitive sessions are quarantined on-device under
`transcriptions_quarantine/` and are now visible library entries with an
explicit **"Local-only — never uploads"** badge (Voice Hub library,
TranscriptionEditor, Recent Transcriptions) instead of being hidden.

Quarantined content is hard-excluded from every egress path:

- Google Drive sync (scanner never enters the quarantine dir; pre-upload
  verdict re-check stays fail-closed),
- correction-training SQLite (`CorrectionAPI` + `ModelManager` refuse
  quarantined writes before any INSERT),
- `scripts/export_corrections.py` (quarantined rows skipped, counts-only log),
- the in-app correction POST (quarantined edits stay on-device).

### Pre-privacy-protection uploads

Uploads that predate privacy protection carry **no sensitivity verdict**.
They are left untouched: no automation re-scans, re-labels, moves, or deletes
already-synced Drive content unasked. The fail-closed verdict check applies to
new scans and uploads only — legacy Drive files are never touched by this
change.
