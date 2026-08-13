# Version Report Guideline

How to write a **version summary report** for AutoBots Sports Camera.

A version report answers one question: **did this version actually work, and how well?**
It is written in **English**, after a version is built and field-tested.

> ฉบับภาษาไทย: [REPORT_GUIDELINE_TH.md](./REPORT_GUIDELINE_TH.md) — same content.
> **This English file is authoritative** if the two ever disagree; update it first, then mirror to TH.

Related: [CONVENTIONS.md](./CONVENTIONS.md) (doc rules) · [CHANGELOG.md](./CHANGELOG.md) (what shipped) · [DOCS.md](./DOCS.md) (index)

---

## 1. Why this file exists

| File | Question it answers | Written when |
|------|---------------------|--------------|
| [`CHANGELOG.md`](./CHANGELOG.md) | What shipped in this version? | At release |
| `reports/vX.Y.Z/report.md` | **Did it work? How well? What is still open?** | After field test |

They are **not** the same document. The changelog is a feature list. The report is evidence:
inputs, expectations, measured results, a score, and a verdict.

Rule from [CONVENTIONS.md §1](./CONVENTIONS.md) applies: **do not copy prose between files — link instead.**
A report links to `PIPELINE_FLOW.md` for how the pipeline works; it does not re-explain it.

---

## 2. Folder layout

One folder per version. Folder name = `appVersionName` in `gradle.properties`, prefixed `v`.

```
reports/
  .gitignore
  v0.1.2/
    report.md      ← the report            (committed)
    inputs.md      ← test input manifest   (committed)
    input/         ← source video files    (git-ignored)
    output/        ← produced JPEG, session_log.txt (git-ignored)
    logs/          ← raw logcat            (git-ignored)
    assets/        ← screenshots used by report.md (committed, keep small)
```

### Media files are not committed

Test videos and output galleries are large binaries. Committing them bloats the repo permanently.

- Keep the real files in `input/` and `output/` locally (or on shared storage).
- Commit `inputs.md` instead — it records filename, duration, resolution, size, `sha256`, and where the file lives.
- Paste only **short log excerpts** into `report.md`. Full logs stay in `logs/`.
- Screenshots in `assets/` are fine if each is under ~500 KB.

---

## 3. Report structure

Use these sections, in this order, with these headings. Skip nothing — write `TBD` if unknown.

| # | Section | Content |
|---|---------|---------|
| 1 | **Snapshot** | One table: version, date, phase, verdict, score, tester, build. A reader with 30 seconds reads only this. |
| 2 | **What & why** | What this version does, and the problem it solves. 5–10 lines. Link to `PRD.md` / `IMPLEMENTATION.md`. |
| 3 | **Scope** | In scope / out of scope. State exclusions explicitly. |
| 4 | **Open questions** | Decisions not yet made: question, options, impact, owner. This is the "still thinking about it" section. |
| 5 | **How it works** | One diagram + short walkthrough. Link to `PIPELINE_FLOW.md` for detail. |
| 6 | **Test setup** | Device, Android version, app build, settings used (resolution, extraction target). |
| 7 | **Test inputs** | Table of test cases with IDs `TC-01`, `TC-02`, … Each row = one input. |
| 8 | **Expected results** | Same `TC-` IDs. Each expectation must be **measurable** (a number, a file count, a state). |
| 9 | **Actual results** | Same `TC-` IDs, side by side with expected. Status `PASS` / `PARTIAL` / `FAIL` and the delta. |
| 10 | **Score** | Weighted rubric — see §5. |
| 11 | **Verdict & next actions** | 3–5 bullets, then a table of follow-up actions with owner and target version. |
| 12 | **Review comments** | Feedback from other people: who, date, comment, status. |

### Rules that make reports readable

- **One TC ID across §7, §8, §9.** `TC-01` in inputs must be `TC-01` in expected and actual. This is what lets a reader diff them.
- **Expectations are numbers, not adjectives.** `≥ 40 JPEG produced` — not `good face recall`.
- **Status vocabulary is fixed:** `PASS` · `PARTIAL` · `FAIL` · `BLOCKED` · `TBD`. Nothing else.
- **Every score cites evidence.** A score line must reference a TC ID or a log excerpt. No unsourced scores.
- **Unknown is `TBD`, never a guess.** A report with honest `TBD` is useful. A report with invented numbers is worse than no report.

---

## 4. Test case IDs

| Prefix | Meaning |
|--------|---------|
| `TC-NN` | Functional test case (an input run end to end) |
| `OQ-NN` | Open question (§4) |
| `NA-NN` | Next action (§11) |
| `RC-NN` | Review comment (§12) |

IDs are unique **within one report** and never renumbered after the report is shared.

---

## 5. Scoring rubric

Score each dimension **1–5**, multiply by weight, total out of 100.

| Dimension | Weight | 5 means | 1 means |
|-----------|--------|---------|---------|
| **Functional correctness** | 30 | All TCs PASS | Core flow does not complete |
| **Output quality** | 25 | Output is usable as-is for the end user | Output unusable / mostly wrong |
| **Stability** | 20 | No crash, no race, clean stop across all runs | Crashes or loses data in normal use |
| **Performance** | 15 | Meets throughput and thermal targets | Cannot keep up / device throttles out |
| **Operator UX** | 10 | Operator can run it in the field unaided | Requires developer to operate |

`weighted = score / 5 × weight` · `total = sum of weighted` (max 100)

### Verdict bands

| Total | Verdict | Meaning |
|-------|---------|---------|
| ≥ 85 | **Ship** | Release as is |
| 70–84 | **Ship with caveats** | Release, but known gaps documented and scheduled |
| 50–69 | **Needs work** | Do not release; specific fixes required |
| < 50 | **Blocked** | Core approach not proven; re-plan before more work |

If any dimension scores **1**, the verdict is capped at **Needs work** regardless of total.

---

## 6. Workflow

1. Version is built and installed — see [BUILD.md](./BUILD.md).
2. Create `reports/vX.Y.Z/` from the template in §7.
3. Fill §1–§8 **before testing**. Expectations written after seeing results are not expectations.
4. Run the tests. Collect output into `output/`, logs into `logs/`.
5. Fill §9–§11.
6. Share for review; record feedback in §12 as `RC-NN` with a status.
7. Update the row in [CHANGELOG.md](./CHANGELOG.md) / [DOCS.md](./DOCS.md) if the report changes what we believe shipped.

Per [CONVENTIONS.md §7](./CONVENTIONS.md), releasing a version means updating
`gradle.properties`, `AutobotsApp.version`, `CHANGELOG.md`, **and** adding `reports/vX.Y.Z/report.md`.

---

## 7. Template

Copy this into `reports/vX.Y.Z/report.md` and fill in.

```markdown
# vX.Y.Z — Summary Report

## 1. Snapshot

| Field | Value |
|-------|-------|
| Version | vX.Y.Z (`appVersionName` / `appVersionCode`) |
| Report date | YYYY-MM-DD |
| Phase | Bn — <name> |
| Theme | <one line> |
| Tester | TBD |
| Device | TBD |
| Verdict | TBD |
| Score | TBD / 100 |

## 2. What & why
**What this version does** — …
**Why** — …

## 3. Scope
### In scope
### Out of scope

## 4. Open questions
| ID | Question | Options | Impact | Owner | Status |
|----|----------|---------|--------|-------|--------|
| OQ-01 | | | | | Open |

## 5. How it works
<diagram> — detail in [PIPELINE_FLOW.md](../../docs/PIPELINE_FLOW.md)

## 6. Test setup
| Item | Value |
|------|-------|

## 7. Test inputs
| ID | Input | Duration | Resolution | Conditions | Settings |
|----|-------|----------|------------|------------|----------|
| TC-01 | | | | | |

## 8. Expected results
| ID | Expectation (measurable) | Source of the number |
|----|--------------------------|----------------------|
| TC-01 | | |

## 9. Actual results
| ID | Expected | Actual | Delta | Status |
|----|----------|--------|-------|--------|
| TC-01 | | TBD | TBD | TBD |

### Observations
### Log excerpts

## 10. Score
| Dimension | Weight | Score /5 | Weighted | Evidence |
|-----------|--------|----------|----------|----------|
| Functional correctness | 30 | TBD | TBD | |
| Output quality | 25 | TBD | TBD | |
| Stability | 20 | TBD | TBD | |
| Performance | 15 | TBD | TBD | |
| Operator UX | 10 | TBD | TBD | |
| **Total** | **100** | | **TBD** | |

**Verdict:** TBD

## 11. Verdict & next actions
| ID | Action | Why | Owner | Target |
|----|--------|-----|-------|--------|
| NA-01 | | | | |

## 12. Review comments
| ID | Reviewer | Date | Comment | Response | Status |
|----|----------|------|---------|----------|--------|
| — | — | — | _No comments yet._ | — | — |
```

---

## Related

- [REPORT_GUIDELINE_TH.md](./REPORT_GUIDELINE_TH.md) — ฉบับภาษาไทย
- [CONVENTIONS.md](./CONVENTIONS.md) — doc rules, Phase naming, doc sync checklist
- [CHANGELOG.md](./CHANGELOG.md) — what shipped per version
- [BUILD.md](./BUILD.md) — build and install the APK under test
- [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) — pipeline internals reports link to
