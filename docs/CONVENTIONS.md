# Documentation conventions

How we write and organize Markdown for AutoBots Sports Camera.

---

## 1. File roles (one job per file)

| File | Question it answers | Audience |
|------|---------------------|----------|
| [`DOCS.md`](./DOCS.md) | Where do I start? | Everyone |
| [`PRD.md`](./PRD.md) | What must the product do? | Product + engineering |
| [`architecture.md`](./architecture.md) | How does the system work? | Engineering |
| [`IMPLEMENTATION.md`](./IMPLEMENTATION.md) | What slice do we build next? | Engineering |
| [`FIELD_SETUP.md`](./FIELD_SETUP.md) | How to place the tripod? | Operator / field |
| [`SCREEN.md`](./SCREEN.md) | What is on the Operator screen? | Everyone |
| [`PLATFORM_APIS.md`](./PLATFORM_APIS.md) | Which native / library APIs are used? | Engineering |
| [`STRUCTURE.md`](./STRUCTURE.md) | Where is the code? | Engineering |
| [`BUILD.md`](./BUILD.md) | How to build and install the APK? | Engineering / field |
| [`CHANGELOG.md`](./CHANGELOG.md) | What shipped in each version? | Everyone |
| [`ROADMAP.md`](./ROADMAP.md) | What is unscheduled later? | Planning |
| [`../CONTEXT.md`](../CONTEXT.md) | What words mean | Everyone |

Do **not** duplicate full content across files — link instead.

---

## 2. Naming: Phase vs Flow vs Passage step

Three different numbering schemes — do not mix them up.

| Label | Meaning | Example |
|-------|---------|---------|
| **Phase P0–P8** | MVP milestones (done) | P6 = Write Queue + Local Delivery |
| **Phase P9–P10** | Tripod hardening slices — see [IMPLEMENTATION.md](./IMPLEMENTATION.md) | P9c = Fixed Focus runtime |
| **Flow 1–N** | Product / design rule (replaces old ADR 000X) | Flow 15 = Fixed Focus default |
| **Passage step 1–10** | Runtime sequence for one runner (in PRD only) | Step 5 = Lean Burst fires |

**Do not use** `ADR`, `ADR 0007`, or zero-padded decision IDs in new docs.

---

## 3. Flow format (design rules)

Each Flow in `architecture.md` uses this template:

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
- **Diagrams:** ASCII pipeline in `architecture.md`; Mermaid only when ASCII is too cramped.
- **Status:** Use ✅ / 🔄 / ❌ for phase or feature status — not prose alone.
- **Archive:** Retired or historical drafts go under `archive/` with a note at the top — never treat as source of truth.

---

## 5. When to update which file

| Change | Update |
|--------|--------|
| New operator-facing behavior | `PRD.md` → acceptance criteria |
| New design rule | `architecture.md` → add/revise a **Flow** |
| New domain term | `CONTEXT.md` |
| New module / package | `STRUCTURE.md` |
| New camera/CV library or API surface | `PLATFORM_APIS.md` |
| App version bump | `gradle.properties` + `AutobotsApp.version` + [CHANGELOG.md](./CHANGELOG.md) |
| MVP phase done | `DOCS.md` phase table |
| New P9/P10 slice started or finished | `IMPLEMENTATION.md` + `DOCS.md` status |
| Deferred feature idea | `ROADMAP.md` |
| Tripod field procedure change | `FIELD_SETUP.md` |
| Threshold / default value change | `PRD.md` + `CONTEXT.md` if terminology shifts |

---

## 6. Links

- Use relative paths: `[architecture.md](./architecture.md)`.
- Root domain glossary: always `../CONTEXT.md` from `docs/`.
