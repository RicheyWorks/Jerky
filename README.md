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

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
