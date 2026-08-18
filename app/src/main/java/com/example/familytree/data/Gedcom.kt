package com.example.familytree.data

import java.nio.charset.Charset
import java.util.UUID

/**
 * GEDCOM 5.5 家谱标准格式编解码：
 *  - 导出：成员 → INDI，夫妻及子女 → FAM；
 *  - 导入：解析 INDI/FAM，还原成员与 父亲/母亲/配偶 关系。
 */
object GedcomCodec {

    fun export(data: FamilyData): String {
        val sb = StringBuilder()
        sb.append("0 HEAD\n")
        sb.append("1 SOUR FAMILYTREE\n")
        sb.append("2 VERS 1.0\n")
        sb.append("1 GEDC\n")
        sb.append("2 VERS 5.5.1\n")
        sb.append("2 FORM LINEAGE-LINKED\n")
        sb.append("1 CHAR UTF-8\n")

        val idOf = mutableMapOf<String, String>()
        data.persons.forEachIndexed { i, p -> idOf[p.id] = "@I${i + 1}@" }

        fun esc(s: String): String = s.replace("\n", " ").replace('\r', ' ')
            .replace('/', '∕') // 避免与 GEDCOM 姓名“姓/名”分隔符冲突，导入时还原
            .trim()

        data.persons.forEachIndexed { i, p ->
            sb.append("0 ${idOf[p.id]} INDI\n")
            if (p.name.isNotBlank()) sb.append("1 NAME ${esc(p.name)}\n")
            when (p.gender) {
                Gender.MALE -> sb.append("1 SEX M\n")
                Gender.FEMALE -> sb.append("1 SEX F\n")
                Gender.UNKNOWN -> Unit
            }
            if (p.colorIndex != null) sb.append("1 _CLR ${p.colorIndex}\n")
            if (p.birth.isNotBlank()) {
                sb.append("1 BIRT\n")
                sb.append("2 DATE ${esc(p.birth)}\n")
            }
            if (p.death.isNotBlank()) {
                sb.append("1 DEAT\n")
                sb.append("2 DATE ${esc(p.death)}\n")
            }
            if (p.notes.isNotBlank()) {
                sb.append("1 NOTE ${esc(p.notes)}\n")
            }
            // 连襟/妯娌/自定义关系：GEDCOM 无标准标签，用 ASSO + RELA 记录。
            // 连襟/妯娌是对称称谓，任一侧记录一次；自定义称谓有方向（from 是 to 的 label），
            // 只从 from 一侧记录，保证导入后方向不翻转。
            data.relations.filter { r ->
                (r.type == RelationType.LIANJIN || r.type == RelationType.ZHOULI) &&
                    (r.fromId == p.id || r.toId == p.id) && r.fromId < r.toId ||
                    (r.type == RelationType.CUSTOM && r.fromId == p.id && r.label.isNotBlank())
            }.forEach { r ->
                val otherId = if (r.fromId == p.id) r.toId else r.fromId
                val rela = when (r.type) {
                    RelationType.LIANJIN -> "连襟"
                    RelationType.ZHOULI -> "妯娌"
                    else -> r.label
                }
                sb.append("1 ASSO ${idOf[otherId]}\n")
                sb.append("2 RELA $rela\n")
            }
        }

        // 家庭：以父母为中心分组子女；夫妻单独成 FAM
        val byId = data.persons.associateBy { it.id }
        val spousesOf = mutableMapOf<String, String>() // personId -> 首位配偶
        for (r in data.relations) {
            if (r.type == RelationType.SPOUSE && spousesOf[r.fromId] == null && spousesOf[r.toId] == null) {
                spousesOf[r.fromId] = r.toId
                spousesOf[r.toId] = r.fromId
            }
        }

        fun husbWife(a: Person, b: Person): Pair<Person?, Person?> = when {
            a.gender == Gender.FEMALE && b.gender != Gender.FEMALE -> b to a
            else -> a to b
        }

        val famOfParent = mutableMapOf<String, Int>()
        val famHusb = mutableListOf<String?>()
        val famWife = mutableListOf<String?>()
        val famChildren = mutableListOf<MutableList<String>>()

        fun famIndexFor(parentId: String): Int {
            famOfParent[parentId]?.let { return it }
            val idx = famHusb.size
            famHusb.add(null)
            famWife.add(null)
            famChildren.add(mutableListOf())
            famOfParent[parentId] = idx
            val p = byId[parentId]
            val spouseId = spousesOf[parentId]
            val s = spouseId?.let { byId[it] }
            if (p != null && s != null) {
                val (h, w) = husbWife(p, s)
                famHusb[idx] = h?.id
                famWife[idx] = w?.id
                famOfParent[s.id] = idx
            } else if (p != null) {
                if (p.gender == Gender.FEMALE) famWife[idx] = p.id else famHusb[idx] = p.id
            }
            return idx
        }

        // 亲子关系 → 归入父母所在 FAM
        for (r in data.relations) {
            when (r.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    val idx = famIndexFor(r.fromId)
                    if (r.toId !in famChildren[idx]) famChildren[idx].add(r.toId)
                }
                RelationType.SON, RelationType.DAUGHTER -> {
                    val idx = famIndexFor(r.toId)
                    if (r.fromId !in famChildren[idx]) famChildren[idx].add(r.fromId)
                }
                else -> Unit
            }
        }
        // 仅有婚姻关系的夫妻
        for (r in data.relations) {
            if (r.type == RelationType.SPOUSE) {
                famIndexFor(r.fromId)
            }
        }

        famHusb.indices.forEach { i ->
            sb.append("0 @F${i + 1}@ FAM\n")
            famHusb[i]?.let { sb.append("1 HUSB ${idOf[it]}\n") }
            famWife[i]?.let { sb.append("1 WIFE ${idOf[it]}\n") }
            famChildren[i].forEach { c -> sb.append("1 CHIL ${idOf[c]}\n") }
        }

        // 家族分组：GEDCOM 标准无此概念，用应用扩展标签 _FAMGRP 记录。
        // 第三方软件会忽略这些记录，本应用导入时可完整还原家族及其成员。
        data.families.forEachIndexed { i, f ->
            sb.append("0 @G${i + 1}@ _FAMGRP\n")
            sb.append("1 NAME ${esc(f.name)}\n")
            data.persons.filter { it.familyId == f.id || f.id in it.familyIds }.forEach { p ->
                sb.append("1 MEMB ${idOf[p.id]}\n")
            }
        }

        sb.append("0 TRLR\n")
        return sb.toString()
    }

    fun parse(text: String): FamilyData {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val persons = mutableListOf<Person>()
        val gidToId = mutableMapOf<String, String>()

        var curId: String? = null
        var name = ""
        var gender = Gender.UNKNOWN
        var birth = ""
        var death = ""
        var colorIndex: Int? = null
        var notes = StringBuilder()
        var lastLevel1 = ""
        var pendingAsso: String? = null
        val assos = mutableListOf<Triple<String, String, String>>() // (fromGid, toGid, label)

        data class FamRec(
            var husbandId: String? = null,
            var wifeId: String? = null,
            val children: MutableList<String> = mutableListOf(),
        )

        val fams = mutableListOf<FamRec>()
        var curFam: FamRec? = null

        // 家族分组扩展记录（_FAMGRP）
        data class GroupRec(var name: String = "", val members: MutableList<String> = mutableListOf())
        val groups = mutableListOf<GroupRec>()
        var curGroup: GroupRec? = null

        fun flushPerson() {
            val gid = curId ?: return
            val clean = name.trim().ifBlank { return }
            if (gid !in gidToId) {
                val newId = UUID.randomUUID().toString()
                gidToId[gid] = newId
                persons.add(
                    Person(
                        id = newId,
                        name = clean,
                        gender = gender,
                        birth = birth.trim(),
                        death = death.trim(),
                        notes = notes.toString().trim(),
                        colorIndex = colorIndex,
                    ),
                )
            }
            name = ""
            gender = Gender.UNKNOWN
            birth = ""
            death = ""
            colorIndex = null
            notes = StringBuilder()
        }

        for (raw in lines) {
            if (raw.isBlank()) continue
            val level = raw.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            val rest = raw.dropWhile { it.isDigit() }.trim()
            if (level == 0) {
                flushPerson()
                curId = null
                curFam = null
                curGroup = null
                lastLevel1 = ""
                pendingAsso = null
                if (rest.startsWith("@") && rest.endsWith(" INDI")) {
                    curId = rest.removeSuffix(" INDI").removePrefix("@").removeSuffix("@")
                } else if (rest.startsWith("@") && rest.endsWith(" FAM")) {
                    curFam = FamRec()
                    fams.add(curFam!!)
                } else if (rest.startsWith("@") && rest.endsWith(" _FAMGRP")) {
                    curGroup = GroupRec()
                    groups.add(curGroup!!)
                }
                continue
            }
            val tag = rest.substringBefore(' ')
            val value = rest.substringAfter(' ', "").trim()
            when (level) {
                1 -> {
                    lastLevel1 = tag
                    when (tag) {
                        "NAME" -> if (curGroup != null) {
                            curGroup!!.name = value.replace('∕', '/')
                        } else {
                            name = value.substringBefore('/').trim()
                                .ifBlank { value.replace("/", "").trim() }
                                .replace('∕', '/')
                        }
                        "_CLR" -> colorIndex = value.toIntOrNull()
                        "MEMB" -> curGroup?.members?.add(value.removePrefix("@").removeSuffix("@"))
                        "SEX" -> gender = when (value.uppercase().firstOrNull()) {
                            'M' -> Gender.MALE
                            'F' -> Gender.FEMALE
                            else -> Gender.UNKNOWN
                        }
                        "NOTE", "CONT" -> notes.append(value)
                        "HUSB" -> curFam?.husbandId = value.removePrefix("@").removeSuffix("@")
                        "WIFE" -> curFam?.wifeId = value.removePrefix("@").removeSuffix("@")
                        "CHIL" -> curFam?.children?.add(value.removePrefix("@").removeSuffix("@"))
                        "ASSO" -> pendingAsso = value.removePrefix("@").removeSuffix("@")
                        else -> Unit
                    }
                }
                2 -> when (lastLevel1) {
                    "BIRT" -> if (tag == "DATE") birth = value
                    "DEAT" -> if (tag == "DATE") death = value
                    "NOTE" -> if (tag == "CONT" || tag == "CONC") notes.append(value)
                    "ASSO" -> if (tag == "RELA" && pendingAsso != null && curId != null) {
                        assos.add(Triple(curId!!, pendingAsso!!, value))
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        flushPerson()

        val personById = persons.associateBy { it.id }
        val relations = mutableListOf<Relation>()
        fun addRel(type: RelationType, from: String, to: String, label: String = "") {
            if (from == to || from.isBlank() || to.isBlank()) return
            relations.add(Relation(UUID.randomUUID().toString(), type, from, to, label))
        }

        for (f in fams) {
            val hId = f.husbandId?.let { gidToId[it] }
            val wId = f.wifeId?.let { gidToId[it] }
            if (hId != null && wId != null) {
                addRel(RelationType.SPOUSE, hId, wId)
            }
            for (c in f.children) {
                val cId = gidToId[c] ?: continue
                hId?.let { pid ->
                    val t = when (personById[pid]?.gender) {
                        Gender.FEMALE -> RelationType.MOTHER
                        Gender.MALE -> RelationType.FATHER
                        else -> RelationType.PARENT
                    }
                    addRel(t, pid, cId)
                }
                wId?.let { pid ->
                    val t = when (personById[pid]?.gender) {
                        Gender.MALE -> RelationType.FATHER
                        Gender.FEMALE -> RelationType.MOTHER
                        else -> RelationType.PARENT
                    }
                    addRel(t, pid, cId)
                }
            }
        }

        // 连襟/妯娌（ASSO + RELA）
        for ((fg, tg, label) in assos) {
            val fId = gidToId[fg] ?: continue
            val tId = gidToId[tg] ?: continue
            when (label) {
                "连襟", "連襟" -> addRel(RelationType.LIANJIN, fId, tId)
                "妯娌" -> addRel(RelationType.ZHOULI, fId, tId)
                else -> addRel(RelationType.CUSTOM, fId, tId, label)
            }
        }

        // 去重（父母/子女同一对、配偶同一对、连襟/妯娌同一对）
        val parentChildTypes = setOf(
            RelationType.FATHER, RelationType.MOTHER,
            RelationType.SON, RelationType.DAUGHTER, RelationType.PARENT,
        )
        val dedup = mutableListOf<Relation>()
        for (r in relations) {
            val dup = dedup.any { x ->
                when {
                    r.type in parentChildTypes && x.type in parentChildTypes ->
                        setOf(x.fromId, x.toId) == setOf(r.fromId, r.toId)
                    r.type == RelationType.SPOUSE && x.type == RelationType.SPOUSE ->
                        setOf(x.fromId, x.toId) == setOf(r.fromId, r.toId)
                    (r.type == RelationType.LIANJIN || r.type == RelationType.ZHOULI) &&
                        (x.type == RelationType.LIANJIN || x.type == RelationType.ZHOULI) ->
                        setOf(x.fromId, x.toId) == setOf(r.fromId, r.toId)
                    else -> false
                }
            }
            if (!dup) dedup.add(r)
        }

        // 家族分组还原：_FAMGRP 记录 → Family + 成员归属
        val families = mutableListOf<Family>()
        val famOfPerson = mutableMapOf<String, MutableList<String>>()
        for (g in groups) {
            val gName = g.name.trim()
            if (gName.isEmpty()) continue
            val fid = UUID.randomUUID().toString()
            families.add(Family(fid, gName))
            for (mg in g.members) {
                val pid = gidToId[mg] ?: continue
                famOfPerson.getOrPut(pid) { mutableListOf() }.add(fid)
            }
        }
        if (famOfPerson.isNotEmpty()) {
            persons.replaceAll { p ->
                val extra = famOfPerson[p.id].orEmpty()
                if (extra.isEmpty()) p
                else p.copy(
                    familyIds = (p.familyIds + extra).distinct(),
                    familyId = if (p.familyId.isBlank()) extra.first() else p.familyId,
                )
            }
        }

        return FamilyData(persons, dedup, families)
    }

    /** 按 UTF-8 优先、GB18030 兜底解码 GEDCOM/JSON 文件内容（自动去除 UTF-8 BOM） */
    fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        val utf8 = b.toString(Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return try {
            b.toString(Charset.forName("GB18030"))
        } catch (e: Exception) {
            utf8
        }
    }

    fun looksLikeGedcom(text: String): Boolean =
        text.trimStart().startsWith("0 HEAD") || text.contains(" INDI", ignoreCase = true)
}
