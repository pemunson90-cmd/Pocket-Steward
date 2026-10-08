package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** A provider document ID is opaque; only a provider path can establish membership in a grant. */
object SafScopeAccess {
    /** Retain the exact document ID, but use the currently authorized grant for reads. */
    fun bind(context: Context, grant: String, ref: String): FileRef.Saf {
        require(contains(context, grant, ref)) { "Document is outside the currently accessible grant." }
        val source = Uri.parse(ref)
        val id = if (DocumentsContract.isDocumentUri(context, source)) DocumentsContract.getDocumentId(source)
            else DocumentsContract.getTreeDocumentId(source)
        return FileRef.Saf(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(grant), id).toString())
    }

    fun contains(context: Context, grant: String, ref: String): Boolean = try {
        val tree = Uri.parse(grant)
        val source = Uri.parse(ref)
        if (!DocumentsContract.isTreeUri(tree) || source.authority != tree.authority) false
        else {
            val id = if (DocumentsContract.isDocumentUri(context, source)) DocumentsContract.getDocumentId(source)
                else if (DocumentsContract.isTreeUri(source)) DocumentsContract.getTreeDocumentId(source) else error("Not a provider document")
            val route = DocumentsContract.findDocumentPath(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, id))?.path
            val rootId = if (DocumentsContract.isDocumentUri(context, tree)) DocumentsContract.getDocumentId(tree) else DocumentsContract.getTreeDocumentId(tree)
            route?.lastOrNull() == id && rootId in route.orEmpty()
        }
    } catch (_: Exception) { false }
}
