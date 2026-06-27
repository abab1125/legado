package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局知识点——人物档案、地名设定、事件脉络、创作笔记等
 */
@Entity(
    tableName = "knowledge_points"
)
data class KnowledgePoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // 知识点标题
    val title: String = "",
    // 知识点内容
    val content: String = "",
    // 标签，逗号分隔
    val tags: String = "",
    // 分类: character/place/event/note/other
    @ColumnInfo(defaultValue = "note")
    val category: String = "note",
    // 排序序号
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    // 创建时间
    @ColumnInfo(defaultValue = "0")
    val createTime: Long = System.currentTimeMillis(),
    // 更新时间
    @ColumnInfo(defaultValue = "0")
    val updateTime: Long = System.currentTimeMillis()
)
