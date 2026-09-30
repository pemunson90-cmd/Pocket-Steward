# Smart organization upgrade

## Goal

Turn Downloads into a reviewed inbox. Suggest destinations in the main folders beside Downloads, then organize related files inside project folders. Keep project artifacts together even when file types differ. Unmatched files move to a single `Downloads/Uncertain` checkpoint, leaving it as the only filing folder in Downloads after a complete run.

## Proposed layout

```text
Internal storage/
  Download/
    Uncertain/
  Documents/
    NSTL/
      Manuscript/
      Notes/
      Drafts/
      Images/
      Versions/
      Archive/
    Lilith/
    Stories/
    Project Documentation/
  Images/
    Lilith/
    Landscape/
    Unsure/
```

These are examples, not folders to create unconditionally. Use an existing project home if one is known. Project identity takes precedence over file extension: a Lilith manuscript, its notes, and related artwork should be proposed as one project group. The role folder is a second decision made within that group. An unrelated landscape image may go to Images/Landscape. Unmatched files go to the `Uncertain` checkpoint through the same reviewed move plan.

## Decision rules

1. Scan the selected inbox and find existing destination folders before proposing new ones.
2. Identify project membership using remembered user choices, existing project homes, artifact metadata, filenames, indexed contents, and supporting cohort evidence, in that order.
3. Reject competing project matches instead of breaking a near tie by list order. Send those files to the `Uncertain` checkpoint.
4. Within a confident project, infer a role such as Manuscript, Notes, Drafts, Images, Versions, or Archive. An unsupported role stays at the project root or waits for review; never invent a role solely from timing.
5. Suggest destination trees under authorized storage only. Create folders only as part of the reviewed, validated plan.
6. Keep project groups together in review. A user can change a destination or leave any file in Downloads. Remember a correction only when the user explicitly requests it.
7. Preserve existing no-overwrite, protection, source-change, journal, history, and undo checks.

## Review screen

Show three clear sections: **Ready to file**, **Needs your choice**, and **Uncertain checkpoint**. Each project card shows a small destination tree, file count and size, and the evidence for each file. Label the origin and destination in plain language, for example `Downloads → Documents › NSTL › Drafts`. Mark new folders before the user runs the plan. Show the selected file count and keep the final Run action visible. The Now and After views must show the complete relevant hierarchy, including role folders.

## Acceptance checks

- Mixed NSTL text, PDF, and related image files stay together under the NSTL project, with roles proposed within that project.
- A Lilith release APK, notes, source archive, and artwork do not split into generic file-type folders.
- Unrelated landscape images are never pulled into a project merely because they arrived at the same time.
- Files matching both NSTL and Stories go to Downloads/Uncertain rather than being assigned by project-list order.
- Existing folders are reused; new folders and every move appear in the preview.
- A changed source, collision, or protected destination blocks execution; an approved multi-file move can be undone.
- A narrow phone screen shows destination and status without clipped actions or hidden files.

## Current implementation status

This checkout implements nested Documents project discovery and creation, deterministic role proposals for recognized filenames and file types, ambiguity holdback, a reviewed Downloads/Uncertain checkpoint, and a review layout grouped by project. The role rules are an initial pass; they do not yet infer rich topics such as "Landscape" from image pixels or reliably identify every manuscript from document contents. The top-level Images taxonomy and the full phone acceptance checks remain open.
