package com.pocketsteward.app.content.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentIndexClearAcceptanceTest {
    @Test fun clearingWhileReadingRetainsUsableDaosAndRemovesFtsAndScopeRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ContentSearchDatabase::class.java).build()
        try {
            val dao = db.contentIndexDao()
            val coordination = ContentIndexCoordination()
            val readers = List(8) {
                async(Dispatchers.IO) { repeat(100) { dao.countDocuments(); dao.countSegments(); yield() } }
            }
            repeat(20) { index ->
                val ref = "/Downloads/$index.txt"
                val doc = IndexedDocument(ref, "/Downloads", "$index.txt", "/Downloads", "txt", "DOCUMENT",
                    6, 100, "evidence-sample-v1:test", "PLAIN_TEXT", "INDEXED", null,
                    ContentIndexPolicy.EXTRACTOR_VERSION, 100, 1)
                coordination.commit(coordination.epoch()) {
                    dao.replaceDocument(doc, listOf(IndexedSegment(stableRef = ref, ordinal = 0, pageNumber = null, ocr = false, body = "Lilith")))
                }
                coordination.clear { dao.clearAll() }
                assertTrue(db.isOpen)
                assertEquals(0, dao.countDocuments())
                assertEquals(0, dao.countSegments())
                assertTrue(dao.getStableRefsForRoot("/Downloads").isEmpty())
                assertTrue(dao.searchRows("Lilith", listOf("/Downloads"), 10).isEmpty())
                // Use a previously retained DAO, not a newly constructed singleton.
                dao.replaceDocument(doc, emptyList())
                assertEquals(1, dao.countDocuments())
            }
            readers.awaitAll()
        } finally { db.close() }
    }
}
