# v0.1.3 — Test input manifest

Media files are **not committed** (see [REPORT_GUIDELINE.md §2](../../docs/REPORT_GUIDELINE.md)).
This file records what was used so a run can be reproduced.

Place the real files in `reports/v0.1.3/input/` locally.

---

## Input files

| TC | Filename | Duration | Resolution / rotation | Size | sha256 | Stored at |
|----|----------|----------|-----------------------|------|--------|-----------|
| TC-01 | `run1mins.mp4` | 60.805 s | 3840×2160, rot 90° | 410,876,276 B (392 MiB) | TBD | operator device + local `input/` |
| TC-02 | `run1mins.mp4` | 60.805 s | 3840×2160, rot 90° | 410,876,276 B | TBD | same file as TC-01 |
| TC-03 | `run1mins.mp4` | 60.805 s | 3840×2160, rot 90° | 410,876,276 B | TBD | same file as TC-01 |
| TC-04 | `run4mins.mp4` | 273.680 s | 3840×2160, rot 90° | 1,926,404,520 B (1.79 GiB) | TBD | operator device + local `input/` |

Duration / size / rotation are read back from `perf_report.json` → `session.sourceVideo`, so they are
the values the pipeline actually saw, not the values a file browser reports.

TC-01 → TC-03 are the **same file** run against three different builds. That is what makes the
v0.1.2 → v0.1.3 comparison in [report.md §9](./report.md) a controlled one.

## Scene notes

| TC | People in frame | Lighting | Camera distance | Notes |
|----|-----------------|----------|-----------------|-------|
| TC-01–03 | TBD — see OQ-01 | TBD | TBD | 63–79% of sampled frames report `no_subject`; whether that is genuine absence has **not** been confirmed by watching the clip |
| TC-04 | TBD — see OQ-01 | TBD | TBD | Denser subject presence than `run1mins` (`no_subject` 63% vs 79%) |

Confirming the scene notes is [NA-04](./report.md#11-verdict--next-actions) and is what closes OQ-01.

## How to record the checksum

```bash
shasum -a 256 reports/v0.1.3/input/<file>.mp4
```

## Output collected

| TC | Output folder (device) | Pulled to |
|----|------------------------|-----------|
| TC-01 | `DCIM/AutoBots/ext_10082026_1800/` | `reports/v0.1.3/output/TC-01/` |
| TC-02 | `DCIM/AutoBots/ext_11082026_1101/` | `reports/v0.1.3/output/TC-02/` |
| TC-03 | `DCIM/AutoBots/ext_11082026_1121/` | `reports/v0.1.3/output/TC-03/` |
| TC-04 | `DCIM/AutoBots/ext_11082026_1228/` | `reports/v0.1.3/output/TC-04/` |

These folder names carry **no version tag** because every run above predates that change. Later
builds name the session `ext_v0_1_3_DDMMYYYY_HHMM`; both forms sit in the same directory and
`sync_gallery.sh` pulls either.

Pull with `./sync_gallery.sh` (or `--logs-only` while iterating). Each session folder carries
`session_log.txt` **and** `perf_report.json`; the JSON is the source for every number in the report.

## Runs excluded from the results table

| Folder | Why excluded |
|--------|--------------|
| `ext_11082026_1134` | `run4mins.mp4` on the build before the drain fix — 158 JPEGs written but **no** `session_log.txt` / `perf_report.json`. The failure itself is recorded as an observation in [report.md §9](./report.md#observations); the run has no machine-readable numbers to report. |
| `ext_11082026_1150` | Same input, same failure, on the build that carried the first drain fix. Root cause still open — see OQ-03. |
