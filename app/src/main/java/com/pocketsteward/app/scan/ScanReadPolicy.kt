package com.pocketsteward.app.scan

import com.pocketsteward.app.storage.FileRef

/** Android private app containers are outside the direct shared-storage inventory. */
object ScanReadPolicy {
    fun excludedPrivateDirectory(storageRoot: String, ref: FileRef): Boolean {
        val direct = ref as? FileRef.Direct ?: return false
        val root = storageRoot.trimEnd('/')
        if (root.isBlank()) return false
        val path = direct.absolutePath.trimEnd('/')
        return listOf("$root/Android/data", "$root/Android/obb").any { path == it || path.startsWith("$it/") }
    }
}
