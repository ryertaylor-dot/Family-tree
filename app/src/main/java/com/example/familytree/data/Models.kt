package com.example.familytree.data

import kotlinx.serialization.Serializable

/** 性别 */
@Serializable
enum class Gender { MALE, FEMALE, UNKNOWN }

/**
 * 关系类型（单重关系）：
 * FATHER  from 是 to 的 父亲；MOTHER 母亲；SON 儿子；DAUGHTER 女儿（父母子女均为单向显式类型）
 * SPOUSE 配偶；SIBLING 兄弟姐妹；LIANJIN 连襟（姐妹的丈夫们）；ZHOULI 妯娌（兄弟的妻子们）；CUSTOM 自定义
 * PARENT 仅用于旧版数据兼容（表示父/母，性别未知）
 */
@Serializable
enum class RelationType {
    FATHER, MOTHER, SON, DAUGHTER, SPOUSE, SIBLING, LIANJIN, ZHOULI, CUSTOM, PARENT,
}

/** 家族分组 */
@Serializable
data class Family(
    val id: String,
    val name: String,
)

/** 未分组筛选的特殊值 */
const val FAMILY_NONE = "__NONE__"

/**
 * 家庭成员节点。
 *
 * @param familyId 旧版单家族字段（兼容迁移用，新数据以 familyIds 为准）
 * @param familyIds 所属家族（可为多个，空 = 未分组）
 * @param colorIndex 节点颜色索引（null 表示按性别自动取色）
 */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val gender: Gender = Gender.UNKNOWN,
    val birth: String = "",
    val death: String = "",
    val notes: String = "",
    val colorIndex: Int? = null,
    val familyId: String = "",
    val familyIds: List<String> = emptyList(),
    /** 照片文件相对名（应用私有目录，最多 3 张） */
    val photos: List<String> = emptyList(),
)

/**
 * 亲属关系边。
 *
 * @param type PARENT 时 fromId 是 toId 的父母（反向即子女）
 * @param label CUSTOM 类型时使用，如“舅舅”
 */
@Serializable
data class Relation(
    val id: String,
    val type: RelationType,
    val fromId: String,
    val toId: String,
    val label: String = "",
)

/** 全部数据（一个 JSON 文件即可整体导出/导入） */
@Serializable
data class FamilyData(
    val persons: List<Person> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val families: List<Family> = emptyList(),
)

/**
 * JSON 导出容器：数据 + 照片文件内容（自动 Base64 内嵌），
 * 保证换设备导入时照片不会丢失；旧版纯 FamilyData 的 JSON 仍可正常导入。
 */
@Serializable
data class FamilyExport(
    val data: FamilyData,
    val photos: Map<String, ByteArray> = emptyMap(),
)

/** 按家族筛选后的数据子集（null=全部；FAMILY_NONE=未分组；其他=家族 id，成员可属于多个家族） */
fun FamilyData.filteredBy(filter: String?): FamilyData {
    if (filter == null) return this
    val ps = if (filter == FAMILY_NONE) {
        persons.filter { it.familyIds.isEmpty() }
    } else {
        persons.filter { it.familyId == filter || filter in it.familyIds }
    }
    val ids = ps.map { it.id }.toSet()
    val rs = relations.filter { it.fromId in ids && it.toId in ids }
    return FamilyData(ps, rs, families)
}

/** 成员所属家族名称列表（按家族定义顺序） */
fun FamilyData.familyNamesOf(p: Person): List<String> =
    families.filter { it.id == p.familyId || it.id in p.familyIds }.map { it.name }

/** 按家族筛选关系：任一端属于所选家族即保留（用于关系页，跨家族的推断关系同样可见） */
fun FamilyData.relationsFilteredBy(filter: String?): List<Relation> {
    if (filter == null) return relations
    val ids = if (filter == FAMILY_NONE) {
        persons.filter { it.familyIds.isEmpty() }.map { it.id }.toSet()
    } else {
        persons.filter { it.familyId == filter || filter in it.familyIds }.map { it.id }.toSet()
    }
    return relations.filter { it.fromId in ids || it.toId in ids }
}

fun Gender.parentName(i18n: I18n): String = when (this) {
    Gender.MALE -> i18n.s("l_father")
    Gender.FEMALE -> i18n.s("l_mother")
    Gender.UNKNOWN -> i18n.s("l_parent")
}

fun Gender.childName(i18n: I18n): String = when (this) {
    Gender.MALE -> i18n.s("l_son")
    Gender.FEMALE -> i18n.s("l_daughter")
    Gender.UNKNOWN -> i18n.s("l_child")
}

/** 以某个成员的视角描述一条关系，如「父亲：张伟」「女儿：张小红」。
 *  自定义关系的存储方向为“fromId 是 toId 的 label”（如 刘华是姜自安的儿媳），
 *  因此当 viewer 是 fromId 一方时，通过实时推导取得反向称谓（公公）再展示。 */
fun Relation.describe(
    viewerId: String,
    personById: Map<String, Person>,
    i18n: I18n,
    data: FamilyData? = null,
): String {
    val otherId = if (fromId == viewerId) toId else if (toId == viewerId) fromId else toId
    val otherName = personById[otherId]?.name ?: "未知"
    val otherGender = personById[otherId]?.gender
    val viewerIsFrom = fromId == viewerId
    return when (type) {
        RelationType.SPOUSE -> "${i18n.s("l_spouse")}：$otherName"
        RelationType.FATHER ->
            if (viewerIsFrom) "${otherGender?.childName(i18n) ?: i18n.s("l_child")}：$otherName" else "${i18n.s("l_father")}：$otherName"
        RelationType.MOTHER ->
            if (viewerIsFrom) "${otherGender?.childName(i18n) ?: i18n.s("l_child")}：$otherName" else "${i18n.s("l_mother")}：$otherName"
        RelationType.SON ->
            if (viewerIsFrom) "${otherGender?.parentName(i18n) ?: i18n.s("l_parent")}：$otherName" else "${i18n.s("l_son")}：$otherName"
        RelationType.DAUGHTER ->
            if (viewerIsFrom) "${otherGender?.parentName(i18n) ?: i18n.s("l_parent")}：$otherName" else "${i18n.s("l_daughter")}：$otherName"
        RelationType.SIBLING -> "${i18n.s("l_sibling")}：$otherName"
        RelationType.LIANJIN -> "${i18n.s("l_lianjin")}：$otherName"
        RelationType.ZHOULI -> "${i18n.s("l_zhouli")}：$otherName"
        RelationType.CUSTOM -> {
            val stored = label.ifBlank { i18n.s("l_kin") }
            val shown = if (viewerIsFrom && data != null) {
                val viewer = personById[viewerId]
                if (viewer != null) {
                    KinshipInference.derive(viewer, data, i18n)
                        .firstOrNull { it.otherId == toId }?.label ?: stored
                } else stored
            } else stored
            "$shown：$otherName"
        }
        RelationType.PARENT ->
            if (viewerIsFrom) "${otherGender?.childName(i18n) ?: i18n.s("l_child")}：$otherName"
            else "${personById[fromId]?.gender?.parentName(i18n) ?: i18n.s("l_parent")}：$otherName"
    }
}

/** 出生/去世年份摘要，如 "1962 - 2020" */
fun personYears(p: Person, i18n: I18n): String {
    val b = p.birth.trim()
    val d = p.death.trim()
    return when {
        b.isNotEmpty() && d.isNotEmpty() -> "$b - $d"
        b.isNotEmpty() -> b
        d.isNotEmpty() -> i18n.s("l_deceased", d)
        else -> ""
    }
}

fun relationCount(p: Person, data: FamilyData): Int =
    data.relations.count { it.fromId == p.id || it.toId == p.id }

/** 关系标签：按 fromId 一方向 toId 一方读，如「父亲」「儿子」，用于关系列表徽标 */
fun Relation.typeLabelA(byId: Map<String, Person>, i18n: I18n): String = when (type) {
    RelationType.SPOUSE -> i18n.s("l_spouse")
    RelationType.FATHER -> i18n.s("l_father")
    RelationType.MOTHER -> i18n.s("l_mother")
    RelationType.SON -> i18n.s("l_son")
    RelationType.DAUGHTER -> i18n.s("l_daughter")
    RelationType.SIBLING -> i18n.s("l_sibling")
    RelationType.LIANJIN -> i18n.s("l_lianjin")
    RelationType.ZHOULI -> i18n.s("l_zhouli")
    RelationType.CUSTOM -> label.ifBlank { i18n.s("l_kin") }
    RelationType.PARENT -> i18n.s("l_parent")
}
