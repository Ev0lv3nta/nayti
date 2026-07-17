package app.nayti.storage

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction

@Entity(tableName = "lexical_fts")
@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
    prefix = [2, 3, 4],
)
data class LexicalFtsDocument(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val canonical: String,
    val stems: String,
    val identifiers: String,
)

@Entity(tableName = "trigram_fts")
@Fts5(tokenizer = FtsOptions.TOKENIZER_TRIGRAM)
data class TrigramFtsDocument(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val canonical: String,
)

@Dao
interface Fts5ProofDao {
    @Insert
    suspend fun insertLexical(rows: List<LexicalFtsDocument>)

    @Insert
    suspend fun insertTrigram(rows: List<TrigramFtsDocument>)

    @Transaction
    suspend fun insertDocuments(rows: List<LexicalFtsDocument>) {
        insertLexical(rows)
        insertTrigram(rows.map { TrigramFtsDocument(it.rowId, it.canonical) })
    }

    @Query(
        "SELECT rowid FROM lexical_fts " +
            "WHERE lexical_fts MATCH :query ORDER BY rank LIMIT :limit",
    )
    suspend fun lexicalIds(query: String, limit: Int): List<Long>

    @Query(
        "SELECT snippet(lexical_fts, 0, '<b>', '</b>', '…', 12) FROM lexical_fts " +
            "WHERE lexical_fts MATCH :query ORDER BY rank LIMIT 1",
    )
    suspend fun firstSnippet(query: String): String?

    @Query(
        "SELECT rowid FROM trigram_fts " +
            "WHERE trigram_fts MATCH :query ORDER BY rank LIMIT :limit",
    )
    suspend fun trigramIds(query: String, limit: Int): List<Long>

    @Query("SELECT count(*) FROM lexical_fts")
    suspend fun lexicalCount(): Long
}

@Database(
    entities = [LexicalFtsDocument::class, TrigramFtsDocument::class],
    version = 1,
    exportSchema = false,
)
abstract class Fts5ProofDatabase : RoomDatabase() {
    abstract fun searchDao(): Fts5ProofDao
}
