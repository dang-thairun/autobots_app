# v0.1.2 — Test input manifest

Media files are **not committed** (see [REPORT_GUIDELINE.md §2](../../docs/REPORT_GUIDELINE.md)).
This file records what was used so a run can be reproduced.

Place the real files in `reports/v0.1.2/input/` locally.

---

## Input files

| TC | Filename | Duration | Resolution / fps | Size | sha256 | Stored at |
|----|----------|----------|------------------|------|--------|-----------|
| TC-01 | TBD | 1 min | 1080p 30 | TBD | TBD | TBD |
| TC-02 | TBD | 1 min | 4K 30 | TBD | TBD | TBD |
| TC-03 | _live capture_ | ~3 min | 1080p | — | — | recorded on device |
| TC-04 | _live capture_ | ~20 s | 1080p | — | — | recorded on device |
| TC-05 | _live capture_ | ~10 min | 1080p | — | — | recorded on device |
| TC-06 | same file as TC-01 | 1 min | 1080p 30 | — | — | — |
| TC-07 | TBD (empty scene) | 1 min | 1080p 30 | TBD | TBD | TBD |

## Scene notes

| TC | People in frame | Lighting | Camera distance | Notes |
|----|-----------------|----------|-----------------|-------|
| TC-01 | TBD | TBD | TBD | Must be the **same scene** as TC-02 |
| TC-02 | TBD | TBD | TBD | Must be the **same scene** as TC-01 |
| TC-07 | 0 | TBD | TBD | Negative control |

## How to record the checksum

```bash
shasum -a 256 reports/v0.1.2/input/<file>.mp4
```

## Output collected

| TC | Output folder (device) | Pulled to |
|----|------------------------|-----------|
| TC-01 | `DCIM/AutoBots/ext_DDMMYYYY_HHMM/` | `reports/v0.1.2/output/TC-01/` |
| TC-03 | `DCIM/AutoBots/yyyyMMdd_HHmmss/` | `reports/v0.1.2/output/TC-03/` |

Pull with `./sync_gallery.sh`. Session logs come from `Download/AutoBots/{subfolder}/session_log.txt`.
