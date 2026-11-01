package com.example.arfas

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KbRepositoryClauseRetrievalTest {
    // Regression: task-shaped views must not become the lexical/specificity query.
    @Test fun exact_acronym_is_the_only_lexical_query() = runTest {
        val capture = mutableListOf<String>()
        val adapter = KbClauseSearchAdapter(
            denseReady = { false },
            hybridSearch = { _, _, _, _, _ -> error("Dense search must not run") },
            lexicalSearch = { literal, _, _ ->
                capture += literal
                GenericKnowledgePackRepository.SearchResult(hits = listOf(hit("direct")))
            }
        )
        val result = adapter.searchClause("What is EPBG?", listOf("EPBG definition purpose scope"), KbClauseIntent.EXPLANATION)
        assertEquals(listOf("What is EPBG?"), capture)
        assertEquals("direct", result.hits.single().chunkId)
        assertEquals(KbRetrievalMode.LEXICAL_ONLY, result.retrievalMode)
    }

    // Regression: forwarding dense expansion as query would pollute legacy BM25.
    @Test fun ready_dense_arm_keeps_literal_and_bounds_views() = runTest {
        var capturedLiteral = ""
        var capturedViews = emptyList<String>()
        var capturedIntent = KbClauseIntent.GENERAL
        val adapter = KbClauseSearchAdapter(
            denseReady = { true },
            hybridSearch = { literal, views, intent, pool, limit ->
                capturedLiteral = literal
                capturedViews = views
                capturedIntent = intent
                assertEquals(48, pool)
                assertEquals(12, limit)
                GenericKnowledgePackRepository.SearchResult(hits = listOf(hit("hybrid")))
            },
            lexicalSearch = { _, _, _ -> error("Unexpected fallback") }
        )
        val result = adapter.searchClause("What is EPBG?", listOf("", "What is EPBG?", " a ", "a", "b", "c", "d"), KbClauseIntent.EXPLANATION)
        assertEquals("What is EPBG?", capturedLiteral)
        assertEquals(listOf("a", "b", "c"), capturedViews)
        assertEquals(KbClauseIntent.EXPLANATION, capturedIntent)
        assertEquals("hybrid", result.hits.single().chunkId)
        assertEquals(KbRetrievalMode.HYBRID, result.retrievalMode)
    }

    // Regression: silently changing a continuation's literal loses provenance.
    @Test fun resolved_referent_is_dense_only_and_clause_is_searched_once() = runTest {
        var calls = 0
        var literal = ""
        var views = emptyList<String>()
        val port = object : KbClauseSearchPort {
            override suspend fun searchClause(literalClause: String, denseViews: List<String>, intent: KbClauseIntent, candidatePool: Int, finalLimit: Int): GenericKnowledgePackRepository.SearchResult {
                calls++
                literal = literalClause
                views = denseViews
                return GenericKnowledgePackRepository.SearchResult(hits = listOf(hit("direct")), retrievalMode = KbRetrievalMode.LEXICAL_ONLY)
            }
        }
        val result = KbClauseRetriever(port).retrieve(KbClause("C2", "Explain it in detail", KbClauseIntent.EXPLANATION, continuationOf = "EPBG"))
        assertEquals(1, calls)
        assertEquals("Explain it in detail", literal)
        assertTrue(views.size in 1..3)
        assertTrue(views.all { it.contains("EPBG") })
        assertEquals("C2", result.clauseId)
        assertEquals("direct", result.hits.single().chunkId)
        assertEquals(KbRetrievalMode.LEXICAL_ONLY, result.diagnostics.retrievalMode)
        assertEquals(views, result.diagnostics.denseViews)
    }

    @Test fun every_intent_has_bounded_nonblank_distinct_dense_views() = runTest {
        val port = object : KbClauseSearchPort {
            override suspend fun searchClause(literalClause: String, denseViews: List<String>, intent: KbClauseIntent, candidatePool: Int, finalLimit: Int): GenericKnowledgePackRepository.SearchResult {
                assertTrue(denseViews.size <= 3)
                assertTrue(denseViews.all { it.isNotBlank() && it != literalClause })
                assertEquals(denseViews.distinct(), denseViews)
                return GenericKnowledgePackRepository.SearchResult()
            }
        }
        for (intent in KbClauseIntent.values()) KbClauseRetriever(port).retrieve(KbClause("C1", "Explain X", intent))
    }

    @Test fun blank_clause_never_calls_either_retrieval_arm() = runTest {
        val adapter = KbClauseSearchAdapter(
            denseReady = { error("Blank query must stop before model access") },
            hybridSearch = { _, _, _, _, _ -> error("Unexpected dense access") },
            lexicalSearch = { _, _, _ -> error("Unexpected lexical access") }
        )
        assertTrue(adapter.searchClause("  ", listOf("an expansion"), KbClauseIntent.GENERAL).hits.isEmpty())
    }

    @Test fun invalid_limits_are_bounded_before_reaching_backend() = runTest {
        val captured = mutableListOf<Pair<Int, Int>>()
        val adapter = KbClauseSearchAdapter(
            denseReady = { false },
            hybridSearch = { _, _, _, _, _ -> error("Unexpected dense access") },
            lexicalSearch = { _, pool, limit ->
                captured += pool to limit
                GenericKnowledgePackRepository.SearchResult()
            }
        )
        adapter.searchClause("literal", emptyList(), KbClauseIntent.GENERAL, -5, 100)
        adapter.searchClause("literal", emptyList(), KbClauseIntent.GENERAL, 1000, -5)
        assertEquals(listOf(20 to 20, 64 to 1), captured)
    }

    @Test fun intent_mapping_preserves_task_shapes() {
        assertEquals(SemanticQueryIntent.EXPLANATION, KbClauseIntent.EXPLANATION.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.PROCEDURE, KbClauseIntent.PROCEDURE.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.ENUMERATION, KbClauseIntent.ENUMERATION.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.COMPARISON, KbClauseIntent.COMPARISON.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.VALUE_LOOKUP, KbClauseIntent.VALUE.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.MATH_CALCULATION, KbClauseIntent.CALCULATION.toSemanticQueryIntent())
        assertEquals(SemanticQueryIntent.GENERAL, KbClauseIntent.GENERAL.toSemanticQueryIntent())
    }

    // Real repository + real sidecar + real DB; no initialized model and no embedding reads.
    @Test fun dense_unavailable_still_returns_literal_bm25_database_rows() = runTest {
        withFixture { repository ->
            val result = repository.searchClause("What is EPBG?", listOf("unrelated procedure purpose"), KbClauseIntent.EXPLANATION, finalLimit = 1)
            assertEquals(listOf("epbg"), result.hits.map { it.chunkId })
            assertEquals("EPBG is the guarantee described by this fixture.", result.hits.single().content)
            assertEquals(KbRetrievalMode.LEXICAL_ONLY, result.retrievalMode)
            assertEquals(0f, result.topSimilarity)
            assertEquals(0f, result.hits.single().similarity)
            assertFalse(result.confident) // A lexical rank is not semantic confidence.
            assertTrue(result.hits.single().rankScore > 0f)
            assertEquals("Fixture source", result.hits.single().sourceName)
            assertTrue(result.packId.isNotBlank())
        }
    }

    @Test fun missing_lexical_match_stays_empty_without_dense_engine() = runTest {
        withFixture { repository ->
            val result = repository.searchClause("zyxwvnomatch", emptyList(), KbClauseIntent.GENERAL)
            assertTrue(result.hits.isEmpty())
            assertEquals(KbRetrievalMode.LEXICAL_ONLY, result.retrievalMode)
        }
    }

    @Test fun legacy_search_still_returns_empty_when_dense_unavailable() = runTest {
        withFixture { repository -> assertTrue(repository.search("What is EPBG?").hits.isEmpty()) }
    }

    private suspend fun withFixture(block: suspend (GenericKnowledgePackRepository) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "kb-clause-test-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(root.name + name, mode)
        }
        val pack = File(root, "knowledge_packs/active_generic_knowledge.db")
        pack.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(pack, null).use { db ->
            db.execSQL("CREATE TABLE knowledge_chunks (chunk_id TEXT PRIMARY KEY, doc_id TEXT, source_name TEXT, content TEXT, embedding BLOB)")
            db.execSQL("INSERT INTO knowledge_chunks VALUES (?, ?, ?, ?, ?)", arrayOf("epbg", "doc1", "Fixture source", "EPBG is the guarantee described by this fixture.", ByteArray(1536)))
            db.execSQL("INSERT INTO knowledge_chunks VALUES (?, ?, ?, ?, ?)", arrayOf("distractor", "doc2", "Other source", "Unrelated procedure purpose definition scope", ByteArray(1536)))
        }
        val engine = SemanticSearchEngine(context)
        val repository = GenericKnowledgePackRepository(context, engine)
        try {
            assertFalse(engine.isReady)
            assertTrue(repository.initialise())
            block(repository)
        } finally {
            repository.close()
            context.getSharedPreferences("fixsight_generic_kb", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    private fun hit(id: String) = GenericKnowledgePackRepository.Hit(id, null, "doc1", "Fixture source", null, null, null, null, "EPBG fixture", 0f, 1f, smallFragment = false)
}
