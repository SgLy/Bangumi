package soko.ekibun.bangumi.api.bangumi.bean

import android.content.Context
import androidx.annotation.IntDef
import androidx.annotation.StringDef
import androidx.annotation.StringRes
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import retrofit2.Call
import soko.ekibun.bangumi.R
import soko.ekibun.bangumi.api.ApiHelper
import soko.ekibun.bangumi.api.bangumi.Bangumi
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * 剧集类
 * @property id 剧集id
 * @property type 剧集类型
 * @property sort 编号
 * @property name 标题
 * @property name_cn 中文标题
 * @property duration 时长
 * @property airdate 放送时间
 * @property comment 吐槽数
 * @property desc 简介
 * @property status 放送状态
 * @property progress 观看进度
 * @property category 音乐的专辑编号
 * @constructor
 */
data class Episode(
        val id: Int = 0,
        @EpisodeType var type: Int = 0,
        var sort: Float = 0f,
        var name: String? = null,
        var name_cn: String? = null,
        var duration: String? = null,
        var airdate: String? = null,
        var comment: Int = 0,
        var desc: String? = null,
        @EpisodeStatus var status: String? = null,
        @ProgressType var progress: String? = null,
        var category: String? = null
) {
    val url = "${Bangumi.SERVER}/ep/$id"

    fun merge(ep: Episode) {
        sort = if (sort == 0f) ep.sort else sort
        name = name ?: ep.name
        name_cn = name_cn ?: ep.name_cn
        duration = duration ?: ep.duration
        airdate = ep.airdate ?: ep.airdate
        comment = if (comment == 0) ep.comment else comment
        desc = desc ?: ep.desc
        status = status ?: ep.status
        progress = progress ?: ep.progress
        category = category ?: ep.category
    }


    fun parseSort(context: Context): String{
        return if(type == TYPE_MAIN)
            context.getString(R.string.parse_sort_ep, DecimalFormat("#.##").format(sort))
        else
            context.getString(getTypeRes(type)) + " ${DecimalFormat("#.##").format(sort)}"
    }

    @IntDef(TYPE_MAIN, TYPE_SP, TYPE_OP, TYPE_ED, TYPE_PV, TYPE_MAD, TYPE_OTHER, TYPE_MUSIC)
    annotation class EpisodeType

    @StringDef(STATUS_TODAY, STATUS_AIR, STATUS_NA)
    annotation class EpisodeStatus

    @StringDef(PROGRESS_WATCH, PROGRESS_QUEUE, PROGRESS_DROP, PROGRESS_REMOVE)
    annotation class ProgressType

    companion object {
        const val TYPE_MAIN = 0
        const val TYPE_SP = 1
        const val TYPE_OP = 2
        const val TYPE_ED = 3
        const val TYPE_PV = 4
        const val TYPE_MAD = 5
        const val TYPE_OTHER = 6
        const val TYPE_MUSIC = 7

        /**
         * 剧集类型字符串资源
         */
        @StringRes
        fun getTypeRes(@EpisodeType type: Int): Int {
            return when(type){
                TYPE_MAIN -> R.string.episode_type_main
                TYPE_SP -> R.string.episode_type_sp
                TYPE_OP -> R.string.episode_type_op
                TYPE_ED -> R.string.episode_type_ed
                TYPE_PV -> R.string.episode_type_pv
                TYPE_MAD -> R.string.episode_type_mad
                TYPE_MUSIC -> R.string.episode_type_music
                else -> R.string.episode_type_main
            }
        }

        const val STATUS_TODAY = "Today"
        const val STATUS_AIR = "Air"
        const val STATUS_NA = "NA"

        const val PROGRESS_WATCH = "watched"
        const val PROGRESS_QUEUE = "queue"
        const val PROGRESS_DROP = "drop"
        const val PROGRESS_REMOVE = "remove"

        /**
         * 剧集和曲目列表
         */
        fun parseLineList(doc: Element): List<Episode> {
            var cat = "本篇"
            return doc.select("ul.line_list>li").mapNotNull { li ->
                if (li.hasClass("cat")) cat = li.text()
                val h6a = li.selectFirst("h6>a") ?: return@mapNotNull null
                val values = Regex("^\\D*(\\d+\\.?\\d?)\\.(.*)").find(h6a.text() ?: "")?.groupValues
                        ?: " ${h6a.text()}".split(" ", limit = 3)
                val epInfo = li.select("small.grey")?.text()?.split("/")
                Episode(
                        id = Regex("""/ep/([0-9]*)""").find(h6a.attr("href") ?: "")?.groupValues?.get(1)?.toIntOrNull()
                                ?: return@mapNotNull null,
                        type = if (cat.startsWith("Disc")) TYPE_MUSIC else when (cat) {
                            "本篇" -> TYPE_MAIN
                            "特别篇" -> TYPE_SP
                            "OP" -> TYPE_OP
                            "ED" -> TYPE_ED
                            "PV" -> TYPE_PV
                            "MAD" -> TYPE_MAD
                            else -> TYPE_OTHER
                        },
                        sort = values.getOrNull(1)?.toFloatOrNull() ?: 0f,
                        name = values.getOrNull(2) ?: h6a.text(),
                        name_cn = li.selectFirst("h6>span.tip")?.text()?.substringAfter(" "),
                        duration = epInfo?.firstOrNull { it.trim().startsWith("时长") }?.substringAfter(":"),
                        airdate = epInfo?.firstOrNull { it.trim().startsWith("首播") }?.substringAfter(":"),
                        comment = epInfo?.firstOrNull { it.trim().startsWith("讨论") }?.trim()?.substringAfter("+")?.toIntOrNull()
                                ?: 0,
                        status = if (cat.startsWith("Disc")) STATUS_AIR else li.selectFirst(".epAirStatus span")?.className(),
                        progress = li.selectFirst(".listEpPrgManager>span")?.let {
                            when {
                                it.hasClass("statusWatched") -> PROGRESS_WATCH
                                it.hasClass("statusQueue") -> PROGRESS_QUEUE
                                it.hasClass("statusDrop") -> PROGRESS_DROP
                                else -> null
                            }
                        },
                        category = if (cat.startsWith("Disc")) cat else null)
            }

        }

        /**
         * 主页和概览的剧集信息
         */
        fun parseProgressList(item: Element, doc: Element? = null): List<Episode> {
            if (item.selectFirst("ul.line_list_music") != null) return parseLineList(item)
            var cat = "本篇"
            val now = Date().time
            return item.select("ul.prg_list li").mapNotNull { li ->
                if (li.hasClass("subtitle")) cat = li.text()
                val it = li.selectFirst("a") ?: return@mapNotNull null
                val rel = doc?.selectFirst(it.attr("rel"))
                val epInfo = rel?.selectFirst(".tip")?.textNodes()?.map { it.text() }
                val airdate = epInfo?.firstOrNull { it.startsWith("首播") }?.substringAfter(":")
                Episode(
                        id = it.id().substringAfter("_").toIntOrNull() ?: return@mapNotNull null,
                        type = when (cat) {
                            "本篇" -> TYPE_MAIN
                            "SP" -> TYPE_SP
                            "OP" -> TYPE_OP
                            "ED" -> TYPE_ED
                            "PV" -> TYPE_PV
                            "MAD" -> TYPE_MAD
                            else -> TYPE_OTHER
                        },
                        sort = Regex("""\d*(\.\d*)?""").find(it.text())?.groupValues?.getOrNull(0)?.toFloatOrNull()
                                ?: 0f,
                        name = it.attr("title")?.substringAfter(" "),
                        name_cn = epInfo?.firstOrNull { it.startsWith("中文标题") }?.substringAfter(":"),
                        duration = epInfo?.firstOrNull { it.startsWith("时长") }?.substringAfter(":"),
                        airdate = airdate,
                        comment = rel?.selectFirst(".cmt .na")?.text()?.trim('(', ')', '+')?.toIntOrNull() ?: 0,
                        status = when {
                            it.hasClass("epBtnToday") -> STATUS_TODAY
                            it.hasClass("epBtnAir") || it.hasClass("epBtnWatched") || (rel != null && (try {
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(airdate ?: "")
                            } catch (e: Exception) {
                                null
                            }?.time ?: 0L < now)) -> STATUS_AIR
                            else -> STATUS_NA
                        },
                        progress = when {
                            it.hasClass("epBtnWatched") -> PROGRESS_WATCH
                            it.hasClass("epBtnQueue") -> PROGRESS_QUEUE
                            it.hasClass("epBtnDrop") -> PROGRESS_DROP
                            else -> null
                        })
            }
        }

        /**
         * 获取剧集列表
         */
        fun getSubjectEps(
                subject: Subject
        ): Call<List<Episode>> {
            return ApiHelper.buildHttpCall("${Bangumi.SERVER}/subject/${subject.id}/ep") {
                parseLineList(Jsoup.parse(it.body?.string() ?: ""))
            }
        }
    }
}