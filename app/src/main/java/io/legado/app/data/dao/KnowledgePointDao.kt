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

    /** 查某人物的经典角色（subCategory 为空） */
    @Query("SELECT * FROM knowledge_points WHERE category = :category AND (subCategory IS NULL OR subCategory = '') ORDER BY sortOrder, createTime")
    fun getByCategoryNoSub(category: String): List<KnowledgePoint>

    /** 查某子分类 + 小说名下的所有条目 */
    @Query("SELECT * FROM knowledge_points WHERE subCategory = :subCategory AND novelName = :novelName ORDER BY sortOrder, createTime")
    fun getBySubAndNovel(subCategory: String, novelName: String): List<KnowledgePoint>

    /** 查某子分类下所有不重复的小说名 */
    @Query("SELECT DISTINCT novelName FROM knowledge_points WHERE subCategory = :subCategory AND novelName != '' ORDER BY novelName")
    fun getDistinctNovelNames(subCategory: String): List<String>

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
