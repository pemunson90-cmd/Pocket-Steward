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
    data class GrantedFolder(override val label: String) : ScanTarget
}
