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
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE inVault = 0 AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun observeDocuments(query: String): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: Long): Flow<Document?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): Document?

    @Insert
    suspend fun insert(doc: Document): Long

    @Update
    suspend fun update(doc: Document)

    @Delete
    suspend fun delete(doc: Document)
}

class Converters {
    @TypeConverter fun fromSource(s: DocumentSource): String = s.name
    @TypeConverter fun toSource(s: String): DocumentSource = DocumentSource.valueOf(s)
}

@Database(entities = [Document::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "papervault.db").build()
    }
}
