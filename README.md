# Jerky

[![CI](https://github.com/RicheyWorks/Jerky/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/Jerky/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine eleven of the ecosystem: **cold storage, dried for the road**. Packs a SmokeHouse
backup (segments + manifest — the shape `backup()` and DryAge's vault produce) into one
DEFLATE-compressed, CRC-verified `.jerky` archive; verifies without extracting; restores
into a directory `SmokeHouse.restore` opens. The log is the only truth, so a verified
archive of the log is a verified archive of everything.

```java
Jerky.Cured cured = Jerky.cure(backupDir, archive);   // files, rawBytes, curedBytes, ratio()
Jerky.verify(archive);                                // full CRC walk, no extraction
Jerky.restore(archive, dir);                          // refuses a bad CRC outright
```

Scope, honestly narrowed: archival compression needs no benchmark (cold bytes are strictly
better smaller); the deferred **columnar scan format** keeps its measured-first trigger.

## Design notes

- **Never unpack garbage.** `restore` runs a full CRC verification pass before extracting a
  single byte; a failed archive leaves the target untouched. Archived names are checked
  against path traversal before any write.
- **Compressed sizes are framed explicitly.** Inflaters read ahead, so unframed boundaries
  corrupt the next entry — each file's deflated region is length-prefixed and sliced
  exactly. (This bit once during development; the framing is the fix, and the corruption
  test guards it.)
- **`Cured` tells the truth:** files, raw bytes, cured bytes, ratio — compression claims
  are numbers, not adjectives.
- **Scope, honestly narrowed:** archival compression only. The columnar scan format keeps
  its measured-first trigger — a benchmark showing cold-segment scans as a real cost.

## The ecosystem

Eleven engines, one organism — each in its own repo, composed by nested Gradle
composite builds:

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index — orders the world |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds in O(n) |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — durability, tail, watchers, replicas |
| [Carver](https://github.com/RicheyWorks/Carver) | the read planner — decides how to read |
| [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine — folds the tail into live aggregates |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache — eviction policy evolved per workload |
| [PitBoss](https://github.com/RicheyWorks/PitBoss) | the fleet conductor — lag watch, re-bootstrap, the promotion runbook |
| [DryAge](https://github.com/RicheyWorks/DryAge) | the time-travel engine — as-of reads over preserved history |
| [Twine](https://github.com/RicheyWorks/Twine) | crash-atomic multi-key batches — journaled commit, idempotent replay |
| [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) | the wire — a loopback protocol face for the store |
| **Jerky** (this repo) | cold storage — compressed, CRC-verified backup archives |
| [WholeHog](https://github.com/RicheyWorks/WholeHog) | the integration organism — all of them, at once |

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
