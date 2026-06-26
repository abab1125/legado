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

    @Query("SELECT * FROM writing_prompts WHERE bookUrl = :bookUrl ORDER BY sortOrder, createTime")
    fun flowByBook(bookUrl: String): Flow<List<WritingPrompt>>

    @Query("SELECT * FROM writing_prompts WHERE bookUrl = :bookUrl ORDER BY sortOrder, createTime")
    fun getByBook(bookUrl: String): List<WritingPrompt>

    @Query("SELECT * FROM writing_prompts WHERE id = :id")
    fun getById(id: Long): WritingPrompt?

    @Query("SELECT * FROM writing_prompts WHERE bookUrl = :bookUrl AND type = :type ORDER BY sortOrder, createTime")
    fun getByType(bookUrl: String, type: String): List<WritingPrompt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg prompt: WritingPrompt): List<Long>

    @Update
    fun update(vararg prompt: WritingPrompt)

    @Delete
    fun delete(vararg prompt: WritingPrompt)

    @Query("DELETE FROM writing_prompts WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM writing_prompts WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @get:Query("SELECT * FROM writing_prompts ORDER BY updateTime DESC")
    val all: List<WritingPrompt>

    @Query("SELECT COUNT(*) FROM writing_prompts WHERE bookUrl = :bookUrl")
    fun countByBook(bookUrl: String): Int
}
