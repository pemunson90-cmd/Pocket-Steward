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

    /** The one SAF-granted folder, when access mode is SAF rather than broad. */
    // In SafStorageGateway.kt and ScanTarget.GrantedFolder

/**
 * 2026-09-19 (M8 Spec): SAF mode and storage gateway are retained intact as a browser 
 * fallback only. It is not supported for file mutations. Do not route operations here 
 * and do not remove SAF_UNSUPPORTED UI fencing.
 */

    data class GrantedFolder(override val label: String) : ScanTarget

    /**
     * Spec 6c: any folder the user picked through the in-app browser.
     * Direct mode only — SAF reaches exactly one granted tree, and narrowing
     * within it is what [GrantedFolder] already is.
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
