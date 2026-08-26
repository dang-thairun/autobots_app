# Documentation conventions

How we write and organize Markdown for AutoBots Sports Camera.

---

## 1. File roles (one job per file)

| File | Question it answers | Audience |
|------|---------------------|----------|
| [`DOCS.md`](./DOCS.md) | Where do I start? | Everyone |
| [`OPERATOR_FLOW.md`](./OPERATOR_FLOW.md) | How does the operator use the current build? | Operator / field |
| [`PIPELINE_FLOW.md`](./PIPELINE_FLOW.md) | How does Plan B pipeline work? | Engineering |
| [`PRD.md`](./PRD.md) | What must the product do? | Product + engineering |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | How does the system work? | Engineering |
| [`IMPLEMENTATION.md`](./IMPLEMENTATION.md) | What slice do we build next? | Engineering |
| [`FIELD_SETUP.md`](./FIELD_SETUP.md) | How to place the tripod? | Operator / field |
| [`SCREEN.md`](./SCREEN.md) | What is on the Operator screen? | Everyone |
| [`PLATFORM_APIS.md`](./PLATFORM_APIS.md) | Which native / library APIs are used? | Engineering |
| [`STRUCTURE.md`](./STRUCTURE.md) | Where is the code? | Engineering |
| [`BUILD.md`](./BUILD.md) | How to build and install the APK? | Engineering / field |
| [`CHANGELOG.md`](./CHANGELOG.md) | What shipped in each version? | Everyone |
| [`REPORT_GUIDELINE.md`](./REPORT_GUIDELINE.md) (ไทย: [`_TH`](./REPORT_GUIDELINE_TH.md)) | How do I write a version report? | Everyone |
| `../reports/vX.Y.Z/report.md` | Did that version actually work, and how well? | Everyone + stakeholders |
| [`ROADMAP.md`](./ROADMAP.md) | What is unscheduled later? | Planning |
| [`../CONTEXT.md`](../CONTEXT.md) | What words mean | Everyone |

Do **not** duplicate full content across files — link instead.

---

## 2. Naming: Phase vs Flow vs Passage step

Three different numbering schemes — do not mix them up.

| Label | Meaning | Example |
|-------|---------|---------|
| **Phase B1–B4** | Plan B video pipeline slices — see [IMPLEMENTATION.md](./IMPLEMENTATION.md) | B1 = chunk record + extract + gallery |
| **Phase P0–P8** | MVP milestones (done) | P6 = Write Queue + Local Delivery |
| **Phase P9–P10** | Tripod hardening slices (v0.1 stills, paused) | P9c = Fixed Focus runtime |
| **Flow 1–N** | Product / design rule (replaces old ADR 000X) | Flow 15 = Fixed Focus default |
| **Passage step 1–10** | Runtime sequence for one runner (PRD v0.1 only) | Step 5 = Lean Burst fires |

**Do not use** `ADR`, `ADR 0007`, or zero-padded decision IDs in new docs.

---

## 3. Flow format (design rules)

Each Flow in `ARCHITECTURE.md` uses this template:

```markdown
### Flow N — Short title

**Rule:** One sentence — what the system must do.

**Why:** One or two sentences — trade-off / rationale.

**In code:** Optional pointer to module or class.
```

---

## 4. Writing style

- **Language:** Thai or English per section audience; PRD domain terms stay English (Passage, Kept Photo, …).
- **Headers:** One `#` title per file; use `##` / `###` below.
- **Tables:** Prefer tables for comparisons, phases, scope in/out.
- **Diagrams:** ASCII pipeline in `ARCHITECTURE.md`; Mermaid only when ASCII is too cramped.
- **Status:** Use ✅ / 🔄 / ❌ for phase or feature status — not prose alone.
- **Archive:** Retired or historical drafts go under `archive/` with a note at the top — never treat as source of truth.

---

## 5. When to update which file

| Change | Update |
|--------|--------|
| New operator-facing behavior | `PRD.md` (Plan B section) + [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) |
| New pipeline / worker behavior | [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) + `ARCHITECTURE.md` |
| New design rule | `ARCHITECTURE.md` → add/revise a **Flow** |
| New domain term | `CONTEXT.md` |
| New module / package | `STRUCTURE.md` |
| New camera/CV library or API surface | `PLATFORM_APIS.md` |
| App version bump | `gradle.properties` + `AutobotsApp.version` + [CHANGELOG.md](./CHANGELOG.md) + `reports/vX.Y.Z/report.md` |
| Plan B slice done | `IMPLEMENTATION.md` + `DOCS.md` phase table |
| MVP / P9/P10 slice (legacy) | `IMPLEMENTATION.md` + `DOCS.md` status |
| Deferred feature idea | `ROADMAP.md` |
| Tripod field procedure change | `FIELD_SETUP.md` |
| Threshold / default value change | `PRD.md` + `CONTEXT.md` if terminology shifts |

---

## 6. Links

- Use relative paths: `[ARCHITECTURE.md](./ARCHITECTURE.md)`.
- Root domain glossary: always `../CONTEXT.md` from `docs/`.

---

## 7. Plan B doc sync checklist

When changing pipeline behavior or operator UI, update **at least** these files (link — do not copy full prose):

| Change type | Files to update |
|-------------|-----------------|
| Chunk size / sample interval / sharpness | `shared/.../StreamResolution.kt` → `PIPELINE_FLOW.md`, `OPERATOR_FLOW.md`, `DOCS.md` quick pipeline, `CONTEXT.md` if new term |
| New worker or queue behavior | `PIPELINE_FLOW.md`, `ARCHITECTURE.md` §2, `PLATFORM_APIS.md`, `STRUCTURE.md` |
| Operator button / screen / chip | `SCREEN.md`, `OPERATOR_FLOW.md`, `PRD.md` Plan B acceptance |
| Gallery / log path | `PIPELINE_FLOW.md`, `OPERATOR_FLOW.md`, `BUILD.md`, `LocalDeliveryWriter` / `SessionAlbumNaming` |
| New Maven dependency | `gradle/libs.versions.toml`, `PLATFORM_APIS.md`, `STRUCTURE.md` §4 |
| Shipped slice | `IMPLEMENTATION.md`, `DOCS.md` phase table, `CHANGELOG.md` |
| App version release | `gradle.properties`, `AutobotsApp.version`, `CHANGELOG.md`, `reports/vX.Y.Z/report.md` ([REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md)) |

**Source of truth for numbers:** `StreamResolution.kt`, `VideoFrameProcessor.kt`, `SessionAlbumNaming.kt`.

### Drift guard (optional)

From repo root:

```bash
./scripts/check_docs_drift.sh
```

Fails if known-stale strings appear in `docs/` or operator UI tooltips (e.g. `20 MB`, `300 ms`, `VideoFaceProcessor`). Run before merging doc or pipeline changes.

---

## 8. Legacy docs

v0.1 stills content in `PRD.md`, `ARCHITECTURE.md`, `CONTEXT.md`, and `FIELD_SETUP.md` is **intentionally retained** — mark sections `[legacy]` or link to Plan B; do not delete without B4 decision.
