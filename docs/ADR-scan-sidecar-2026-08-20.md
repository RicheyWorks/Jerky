# ADR: the sorted-run sidecar — 2026-08-20

**Status: Accepted.** Fired by the 2026-08-20 cold-scan benchmark
([`WholeHog/docs/EXPERIMENT-2026-08-20-cold-triggers.md`](https://github.com/RicheyWorks/WholeHog/blob/main/docs/EXPERIMENT-2026-08-20-cold-triggers.md)):
answering one scan over a cold `.jerky` archive costs **524×** the raw-read floor at 60k
records, against a pre-registered 5× bar.

## The question

The 2026-07-18 deferral called the held half "a scan-friendly *columnar* cold format." Now
that its trigger has fired, what do we actually build?

## What the measurement says the enemy is

Not the archive format. The decomposition was unambiguous: inflate 29 ms, recovery 61 ms,
**scan 434 ms** — the dominant cost is resurrecting a store and walking its ordered index
with per-record reads, solely to visit every record once in key order. The archive's framing
(per-entry name + sizes + deflated bytes, whole-body CRC) is not implicated at all.

## What "columnar" honestly means here

Nothing. SmokeHouse's values are opaque bytes — there are no fields to shred into columns
below the store, and consumers that have typed fields (Renderer's extractors, Carver's
attributes) live *above* the store where a cold format cannot see them. The honest reduction
of "columnar scan format" for this ecosystem is a **sorted run**: every live (key, value) in
key order, flat, framed by the store's own serializers — one sequential read answers the
scan that used to need a resurrection.

## Decision

**No `.jerky` v2. The v1 format is untouched — persisted formats are forever, and this one
was never the problem.** Instead, three additive cuts where each piece of knowledge already
lives:

1. **SmokeHouse** (owns record knowledge): `exportSorted(Path)` writes a `scan.run` —
   `[magic "SRUN"][count]` then count × (key, value) through the store's own
   `SpillSerializer`s (the same framing the wire and replication ride), CRC32 trailer in the
   house shape. `scanSorted(bytes | path, opts, consumer)` reads one back: CRC-verified
   first, then streamed to the consumer in key order. Export costs one ordered walk at
   export time — paid when the data is hot, which is the right time to pay it.
2. **Jerky** (owns the archive walk, still zero-dependency): `names(archive)` and
   `extract(archive, name)` — targeted single-entry extraction that CRC-verifies the whole
   body (it is already in memory; v1's whole-body CRC makes this free) and inflates ONLY the
   requested entry, skipping the others by their framed compressed length. The v1 framing
   supported this from birth; it just had no caller.
3. **DryAge** (owns preservation): `preserveWithScanRun(store)` — same preserve, plus the
   sorted run exported into the staging directory *before* the atomic move, so the
   generation carries its scan run **from birth** and the vault's founding rule (history's
   bytes never change) is never bent by a post-hoc write. Recovery is indifferent: segment
   files are pattern-matched (`seg-\d{8}.log`), so the sidecar is invisible to `asOf` and
   `restore`.

A cold scan is then: `Jerky.extract(archive, "scan.run")` → `SmokeHouse.scanSorted(bytes,
opts, consumer)`. No inflate-everything, no recovery, no store, no index.

## Costs, stated

The run duplicates the live records inside the generation and the archive (compressed
alongside everything else), and export adds one ordered walk to each preserving call that
opts in. Opt-in is the mitigation: `preserve` without the run is unchanged, and a vault used
only for time travel pays nothing.

## Consequences

- The cold-scan benchmark gains a fourth phase (the sidecar route) and the experiment doc
  records the before/after.
- `WholeHog.preserveAndCure` opts in, and the organism gains `coldScan(archive, consumer)` —
  history, scanned without resurrection.
- The word "columnar" retires from the ledger: measured, reduced, and shipped as what it
  actually meant here.
