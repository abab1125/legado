package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.KnowledgePoint
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgePointDao {

    @Query("SELECT * FROM knowledge_points ORDER BY updateTime DESC")
    fun flowAll(): Flow<List<KnowledgePoint>>

    @Query("SELECT * FROM knowledge_points WHERE id = :id")
    fun getById(id: Long): KnowledgePoint?

    @Query("SELECT * FROM knowledge_points WHERE category = :category ORDER BY sortOrder, createTime")
    fun getByCategory(category: String): List<KnowledgePoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg point: KnowledgePoint): List<Long>

    @Update
    fun update(vararg point: KnowledgePoint)

    @Delete
    fun delete(vararg point: KnowledgePoint)

    @Query("DELETE FROM knowledge_points WHERE id = :id")
    fun deleteById(id: Long)

    @get:Query("SELECT * FROM knowledge_points ORDER BY updateTime DESC")
    val all: List<KnowledgePoint>

    @Query("SELECT COUNT(*) FROM knowledge_points")
    fun count(): Int
}
