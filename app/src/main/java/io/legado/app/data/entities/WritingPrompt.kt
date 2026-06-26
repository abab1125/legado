package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 写作提示词——角色设定、世界观、写作风格指引、大纲等
 */
@Entity(
    tableName = "writing_prompts",
    indices = [Index(value = ["bookUrl"])]
)
data class WritingPrompt(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // 所属书籍
    val bookUrl: String = "",
    // 提示词标题
    val title: String = "",
    // 提示词内容
    val content: String = "",
    // 类型: character/world/style/outline/other
    @ColumnInfo(defaultValue = "other")
    val type: String = "other",
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
