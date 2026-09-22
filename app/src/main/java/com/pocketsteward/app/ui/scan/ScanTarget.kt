package com.pocketsteward.app.ui.scan

/**
 * What to scan, as offered on the Storage Scope screen (plan Section 16).
 * Distinct from [com.pocketsteward.app.storage.StorageScope], which is
 * about *access* (broad vs. one SAF tree) — this is about which starting
 * point within that access to actually walk.
 */
sealed interface ScanTarget {
    val label: String

    data object Downloads : ScanTarget {
        override val label = "Downloads"
    }

    data object Documents : ScanTarget {
        override val label = "Documents"
    }

    data object Pictures : ScanTarget {
        override val label = "Pictures"
    }

    data object Everything : ScanTarget {
        override val label = "All accessible files"
    }

    /** The root of the persisted SAF grant. */
    data class GrantedFolder(override val label: String) : ScanTarget

    /** A concrete subfolder inside the persisted SAF tree. */
    data class GrantedSubfolder(
        val documentUri: String,
        override val label: String,
    ) : ScanTarget

    /**
     * Spec 6c: any direct-storage folder the user picked through the in-app browser.
     * SAF subfolders use [GrantedSubfolder] so provider URIs remain typed.
     *
     * The label is the folder's own name rather than its full path, because
     * it appears in a scan summary header and in every plan goal string; the
     * full path is always one tap away in the browser that produced it.
     */
    data class CustomFolder(val absolutePath: String) : ScanTarget {
        override val label: String =
            absolutePath.trimEnd('/').substringAfterLast('/').ifBlank { absolutePath }
    }
}
