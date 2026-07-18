# Jerky — working notes for agents

Engine 11: compressed cold archives of SmokeHouse backups. One class (`Jerky`, static):
`cure` (per-file DEFLATE with compressed-size framing + whole-body CRC32 trailer),
`verify` (full parse, no extraction), `restore` (verify-then-extract, refuses non-empty
targets and unsafe names).

## Invariants (do not break)
- **Never unpack garbage.** verify-before-extract is mandatory; a failed CRC leaves the
  target untouched.
- **Frame compressed sizes explicitly** — inflaters read ahead; unframed boundaries corrupt
  the next entry (this bit once already; the framing is the fix).
- Columnar/scan format = measured-first (a benchmark showing cold-scan cost re-arms it).
- Round-trip oracle in `JerkyTest`; every format change needs a corruption test with it.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.
