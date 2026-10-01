# Pocket Steward dev7: organizing your downloads

This is a signed development update for dev6. Install it over the existing app; do not uninstall first. The organizing core works offline. Optional on-device model features depend on your phone's available Gemini Nano/AICore support.

## Start with your existing Uncertain folder

1. On Home, tap **Sort Uncertain**. The app scans configured inboxes and their existing checkpoints.
2. In Settings, enable **Content inspection** for document evidence and **Image analysis** if you want local image labels. If content inspection is off, the preview offers **Enable local content and rebuild**. Rebuilding replaces edits in that preview.
3. Review the proposed folders. **Strong** matches are selected initially. **Probable** matches need your review. Unresolved checkpoint files stay where they are.
4. Expand a destination card to inspect its files and evidence. Lists render as you scroll. Select or deselect files and groups.
5. Search unresolved files by name, evidence or excerpt. Assign matching files together to a project such as NSTL, Lilith or Stories, then choose a role. Known project choices show their full folder paths.
6. Review the rebuilt plan. Approve the selected changes. Tasks shows progress, failures, recovery and undo.

Use **Organize Downloads** for new loose downloads. That review can move unresolved incoming files to Downloads/Uncertain. It does not create Uncertain inside an existing checkpoint. Direct-access folders move intact; selected-folder access currently handles loose files only.

## Understand the review

- Project ownership takes priority over file type. A project's images can stay with its documents.
- A suggested image topic, camera-date event or tagged album is probable and initially unchecked.
- A collision does not overwrite an existing file. Conflicts remain reviewable.
- Strong-only selection and individual/group selection are controls over the preview. Nothing moves merely because you changed a project or role.
- Cancel planning to stop further preparation; completed private evidence can be reused next time. Back out of a completed preview and use **Resume review** from Explore to retain selections. Restoration after Android kills the process remains unfinished.

## Project folders and templates

Settings has six labeled folder fields: Manuscript, Notes, Drafts, Images, Versions and Archive. Enter a relative folder, optionally with subfolders, or leave a field blank to use the project root. Save a named template to reuse a layout. Templates apply to new project homes; existing remembered homes retain their organization preference.

Existing homes can use flat, versioned, category or project-role organization. Exact release-convention editing is still being completed; inspect release destinations before approving them.

## Other reviewed actions

- **Similar files:** compare candidates, choose a keeper, then preview archiving alternatives. These moves start unchecked.
- **Duplicates / Older versions:** keep a survivor and review recoverable alternatives using the existing workflows. Similarity alone does not authorize deletion.
- **Consolidate project homes:** available in Explore with direct access and at least two known homes. Choose the source and home to keep. Conflicts remain at source; empty source folders remain too.
- **Share sheet:** select one or multiple files in another app and share to Pocket Steward. Choose a destination and optional project title, inspect the copy preview, then approve. Keep the share screen open when the source granted temporary access. Tasks owns verified copying and history; originals are retained.
- **Saved workflows:** save an Ask request, Organize Inbox or Sort Uncertain recipe. Running it scans fresh storage and opens a new review.
- **Scheduled suggestions:** notify about newly discovered files and open a bounded project review. They do not move files in the background without approval.
- **Metadata / image review:** inspect dimensions, media tags, camera dates, APK metadata, archive samples and local image labels. Archive samples can be partial. ZIP, TAR and compressed TAR are supported; other formats may be unavailable.
- **Search / Ask / document audit:** existing content search and optional local model features remain available with their respective privacy and availability requirements.
- **Tasks, manifests, inventory export, protection and read-only file browsing:** continue through the existing app workflows. Use the no-sort marker to protect folders; explicit protection controls manage that marker.

## Limits of this checkpoint

The suite includes a synthetic 16,000-file planner case; the complete phone scan/index/UI journey has not been measured. Phone-specific Fold, restart, grant and recovery acceptance remains open. Backup/restore, persistent review drafts, inbox observation, optional external providers and other master-plan extensions are still being implemented. This checkpoint is not the declaration of a finished app.
