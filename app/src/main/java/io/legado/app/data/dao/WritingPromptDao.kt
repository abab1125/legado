package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.WritingPrompt
import kotlinx.coroutines.flow.Flow

@Dao
interface WritingPromptDao {

    @get:Query("SELECT * FROM writing_prompts ORDER BY sortOrder, createTime")
    val all: List<WritingPrompt>

    @Query("SELECT * FROM writing_prompts ORDER BY sortOrder, createTime")
    fun flowAll(): Flow<List<WritingPrompt>>

    @Query("SELECT * FROM writing_prompts WHERE id = :id")
    fun getById(id: Long): WritingPrompt?

    @Query("SELECT * FROM writing_prompts WHERE type = :type ORDER BY sortOrder, createTime")
    fun getByType(type: String): List<WritingPrompt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg prompt: WritingPrompt): List<Long>

    @Update
    suspend fun update(vararg prompt: WritingPrompt)

    @Delete
    suspend fun delete(vararg prompt: WritingPrompt)

    @Query("DELETE FROM writing_prompts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM writing_prompts")
    fun count(): Int
}
