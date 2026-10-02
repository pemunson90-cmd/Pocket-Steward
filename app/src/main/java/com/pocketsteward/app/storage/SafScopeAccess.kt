package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** A provider document ID is opaque; only a provider path can establish membership in a grant. */
object SafScopeAccess {
    fun contains(context: Context, grant: String, ref: String): Boolean = try {
        val tree = Uri.parse(grant)
        val source = Uri.parse(ref)
        if (!DocumentsContract.isTreeUri(tree) || source.authority != tree.authority) false
        else {
            val id = if (DocumentsContract.isDocumentUri(context, source)) DocumentsContract.getDocumentId(source)
                else if (DocumentsContract.isTreeUri(source)) DocumentsContract.getTreeDocumentId(source) else error("Not a provider document")
            val route = DocumentsContract.findDocumentPath(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, id))?.path
            route?.lastOrNull() == id && DocumentsContract.getTreeDocumentId(tree) in route.orEmpty()
        }
    } catch (_: Exception) { false }
}
