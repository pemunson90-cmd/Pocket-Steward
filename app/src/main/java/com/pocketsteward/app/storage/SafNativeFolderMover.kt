package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One provider mutation per journal step. Folders never fall back to recursive copy/delete. */
internal class SafNativeFolderMover(private val context: Context, private val documents: SafDocuments,
    private val protection: SafProtection) {
    suspend fun move(source: Uri, parent: Uri, name: String): MutationResult {
      return try {
        currentCoroutineContext().ensureActive()
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && name.none { it.isISOControl() }) { "Unsafe folder name." }
        check(source.authority == parent.authority) { "This provider cannot move an intact folder to another provider." }
        val node = documents.stat(source)
        check(node.directory && documents.stat(parent).directory) { "Folder source and destination parent must be directories." }
        val original = documents.location(source)
        val originalParent = Uri.parse((original.parent as FileRef.Saf).documentUri)
        val sameParent = documents.sameDocument(originalParent, parent)
        if (sameParent && node.name == name) return MutationResult.Success(FileRef.Saf(source.toString()), changed = false)
        check(!SafScopeAccess.contains(context, source.toString(), parent.toString())) { "A folder cannot be moved into itself or a descendant." }
        check(documents.children(parent).none { it.name.equals(name, ignoreCase = true) && it.id != node.id }) { "Refusing to overwrite an existing SAF folder or file: $name" }
        check(sameParent || node.name == name) { "Moving and renaming an intact folder requires two separately reviewed steps. Keep its name for this move." }
        val required = if (sameParent) DocumentsContract.Document.FLAG_SUPPORTS_RENAME else DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        check(node.flags and required != 0) { "The selected provider does not support ${if (sameParent) "renaming" else "native intact-folder moves"} for this folder. The original stays together." }
        protection.refusal(source)?.let { return MutationResult.Failure(it) }
        protection.refusalDestination(parent, name)?.let { return MutationResult.Failure(it) }
        currentCoroutineContext().ensureActive()
        val returned = if (sameParent) DocumentsContract.renameDocument(context.contentResolver, source, name)
            else DocumentsContract.moveDocument(context.contentResolver, source, originalParent, parent)
        check(returned != null) { "The provider did not return the moved folder identity; review its location." }
        // A move may return a URI using the old tree. Bind its opaque ID through the destination tree.
        val bound = DocumentsContract.buildDocumentUriUsingTree(parent, DocumentsContract.getDocumentId(returned))
        val actual = documents.location(bound)
        check(actual.name == name && documents.sameDocument(Uri.parse((actual.parent as FileRef.Saf).documentUri), parent)) {
            "The provider did not confirm the reviewed folder destination; inspect both locations."
        }
        MutationResult.Success(FileRef.Saf(bound.toString()))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        MutationResult.Failure(failure.message ?: "The provider could not move the intact folder.", failure)
      }
    }
}
