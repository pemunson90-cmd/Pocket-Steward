**Pocket Steward 1.4.0-dev6: complete user walkthrough**

This guide follows the controls in dev6. The tested app source is tagged [v1.4.0-dev6](https://github.com/pemunson90-cmd/Pocket-Steward/tree/v1.4.0-dev6). [Release and signed download](https://github.com/pemunson90-cmd/Pocket-Steward/releases/tag/v1.4.0-dev6).

**1. Set up storage access**

Open Settings and read the Storage card. For filing Downloads into Documents or Images beside it, use **Full storage access**. If needed, choose **Change access** and grant the Android all-files permission.

**Selected-folder access** works inside the folder Android grants to the app. It cannot move items into sibling Documents or Images folders outside that grant. Dev6's intact-folder filing is available in full-storage mode; selected-folder inbox filing currently handles loose files.

Downloads is the default inbox. You do not need a cloud API key for the organizing features described here.

**2. Prepare your project folders**

Open **Files**, navigate to internal storage, open or create **Documents**, and use **New folder** to create the projects you actually use: NSTL, Lilith, Stories, Project Documentation, and others.

The organizer discovers project folders directly inside Documents. It can also propose new project homes from repeated filename families or explicit rules. Existing homes are preferred; creating them yourself makes your desired names clear.

For better matching inside documents, turn on **Settings → Inspect document contents**. Optionally turn on **Index document contents while charging**, then charge the phone to build the local content index. Filename matching works before this indexing finishes.

**3. Organize Downloads: your main workflow**

From Home, tap **Organize Downloads**. The app scans the configured inboxes and builds a proposal; it does not move files just because you opened it.

Review each project card. Tap **Files** to expand its contents, read the evidence, and check where the files will go. Related mixed file types can belong to one project. Within a project, dev6 proposes Manuscript, Notes, Drafts, Images, Versions, and Archive roles when their names or types support those roles. A file without a recognized role can remain at the project root.

For example, NSTL-manuscript.txt, NSTL-notes.md, and NSTL-cover.jpg can share Documents/NSTL while using Manuscript, Notes, and Images beneath it. These are examples, not a guarantee that every vaguely named document can be understood.

**Strong match** items are selected by default. **Probable match** items wait unchecked for your decision. Unresolved items are proposed for **Downloads/Uncertain** and selected in the initial checkpoint plan. Conflicting project evidence goes to that checkpoint rather than choosing a project arbitrarily.

Use **List** to inspect actions, **Now** to see the current arrangement, and **After** to see the arrangement for the selected actions. Use checkboxes to include or exclude individual items. **Strong only** selects the strongest project matches; check the checkpoint selections again afterward if you want unresolved items moved too. **Select all** includes all accepted actions and can request extra confirmation for quarantine actions. **Clear** removes the selection.

Where an **Edit destination** or **Edit action** control is offered, make a correction and tap **Apply destination**. New-project and checkpoint groups do not always offer the same group editor; destination editing is not universally available on every card.

Tap **Run [count]** when the selected paths look right. The count refers to the selected source items for inbox filing, including whole folders; it is not a count of every descendant inside a folder.

Existing source folders move intact rather than being dismantled into role folders. Their reviewed contents metadata is checked again before moving and saved for task resume. The app can keep an intact bundle within a project instead of merging it into existing children. It does not automatically merge conflicting folder trees.

The intended end state is Downloads/Uncertain. Unchecked, protected, inaccessible, changed, or colliding items can remain elsewhere in Downloads. Review the result for these exceptions. Existing Uncertain contents are left alone by the top-level inbox pass.

**4. Work through Uncertain**

Open **Files → Downloads → Uncertain** and inspect the items. To place something manually, long-press it, tap **Move**, navigate to the correct folder, and tap **Move [item] here**. Review the resulting proposal and run it.

For recurring project terms, add a project keyword rule as described below, then run Organize Downloads on new inbox arrivals. Items already inside Uncertain are not automatically reprocessed by the top-level Downloads pass; use manual moves or Explore with Uncertain selected when reviewing that folder.

The current image-topic filing suggestions use names such as landscape, mountain, portrait, or screenshot and require confirmation. They do not interpret pixels to identify Lilith artwork or a landscape automatically. **Image understanding**, described below, is a separate local analysis feature.

**5. Teach it your projects**

In Settings, turn on **Advanced** to reveal **Project keyword rules**. Use one term=project-name rule per line, for example:

```text
NSTL=NSTL
Lilith=Lilith
Stories=Stories
Project Documentation=Project Documentation
```

Tap **Save rules**. These give recognizable terms a project destination. Narrow, distinctive terms work better than a broad word such as “notes,” which many projects share.

Settings also offers **Learned correction rules**. These outrank project keywords and model suggestions. Where a preview editor offers **Remember this correction**, check it only if you want that filename term to guide future organization. Correction editors are not present on every inbox card.

**Project homes** records a project's actual location, aliases, optional APK package names, and layout. Approved filing can remember project homes automatically. Discovered Documents projects use the role layout. For an existing project folder you want to configure explicitly, a full-storage example is:

```text
NSTL=/storage/emulated/0/Documents/NSTL ; strategy=PROJECT_ROLES
Lilith=/storage/emulated/0/Documents/Lilith ; strategy=PROJECT_ROLES
Stories=/storage/emulated/0/Documents/Stories ; strategy=PROJECT_ROLES
Project Documentation=/storage/emulated/0/Documents/Project Documentation ; strategy=PROJECT_ROLES
```

Create these directories first, then tap **Save project homes**. The shown path is the common Android internal-storage path; use the actual path on your phone if different. Keep any existing saved entries you still want when editing this list. `PROJECT_ROLES` uses role subfolders; `VERSIONED` uses release folders; `FLAT` keeps files directly in the project home. An alias can help another name identify the same project, e.g. `; aliases=NSTL Novel`.

**Favorite destinations** puts frequently used roots in destination choices. The format is one name=/absolute/path per line. **File inboxes** lets you watch additional landing folders using that same format; Downloads already works by default. Save the relevant setting after editing.

**6. Tidy Downloads locally and Smart cleanup**

**Home → Tidy Downloads locally** organizes loose files within Downloads. It does not perform the sibling-folder filing workflow you want; choose Organize Downloads for that.

**Explore → choose folders → scan → Smart cleanup** builds a local organization proposal for the selected roots. **Include nested files** is off by default to preserve existing structures. Turning it on makes files already inside subfolders eligible for that cleanup proposal. Review its After view before running.

**7. Browse and manage files manually**

In **Files**, tap a folder to enter it and tap a file to open it using an appropriate installed app. Use the breadcrumb path to go back to a parent. Categories include Images, Videos, Audio, Documents, Apps, Archives, and Recent; these are views of the library, not instructions to relocate the files.

Use **More options** to sort by Name, Date, Size, or Type, reverse the active sort, switch list/grid view, or show/hide hidden files. Long-press an item to select it and tap more items to select them too. The action bar can scroll horizontally on a small phone.

- **Move:** select items, tap Move, enter the destination folder, tap Move here, review, and run.
- **Copy:** select files, tap Copy, enter the destination, tap Copy here, review, and run. Folder copy is not supported.
- **Rename:** select one item, tap Rename, enter its new name, and review the proposal.
- **Share:** select files and choose an Android receiving app. Sharing folders through this action is not supported.
- **Trash:** review the move into recoverable Trash; this is not permanent deletion.
- **Details:** select one item to inspect its information.
- **New folder:** open its intended parent and choose New folder, enter the name, and review creation.

Where an operation offers an Undo snackbar, use it immediately or find the task later in Tasks. The app can also appear as a file picker when another Android app asks you to choose files.

**8. Search filenames and document contents**

In **Files**, tap Search and enter a filename term or words from a document. Document content search needs content inspection enabled and available indexed text. Results can show passages and page numbers; **Show in folder** locates the original item.

For search within specific roots, open **Explore**, scan those roots, and use the request box, e.g. **Find documents containing Lilith**. Indexed search offers sorting, filters for source folder/type/extension/path, result previews, **Refresh index**, **Pause indexing**, and **Save search**. Open saved searches from Home. Refreshing a search reads current material; clearing the content index in Settings removes cached search text, not the original documents.

Supported local extraction includes plain text and Markdown, common code/config files, DOCX, XLSX, PPTX, PDF text, and scanned-PDF OCR. Unsupported or unreadable files may not produce searchable content.

**9. Ask your files**

Tap **Home → Ask your files** and ask a question about indexed documents, such as “When does my lease end?” Read the answer and its numbered sources; tap a source to open the document.

When the compatible on-device model is available and enabled, it can produce an answer grounded in retrieved passages. Otherwise the feature can show the best-matching passages or an availability note. If the passages do not answer the question, it can say so. It does not guarantee an answer just because a filename seems related.

**10. Ask Pocket Steward to perform a file task**

The **Ask Pocket Steward** box on Home and the request box after an Explore scan accept bounded file commands. Home scans Downloads for the request; Explore applies the request to the roots you scanned.

Examples:

```text
Find documents containing Lilith
Show the largest files
Organize the obvious files and leave uncertain things alone
Rename old-notes.txt to Lilith-notes.txt
Rename files matching IMG to Vacation-{n}.{ext}
```

Batch renaming supports `{n}` for the sequence number, `{name}` for the original name stem, and `{ext}` for the extension. Read every proposed name; a template that would collide is refused. Move and copy requests need a file criterion and a destination, such as “Move PDFs older than 6 months to Documents/Archive”; destination access and validation still apply.

“Archive” commands can group existing archive files. They do not create a new ZIP or compress arbitrary folders. Natural-language organization commands use their own bounded planning path; use **Organize Downloads** for dev6's project-first inbox filing.

**11. Explore, largest files, old files, and uncategorized items**

Open **Explore**, select one or more roots or browse to a custom folder, and scan. You can pause a scan and resume its checkpoint. Results describe only those roots.

**Largest files** shows the top 50; **Older files/Old files** shows items not modified in six months. These are review lists, not automatic deletion. **Uncategorized/Review uncategorized** shows what the rules could not confidently classify; that classification review is separate from opening the physical Uncertain checkpoint folder.

**Continue last scan** reopens the saved inventory. If another app or file manager changed storage, use **Check storage for outside changes** in Explore results or **Refresh now** in Settings.

**12. Exact duplicates, older versions, and similar files**

**Find duplicates** on Home scans Downloads; **Duplicates** after Explore uses the selected roots. Duplicate sets are byte-identical SHA-256 matches. Inspect the keeper and the copies proposed for Trash, choose the keeper if offered, then choose **Propose trashing extra copies**, review, and run.

**Explore → Older versions** groups name variants such as Resume, Resume (1), and Resume_v2. Inspect the suggested newest keeper, then use **Propose moving older versions**. Older versions are moved aside rather than permanently deleted. Names and dates are evidence to review; they do not prove which draft you value most.

**Explore → Similar files** shows visually similar images and near-duplicate indexed documents. It is a review-only comparison. Similarity is not exact duplication and does not offer automatic trash actions.

**13. Understand documents, images, and metadata**

**Explore → Rich metadata** reads supported dimensions, media duration, APK package/version details, PDF page counts, EXIF data, and ZIP entry samples locally. A ZIP listing is inspection, not archive extraction.

**Explore → Image understanding** analyzes a bounded set of recent images for local labels and likely screenshots. Enable **Advanced → Image analysis** in Settings if needed. Labels below the confidence threshold may be omitted. This separate analysis view does not turn the Organize Downloads image-topic rules into automatic pixel-based project classification.

**Explore → Document audit** analyzes a representative sample of documents, using local extraction and on-device intelligence when available. Inspect its findings, then choose **Build organization proposal**. Pick Recommended Documents, keep inside the scan root, or an accessible existing destination; favorites and recent folders can be offered. Decide whether to include documents already inside folders, then tap **Review proposed moves**. An audit is a sample, not a guarantee that every document was read or understood.

**14. Protect folders**

Open a folder in Files and use **More options → Protect this folder**, or use **Explore → Protect folders**. Review the protection change before running it.

Protection creates the visible `POCKETSTEWARD-DO-NOT-SORT.md` marker. Protected folders and descendants are excluded/refused by automated mutations. Use the protection controls again to review removing protection. Avoid treating a protection marker as an ordinary file to organize.

**15. Tasks, results, pause, resume, and Undo**

Open **Tasks** from Home to see what ran, what moved, what failed, and what remains blocked. Select a task to inspect its result or **Manifest**, which records the operations and outcomes.

Use **Pause** on a running resumable task and **Resume** when offered. A paused task keeps its recorded plan; it does not invent new destinations on resume. If a source changed or a collision appeared, the app can require review.

Use **Undo** or **Undo task** to reverse recorded changes where possible. Read the undo result. **Retry blocked undo** is offered for eligible partial undo cases. Undo refuses to overwrite a new file or ignore protection; do not assume every operation can always be reversed after other apps alter storage.

**Settings → Trash** lists quarantined files and their original locations. Tap **Restore** or **Retry restore** when offered. Pocket Steward does not permanently delete these items. In selected-folder mode, quarantine is inside the granted tree under PocketSteward/Trash.

**16. Exports and importing a reviewed plan**

After Explore, **Export inventory** writes verified JSON and CSV listings into each selected root. **Export unresolved problem set** writes metadata-only Markdown and JSON for unresolved files. Those reports can contain filenames and paths; share them only when you intend to.

From a plan preview, **Export** creates a reviewed-plan export. **Home → Import reviewed plan** opens a Pocket Steward reviewed-plan JSON and rescans/revalidates it before anything can run. Import is not an instruction to execute stale paths blindly.

In **Tasks → Manifest**, use **Export Markdown**, **Export JSON**, **Share**, **Open manifest**, or **Show folder** where offered. These let you keep or inspect a record of the completed task.

**17. Saved workflows, saved searches, and periodic review**

After an Explore scan, enter a request and use **Save workflow** with a descriptive name. Run it later from **Home → Saved**. It reuses a workflow request and scope, not permission to silently change files without fresh review.

Use **Save search** on an indexed search and name it; open or remove it under **Saved searches** on Home.

In **Settings → Scheduled review suggestions**, enable **Periodic review**, set an interval in hours, optionally choose roots, and tap **Save schedule**. A blank roots list reuses previously scanned roots. Allow notifications if you want suggestions surfaced. This scans metadata and offers a review; it does not move files unattended. A Home **Scheduled review ready** card can be reviewed or dismissed.

**18. Background library, appearance, and intelligence settings**

**Keep my file library up to date** refreshes metadata periodically and when opening the app. **Refresh now** requests an immediate refresh. Initial categories/search can fill in while the first pass finishes; folder browsing remains available.

**Inspect document contents** allows local text extraction. **Index document contents while charging** enables inspection and builds search content on the charger. **Clear content index** removes cached text, not your files.

**On-device intelligence** allows compatible local semantic analysis. The model card reports Ready, Download required, Downloading, or Not available on this device. Use **Download on-device model** only when offered. Model availability depends on the phone; turning the switch on cannot make an unsupported phone compatible.

**Show thumbnails** enables local image/video/PDF previews. **Use wallpaper colors** uses the phone's Material You palette on supported Android versions. **Advanced** reveals metadata-indexing, image-analysis, keyword rules, and diagnostics controls. **Refresh diagnostics** shows counts, task timings, free storage, and device state for troubleshooting.

**A practical first run for your library**

Use full storage access, create Documents/NSTL, Documents/Lilith, Documents/Stories, and Documents/Project Documentation, add distinctive keyword rules, and enable document inspection. Run Organize Downloads, inspect each expanded project, confirm probable matches, check the Uncertain selections, compare Now and After, and run the selected changes. Read the result and Tasks record, then work through Uncertain manually. Use background content indexing on the charger to improve later searches and project evidence.
