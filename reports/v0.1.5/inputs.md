# v0.1.5 — Test inputs

> Media files are **not** committed ([REPORT_GUIDELINE.md §2](../../docs/REPORT_GUIDELINE.md)).
> This file records what was used so the run can be repeated.

## TC-16 — source video

| Field | Value |
|-------|-------|
| Name | `2026-asicsmeta-full-1.mp4` |
| Resolution | 3840×2160 (4K), rotation 0 |
| Duration | 50:00 (`3,000,000 ms`; the splitter saw `2,991,657 ms`) |
| Size | 14.6 GB (`15,305,347,584` bytes as reported by the session) |
| Frame rate | TBD — not recorded in the session artifacts |
| `sha256` | TBD — file was streamed from a URL, never held whole on the device |
| Where it lives | Served over HTTP on the local network; URL not recorded |
| Ingest path | **Network URL**, streamed (byte-range), full length, no trim |

## TC-18 — source video

| Field | Value |
|-------|-------|
| Name | `input_1.MP4` |
| Resolution | 3840×2160, **rotation 90** — displays as 2160×3840 portrait |
| Duration | 29:50 (`1,790,400 ms`; the splitter saw `1,753,280 ms`) |
| Size | 11.9 GB (`12,523,286,528` bytes as reported by the session) |
| `sha256` | TBD |
| Where it lives | `/sdcard/Download/input_1.MP4` on the test device |
| Ingest path | **Browse Video** (local file), full length, no trim |
| Note | Started immediately after TC-16; the device had not cooled down (`cpuMaxFreqKhz` opened at 2,016,000 vs 2,572,800) |

## TC-17 — upload target

| Field | Value |
|-------|-------|
| Backend | `https://api.photo.thai.run/graphql` · `https://upload.photo.thai.run/success` |
| Platform | `thai` |
| Event | `test-upload` (`694a4a3cd36bc1e3bd5c5f78`) |
| Account | `thairundev` (role `photographer`) |
| Provider | `gs` — the only accepted `CloudUploadProvider` value |

## Output produced

| What | Where | Size |
|------|-------|------|
| Photos (TC-16) | `DCIM/AutoBots/ext_v0_1_5_21082026_1344/` | 6,578 files, 7.2 GB |
| Photos (TC-18) | `DCIM/AutoBots/ext_v0_1_5_21082026_1532/` | 1,829 files |
| Upload queue rows | **deleted 21/08 16:16** while testing *Clear queue* — TC-19's per-row detail is gone | — |
| `perf_report.json` (TC-16) | `Download/AutoBots/ext_v0_1_5_21082026_1344/` | 4.8 MB |
| `session_log.txt` (TC-16) | `Download/AutoBots/ext_v0_1_5_21082026_1344/` | 295 KB |
| `perf_report.json` (TC-18) | `Download/AutoBots/ext_v0_1_5_21082026_1532/` | 3.3 MB |
| `session_log.txt` (TC-18) | `Download/AutoBots/ext_v0_1_5_21082026_1532/` | 111 KB |
| Upload queue | `databases/autobots_upload.db` (app-private) | 3.1 MB |

> Session artifacts are written to `Download/`, not next to the photos: MediaStore refuses
> `RELATIVE_PATH` under `DCIM` for non-media files on API 29+.

## How to collect them

```bash
S=ext_v0_1_5_21082026_1344
adb pull /sdcard/Download/AutoBots/$S/perf_report.json
adb pull /sdcard/Download/AutoBots/$S/session_log.txt
adb shell "run-as com.autobots.camera cat databases/autobots_upload.db" > queue.db
```
