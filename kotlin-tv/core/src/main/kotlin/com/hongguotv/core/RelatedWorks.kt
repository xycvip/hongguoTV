// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

data class RelatedWork(val series: Series, val season: String, val upcoming: Boolean) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

/**
 * Reading plan's related-work cell, verified with public season metadata. Only short-drama types
 * are actionable; novels/audio are intentionally excluded.
 */
object RelatedWorks {
    fun parse(raw: String, currentId: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) throw IOException("相关作品接口暂不可用")
        val cells = root.optJSONArray("data") ?: throw IOException("相关作品数据格式已变化")
        return cells
            .objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                if (
                    id == currentId ||
                        !id.matches(Regex("[0-9]{1,30}")) ||
                        title.isBlank() ||
                        type !in listOf(1004, 1007)
                )
                    return@mapNotNull null
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0))
                    return@mapNotNull null
                val season =
                    row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                        row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty()
                    }
                RelatedWork(
                    Series(
                        id,
                        title,
                        cleanUrl(row.str("cover")),
                        row.str("video_desc"),
                        if (upcoming) "待上线" else "全 ${row.optInt("episode_cnt")} 集",
                    ),
                    season,
                    upcoming,
                )
            }
            .distinctBy { it.series.id }
            .take(30)
    }
}
