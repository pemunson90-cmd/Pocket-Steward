package com.pocketsteward.app.storage

/**
 * Conservative preflight for operations that must create a second byte-for-byte
 * copy before they can commit. This is advisory safety, not the final write
 * guarantee: the filesystem can still change after the check, so gateways
 * retain their ordinary exception/rollback handling.
 */
object StorageCapacityPolicy {
    fun canFit(requiredBytes: Long, usableBytes: Long): Boolean {
        if (requiredBytes < 0L || usableBytes < 0L) return false
        return requiredBytes <= usableBytes
    }

    fun failureMessage(requiredBytes: Long, usableBytes: Long): String =
        "Not enough free space for this operation. " +
            "Needs at least $requiredBytes bytes; destination reports $usableBytes bytes available."
}
