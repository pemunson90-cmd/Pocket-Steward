# Pocket Steward dev11: organizing your downloads

This is a signed development update for dev6/dev7/dev8/dev9/dev10. Install it over the existing app; do not uninstall first. The organizing core works offline. Optional on-device model features depend on your phone's available Gemini Nano/AICore support.

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
- Cancel planning to stop further preparation; completed private evidence can be reused next time. Back out of a completed preview and use **Resume review** from Explore to retain selections. Saved drafts also restore after Android restarts the app. The save status is shown in the review. Restoring never executes changes; approval checks the original source baselines again. A changed storage mode or tree grant invalidates the draft.

## Project folders and templates

Settings has six labeled folder fields: Manuscript, Notes, Drafts, Images, Versions and Archive. Enter a relative folder, optionally with subfolders, or leave a field blank to use the project root. Save a named template to reuse a layout. Templates apply to new project homes; existing remembered homes retain their organization preference.

Existing homes can use flat, versioned, category or project-role organization. For a destination group, open **Assign project / role** and switch between the entire group and selected files only. Enter an optional **Release folder**, such as v1.4.0 or Draft 3. Project-role homes keep related assets under Versions/<release>, with their role folders inside. Existing folders move intact. Automatic versioned filing keeps an existing release folder’s spelling and v-prefix; competing folders remain uncertain.

## Discover existing project homes

With full storage access, refresh the library before preparing a filing review. Pocket Steward can recognize indexed folders outside Downloads that already contain a standard role folder such as Manuscript, Notes, Drafts, Images, Versions or Archive. This includes nested project folders beyond the immediate Documents folder. Discovery verifies the folder and its observed roles on disk, preserves their spelling, and excludes inboxes, checkpoints, hidden and private system locations. The review notes show how many layouts were verified.

Discovery checks up to 200 indexed candidates. Configure an important home explicitly in Settings if it is outside that coverage or uses custom roles. Saved project homes remain authoritative. Selected-folder access uses its existing grant-local discovery. Two homes with the same title stay unresolved until you choose the full destination path. Numbered project titles remain distinct; Project 1 does not match Project 17 merely by substring.

## Bulk content coverage

Filing reads up to 12,000 characters and eight PDF pages, with at most two OCR pages per PDF. Each review inspects at most 40 fresh PDFs; rebuilding can continue with previously uninspected PDFs. A failed PDF does not block new ones. The notes show partial coverage and deferred PDFs. Run the full content index when you want broader search coverage; it expands filing caches and can still report partial coverage for very large or unreadable documents.

Overlapping indexed folders share one document without losing either root membership. Refreshing Downloads does not erase content still indexed under Uncertain, and vice versa. Partial coverage is labeled in search results. There is no promise that the complete 16k workflow has been timed on your phone.

## Backup and restore

In **Settings → Backup and restore**, choose **Export verified backup** and select a new JSON file. The app reopens the file and checks its bytes. It saves project homes, templates, keywords, corrections, workflows, searches and preferences, plus up to 1,000 recent task summaries. File paths and request text are included. File contents, access grants, signing keys, active drafts, execution plans, mutation journals and content caches are excluded.

Choose **Choose backup to restore**, review its date and counts, then tap **Restore these settings**. This replaces saved preferences; your current task history and undo journals stay in place. Imported task summaries appear in an expandable read-only list. They cannot be resumed or undone. Background refresh and scheduled reviews remain off until you enable them again. Storage access must still be configured on a new phone. Keep copies of the files you intend to move: this settings backup is not a backup of your documents.

## Automatic inbox monitoring

In **Settings → Background library**, the **Inbox monitoring** card shows the exact watched folders and the last change noticed. With full storage access, Pocket Steward watches configured inboxes and their immediate Uncertain checkpoints while its process is running. A burst of downloads triggers one refresh after three quiet seconds; further refreshes are at least two minutes apart. Monitoring waits for an active move or undo task and for an existing requested refresh to finish. Queued refreshes require adequate battery and free storage. It inventories names, sizes and dates; content indexing follows your inspection and charging preferences. It never moves files unattended.

Turn off background library refresh to stop monitoring and its queued automatic refreshes. **Refresh now** remains available. Android may stop the process; the existing scheduled library refresh covers time away. Selected-folder access uses scheduled refresh because document providers do not offer equivalent reliable live events. Watch limits are 32 configured inbox roots and 64 folders in total; the card lists what is actually monitored. Monitoring does not recursively watch every subfolder.

## Project evidence and remembered corrections

An explicit field such as `Project: NSTL`, `Project title: Lilith` or `Series: Stories` in the first 16 lines of an inspected document can identify a new project home. Generic headings, incidental names, unsafe titles and competing project fields do not create a home. Suggestions still pass through the review and ordinary plan validation.

Automatically remembered filename terms exclude generic export names, roles, versions and opaque IDs. A term must occur in at least 80% of the files being taught; equally common alternatives do not create an automatic rule. Explicitly configured keywords and corrections remain available in Settings. Mappings match whole terms, so `story` does not match `history`. Remembered corrections take priority over inferred evidence. Conflicting remembered owners or two matching homes stay unresolved; semantic grouping also holds conflicting mappings rather than choosing the first.

## Image descriptions and readable screenshot text

Enable **Image analysis** to inspect images locally. The image review describes observed dimensions and orientation, lists model labels, and explains a screenshot suggestion. A filename hint is labeled as a hint. A portrait-shaped image plus detected interface text can support a screenshot suggestion; dimensions alone do not establish one. These are descriptions of observed evidence, not generated stories about an image or an identified person.

To read image text, also enable **Content inspection**. Bundled on-device OCR reads up to 4,000 text characters per image. The image review shows an excerpt and explains empty, unavailable or limited text coverage. Labels and text use separate private caches, so switching text inspection off does not return a cached OCR excerpt as visual-only evidence. Analysis verifies source metadata before reuse and after inspecting a new image. Bitmaps are bounded and remain alive until the underlying vision task finishes, including cancellation.

Filing can use detected text to suggest an existing project, a configured keyword home or an explicit Project field. OCR-only project suggestions are **Probable**, initially unchecked; confirm the text and ownership before approving. Conflicting homes stay uncertain. Related image files use the Images role even when named manuscript-cover or draft-preview; archive bundles use Archive and APK builds use Versions. Custom folder names for those roles still apply. Explicitly choosing a role remains available in the review.

## Current evidence in Search and Ask

Content search checks each candidate file's current name, size and modification time before displaying its cached excerpt. Missing, changed, unreadable or undated files do not supply current results. These checks read metadata; they do not silently reindex document text. Refresh the content index to include changed documents again.

Ask performs the same check before choosing passages, then checks cited sources again after the model returns. If a cited source changed during the answer, the answer is withheld and the app explains that current passages need another attempt. Leaving Ask cancels the request; a late model response cannot replace that cancellation. Optional local model availability still depends on the phone.

Filing and Coherence also refresh source metadata before reusing evidence. A proposed move retains the original evidence-time source baseline for approval checks.

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

The full master plan remains open. Further evidence coverage and selected-folder parity, broader release conventions, generative image captions, optional provider/runtime integrations and additional archives remain tracked. AppFunctions is conditionally deferred while its SDK is alpha-only; APPFUNCTIONS_DECISION.md records the official release evidence. Other work remains tracked in APP_COMPLETION_PROGRESS.md. Target-phone timing, Fold layouts, revoked permissions, thermal/battery behavior, reboot and cross-root undo still need device acceptance. Automated tests and instrumentation compilation do not substitute for those checks.
