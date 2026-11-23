package com.papervault.scanner.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE inVault = 0 ORDER BY updatedAt DESC")
    fun observeLibrary(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE inVault = 1 ORDER BY updatedAt DESC")
    fun observeVault(): Flow<List<Document>>

    @Query("SELECT DISTINCT folder FROM documents WHERE inVault = 0 AND folder IS NOT NULL ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: Long): Flow<Document?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): Document?

    @Query("SELECT * FROM documents WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<Document>

    @Insert
    suspend fun insert(doc: Document): Long

    @Update
    suspend fun update(doc: Document)

    @Delete
    suspend fun delete(doc: Document)
}

class Converters {
    @TypeConverter fun fromSource(s: DocumentSource): String = s.name
    @TypeConverter fun toSource(s: String): DocumentSource =
        runCatching { DocumentSource.valueOf(s) }.getOrDefault(DocumentSource.IMPORT)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN folder TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Document::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "papervault.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
