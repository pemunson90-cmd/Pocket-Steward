# M13 Repair Plan — Coherence Fallback, Persistent Search, and Destination-Aware Organization

Status: PLANNED / USER-DIRECTED
Base shipping head: `f336642035647f373f2e7b49c4cf1852d066f46b`
Created: 2026-09-20

## User-reported hardware findings

### 1. Coherence Audit fails on supported Prompt API hardware

Observed on hardware:

> Structured output is not available on this device/model configuration.

The device can expose the on-device Prompt API while the ML Kit Structured Output
feature is unavailable. The current implementation treats Structured Output as a
hard requirement, so Coherence Audit refuses even though ordinary on-device text
generation is usable.

### 2. Content search works but does not scale as a product surface

Observed on a Downloads scan of roughly 17,000 files:

- hundreds of content matches in one flat list;
- no sort controls;
- no filters;
- no saved/reopenable search state;
- repeated searches require re-reading/extracting the same unchanged files;
- current bounded inspection can stop early and report that results may be incomplete.

This is acceptable as a proof of content extraction, not as the long-term search UX.

### 3. Current root-local organization is wrong for real document cleanup

The old safety rule says each selected scan root organizes locally by default. That
was correct for early Smart Cleanup because it prevented accidental cross-root moves.

The user has now explicitly requested a different document workflow:

- documents found in Downloads should not be parked under `Downloads/Documents/`;
- the normal document destination should be the device-level Documents root, e.g.
  `/storage/emulated/0/Documents/`;
- that root should contain semantic/title-based subfolders rather than one giant
  undifferentiated Documents bucket;
- the user must be able to choose or override the destination.

This is an explicit, user-directed exception to root-locality, not a general removal
of that safety rule.

---

# Repair A — Coherence Audit without Structured Output dependency

## Required behavior

1. Keep Structured Output as the preferred path when
   `isStructuredOutputFeatureAvailable()` is true.
2. When Structured Output is unavailable but ordinary Prompt API generation is
   available, automatically use a deterministic plain-text protocol instead of
   refusing the audit.
3. The fallback must remain advisory. It must never gain filesystem authority.

## Fallback protocol

Use a rigid record format with one finding per line. Exact wire format may be TSV
or another escaping-safe line protocol, but it must carry:

- exact input document ID;
- classification:
  - BELONGS
  - QUESTIONABLE
  - DOES_NOT_BELONG
  - UNCERTAIN
- suggested group, optional;
- concise reason.

The parser must:

- reject unknown IDs;
- reject duplicate/conflicting records for the same ID;
- map unknown classifications to UNCERTAIN or discard them conservatively;
- enforce bounded lengths;
- sanitize suggested group names through the existing deterministic path rules;
- tolerate explanatory chatter by ignoring non-record lines;
- never interpret generated text as a filesystem path.

The downstream chain remains:

`on-device model -> deterministic response parser -> advisory finding -> SemanticPlanAdapter -> PlanValidator -> preview -> user selection -> durable task -> executor`

## Acceptance

- Coherence Audit works on hardware where Prompt API is available and Structured
  Output is not.
- The UI can indicate whether the audit used structured or fallback parsing in
  Advanced/details, but normal UI continues to say "On-device intelligence."
- Malformed model output cannot create a move operation.

---

# Repair B — Persistent content index and scalable search UX

## Product goal

Searching a large folder should become an incremental local index, not a repeated
full document-reading job.

A query such as:

`find documents containing pain`

should return immediately from already indexed content when possible, while only
new or changed files are re-extracted.

## Storage architecture

Create a separate app-private, rebuildable content-search database file rather than
putting raw extracted text into the mutation/journal Room database.

Recommended implementation:

`content_search.db`

It is derived cache, not authority. Deleting it loses search acceleration only and
must never affect files, plans, journals, Undo, or saved workflows.

Persist enough data to support:

- stable file ref;
- scan/source root;
- display name;
- extension/category;
- size;
- modified time;
- quick content fingerprint where needed;
- extraction kind:
  - plain text
  - OOXML
  - PDF embedded text
  - PDF OCR
- page number/provenance for PDF segments;
- extracted searchable text or FTS-backed normalized text;
- extraction/index version;
- last indexed time;
- unsupported/failure reason when useful.

Use a dedicated full-text-search table/index for query speed.

## Change detection / incremental refresh

On an index refresh:

1. New file -> extract/index.
2. Deleted file -> remove its search rows.
3. Same size + same modified time -> reuse cached content by default.
4. Metadata changed -> re-extract.
5. Where same-size/same-time false negatives are plausible, use a cheap fingerprint
   before deciding the cached content is unchanged.
6. Extraction-code/index-version changes invalidate only the affected cached rows,
   not the user's files.

The point is to check *what changed*, not reread 1,600+ unchanged documents for
every query.

## Large-scope behavior

Replace the current one-shot aggregate inspection ceiling with a resumable indexing
job.

Keep defensive per-file bounds such as maximum file size/page count/text size, but
do not make a large Downloads folder permanently unsearchable merely because the
first pass exceeded one aggregate byte/result budget.

Behavior while indexing is incomplete:

- search the portion already indexed;
- clearly show "Indexing incomplete" / indexed X of Y;
- continue indexing through the foreground/resumable job framework;
- do not pretend partial results are complete.

## Search result UX

Add a sticky search-results control row.

### Sort

At minimum:

- Relevance
- Filename A-Z
- Filename Z-A
- Modified newest
- Modified oldest
- Largest
- Smallest
- Path/folder

### Filters

At minimum:

- source/selected root;
- category/type;
- extension;
- PDF / OCR-derived / ordinary extracted text;
- modified date range;
- size range;
- folder/path;
- optionally "filename match" vs "content match" when both surfaces are combined.

### Result presentation

- one primary card per matching file;
- filename and parent folder visible;
- page number and OCR badge when applicable;
- concise snippet;
- collapsible extra snippets/pages rather than multiplying the whole page vertically;
- result count after filters;
- clear reset-filters action.

## Saved searches

Add saved/recent search sessions containing:

- query;
- selected roots;
- sort;
- filters;
- timestamp;
- result count at last refresh.

Opening a saved search queries the local index immediately, then performs a cheap
change check and refreshes only stale/new/deleted files.

Do not save an old result list as truth. Save the query/view state and the derived
content index.

## Privacy

- Content inspection toggle remains authoritative.
- Index stays on device in app-private storage.
- No cloud upload.
- Provide an explicit "Clear content index" control.
- Clearing the index does not touch source files.

---

# Repair C — Destination-aware document organization

## User-directed default

For documents originating in Downloads, stop treating:

`Downloads/Documents/`

as the preferred destination.

Preferred document root becomes the standard device Documents directory:

`/storage/emulated/0/Documents/`

or the equivalent resolved Android Documents directory.

This is a sibling of Downloads under shared storage, not a child of Downloads.

## Destination choice

Every organization flow that can cross roots must expose destination intent before
execution.

At minimum support:

1. Recommended destination
2. Keep inside current scan root
3. Choose existing folder
4. Create/select a destination folder

Remembered destinations may later be attached to Saved Workflows, but a remembered
destination remains a preference, not execution authority.

## Cross-root safety rule

Replace the blanket old rule:

> Each selected scan root organizes locally by default.

with:

> Root-locality remains the default unless the user explicitly selects, saves, or
> approves a destination outside the source root.

For the user-requested document workflow, `Documents` is an explicit approved
recommended destination.

No model may invent an absolute destination path.

## Semantic/title-based subfolders

Inside the chosen Documents root, propose a single meaningful subfolder level first.

Examples of intended shape:

```
Documents/
  NSTL/
  Pocket Steward/
  Resumes/
  Receipts/
  Manuals/
  Reference/
  Writing/
  Taxes/
```

The exact labels come from document evidence and user rules, not from a hardcoded
example list.

Evidence priority should be:

1. explicit user project keyword/rule;
2. existing known project/destination mappings;
3. strong filename/title signal;
4. document content classification;
5. on-device semantic suggestion;
6. uncertain -> leave ungrouped / ask rather than force a category.

Avoid deep AI-generated folder trees in the first version. One semantic layer under
the selected destination is reviewable; arbitrary nesting is not.

## User override in preview

Plan Preview should group proposed moves by semantic destination.

The user must be able to:

- change the destination for a whole proposed group;
- choose an existing folder;
- create/rename the proposed group folder;
- deselect individual files;
- leave uncertain files untouched.

A destination edit triggers deterministic revalidation before execution.

## Collision behavior

- never overwrite;
- show filename collision in preview;
- prefer leave-untouched or explicit rename proposal rather than silently suffixing;
- keep journal/Undo semantics unchanged.

## Example desired flow

```
Downloads/
  Patrick Munson Resume 2026.docx
  Pocket Steward notes.md
  NSTL story bible.pdf
```

Proposed result:

```
Documents/
  Resumes/
    Patrick Munson Resume 2026.docx
  Pocket Steward/
    Pocket Steward notes.md
  NSTL/
    NSTL story bible.pdf
```

The preview must show these cross-root destinations before anything moves.

---

# Suggested implementation order

## M13A — Search repair

- dedicated persistent content index;
- incremental invalidation/change checks;
- resumable indexing;
- search sort/filter UI;
- saved/recent searches;
- clear-index control.

This directly fixes the large Downloads hardware failure and removes repeated
document extraction cost.

## M13B — Coherence compatibility repair

- Structured Output preferred path;
- ordinary Prompt API deterministic fallback;
- hardware acceptance on current Fold configuration.

Can be developed independently from the content index but should consume indexed
text when available rather than re-extracting unchanged documents.

## M13C — Destination-aware semantic organization

- destination picker and remembered destinations;
- explicit cross-root plan support;
- Documents-root default for document cleanup;
- semantic/title-based one-level group proposals;
- group-level destination editing;
- normal validator/preview/durable foreground execution/Undo.

---

# Hardware acceptance additions

### Search/index

- Index the large Downloads scope.
- Stop/restart app during indexing.
- Verify index resumes.
- Run the same query twice; second query must not reread every unchanged file.
- Modify one indexed document and add one new document.
- Refresh; only changed/new rows should be re-extracted.
- Delete one indexed document; stale result disappears.
- Exercise every sort/filter combination on hundreds of results.

### Coherence

- Run on the current device/model where Structured Output is unavailable.
- Fallback audit returns usable findings.
- Inject/mimic malformed lines; no unsafe operation appears.
- Build a proposal from valid fallback findings and verify normal preview still gates it.

### Destination routing

- Scan Downloads.
- Propose document cleanup.
- Verify documents propose destinations under device Documents, not
  `Downloads/Documents`.
- Change one group's destination manually.
- Run selected operations.
- Verify journal, Tasks, Pause/Resume, and Undo across the cross-root move.
- Verify filename collision never overwrites.
