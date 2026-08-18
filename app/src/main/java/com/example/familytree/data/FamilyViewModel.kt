package com.example.familytree.data

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID

sealed interface ImportResult {
    data object Success : ImportResult
    data class Error(val message: String) : ImportResult
}

class FamilyViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FamilyRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val parentChildTypes = setOf(
        RelationType.FATHER, RelationType.MOTHER,
        RelationType.SON, RelationType.DAUGHTER, RelationType.PARENT,
    )

    /** 同代关系：成员必须处于同一代，不能出现在祖先后代之间 */
    private val sameGenTypes = setOf(
        RelationType.SPOUSE, RelationType.SIBLING,
        RelationType.LIANJIN, RelationType.ZHOULI,
    )

    /** Compose 状态：任何修改都会触发界面重组 */
    var data by mutableStateOf(repo.load())
        private set

    init {
        // 规则版本：升级后首次启动时，按最新规则全局更新一次所有关系
        val applied = prefs.getInt("rules_version", 0)
        if (applied < RULES_VERSION && data.persons.isNotEmpty()) {
            applyAllRules(null)
            persist()
            prefs.edit().putInt("rules_version", RULES_VERSION).apply()
        }
    }

    /** 拓扑图背景样式索引（持久化到 SharedPreferences） */
    var bgStyle by mutableStateOf(prefs.getInt("bg_style", 0))
        private set

    fun changeBgStyle(style: Int) {
        bgStyle = style
        prefs.edit().putInt("bg_style", style).apply()
    }

    /** 各页面当前家族筛选（null=全部，FAMILY_NONE=未分组，其他=家族 id） */
    var familyFilter by mutableStateOf<String?>(null)
    var treeFamilyFilter by mutableStateOf<String?>(null)
    var relFamilyFilter by mutableStateOf<String?>(null)

    /** 新建成员默认归属的家族（当前筛选为具体家族时自动归入） */
    fun defaultFamiliesForNew(): List<String> =
        familyFilter?.takeIf { it != FAMILY_NONE }?.let { listOf(it) } ?: emptyList()

    fun addFamily(name: String): Family {
        val f = Family(UUID.randomUUID().toString(), name.trim())
        data = data.copy(families = data.families + f)
        persist()
        return f
    }

    fun addPerson(
        name: String,
        gender: Gender,
        birth: String,
        death: String,
        notes: String,
        colorIndex: Int?,
        familyIds: List<String> = emptyList(),
    ): Person {
        val person = Person(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            gender = gender,
            birth = birth.trim(),
            death = death.trim(),
            notes = notes.trim(),
            colorIndex = colorIndex,
            familyId = familyIds.firstOrNull() ?: "",
            familyIds = familyIds,
        )
        data = data.copy(persons = data.persons + person)
        persist()
        return person
    }

    fun updatePerson(person: Person) {
        val old = data.persons.firstOrNull { it.id == person.id }
        data = data.copy(persons = data.persons.map { if (it.id == person.id) person else it })
        // 性别变化时同步修正关系类型（父亲↔母亲、儿子↔女儿），避免称谓与性别矛盾
        if (old != null && old.gender != person.gender) {
            data = data.copy(relations = data.relations.map { r ->
                when {
                    r.fromId != person.id -> r
                    r.type == RelationType.FATHER && person.gender == Gender.FEMALE ->
                        r.copy(type = RelationType.MOTHER)
                    r.type == RelationType.MOTHER && person.gender == Gender.MALE ->
                        r.copy(type = RelationType.FATHER)
                    r.type == RelationType.SON && person.gender == Gender.FEMALE ->
                        r.copy(type = RelationType.DAUGHTER)
                    r.type == RelationType.DAUGHTER && person.gender == Gender.MALE ->
                        r.copy(type = RelationType.SON)
                    else -> r
                }
            })
        }
        persist()
    }

    fun deletePerson(id: String) {
        data.persons.firstOrNull { it.id == id }?.photos?.forEach { repo.deletePhotoFile(it) }
        data = data.copy(
            persons = data.persons.filterNot { it.id == id },
            relations = data.relations.filterNot { it.fromId == id || it.toId == id },
        )
        persist()
    }

    /** 批量删除成员（连同其关系与照片文件） */
    fun deletePersons(ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val removed = data.persons.filter { it.id in idSet }
        removed.forEach { p -> p.photos.forEach { repo.deletePhotoFile(it) } }
        data = data.copy(
            persons = data.persons.filterNot { it.id in idSet },
            relations = data.relations.filterNot { it.fromId in idSet || it.toId in idSet },
        )
        persist()
    }

    /** 添加关系，返回是否成功（重复关系会被拒绝）。
     *  FATHER/MOTHER 为 parent→child 方向；SON/DAUGHTER 为 child→parent 方向。 */
    fun addRelation(type: RelationType, fromId: String, toId: String, label: String = ""): Boolean {
        if (fromId == toId) return false
        val parentChildTypes = setOf(
            RelationType.FATHER, RelationType.MOTHER,
            RelationType.SON, RelationType.DAUGHTER, RelationType.PARENT,
        )
        val dup = data.relations.any { r ->
            when {
                // 父母/子女类：同一对成员只能有一条（无论方向/细分类型）
                type in parentChildTypes && r.type in parentChildTypes ->
                    setOf(r.fromId, r.toId) == setOf(fromId, toId)
                // 配偶/兄弟姐妹/连襟/妯娌：对称去重
                type == RelationType.SPOUSE || type == RelationType.SIBLING ||
                    type == RelationType.LIANJIN || type == RelationType.ZHOULI ->
                    r.type == type &&
                        ((r.fromId == fromId && r.toId == toId) || (r.fromId == toId && r.toId == fromId))
                // 自定义：同一对成员 + 相同称谓唯一
                type == RelationType.CUSTOM ->
                    r.type == RelationType.CUSTOM &&
                        setOf(r.fromId, r.toId) == setOf(fromId, toId) && r.label == label
                else -> false
            }
        }
        if (dup || parentChildCycle(type, fromId, toId)) return false
        if (type in sameGenTypes && sameGenConflict(fromId, toId)) return false
        val newRel = Relation(UUID.randomUUID().toString(), type, fromId, toId, label.trim())
        data = data.copy(relations = data.relations + newRel)
        autoDeriveFixpoint(newRel, null)
        persist()
        return true
    }

    /** 修改已有关系（返回是否成功，重复会被拒绝）；修改后同样自动补全关联亲属 */
    fun updateRelation(id: String, type: RelationType, fromId: String, toId: String, label: String = ""): Boolean {
        if (fromId == toId) return false
        val parentChildTypes = setOf(
            RelationType.FATHER, RelationType.MOTHER,
            RelationType.SON, RelationType.DAUGHTER, RelationType.PARENT,
        )
        val dup = data.relations.any { x ->
            x.id != id && when {
                type in parentChildTypes && x.type in parentChildTypes ->
                    setOf(x.fromId, x.toId) == setOf(fromId, toId)
                type == RelationType.SPOUSE || type == RelationType.SIBLING ||
                    type == RelationType.LIANJIN || type == RelationType.ZHOULI ->
                    x.type == type &&
                        ((x.fromId == fromId && x.toId == toId) || (x.fromId == toId && x.toId == fromId))
                type == RelationType.CUSTOM ->
                    x.type == RelationType.CUSTOM &&
                        setOf(x.fromId, x.toId) == setOf(fromId, toId) && x.label == label
                else -> false
            }
        }
        if (dup || parentChildCycle(type, fromId, toId)) return false
        if (type in sameGenTypes && sameGenConflict(fromId, toId)) return false
        val old = data.relations.firstOrNull { it.id == id } ?: return false
        val updated = old.copy(type = type, fromId = fromId, toId = toId, label = label.trim())
        data = data.copy(relations = data.relations.map { if (it.id == id) updated else it })
        autoDeriveFixpoint(updated, null)
        persist()
        return true
    }

    /** 父母/子女类关系成环检测：child 不能是 parent 的祖先（避免“爷爷和孙子同人”等矛盾） */
    private fun parentChildCycle(type: RelationType, fromId: String, toId: String): Boolean {
        if (type !in parentChildTypes) return false
        val parentId = if (type == RelationType.SON || type == RelationType.DAUGHTER) toId else fromId
        val childId = if (type == RelationType.SON || type == RelationType.DAUGHTER) fromId else toId
        val parentsOf = mutableMapOf<String, MutableList<String>>()
        for (r in data.relations) {
            when (r.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT ->
                    parentsOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                RelationType.SON, RelationType.DAUGHTER ->
                    parentsOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                else -> Unit
            }
        }
        val stack = ArrayDeque<String>()
        stack.add(parentId)
        val seen = mutableSetOf<String>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            if (cur == childId) return true
            parentsOf[cur].orEmpty().forEach { stack.add(it) }
        }
        return false
    }

    /** 同代关系冲突检测：a 与 b 存在祖先后代关系时不能设为配偶/兄弟姐妹/连襟/妯娌 */
    private fun sameGenConflict(a: String, b: String): Boolean {
        fun isAncestorOf(anc: String, desc: String): Boolean {
            if (anc == desc) return true
            val parentsOf = mutableMapOf<String, MutableList<String>>()
            for (r in data.relations) {
                when (r.type) {
                    RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT ->
                        parentsOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                    RelationType.SON, RelationType.DAUGHTER ->
                        parentsOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                    else -> Unit
                }
            }
            val stack = ArrayDeque<String>()
            stack.add(desc)
            val seen = mutableSetOf<String>()
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!seen.add(cur)) continue
                if (cur == anc) return true
                parentsOf[cur].orEmpty().forEach { stack.add(it) }
            }
            return false
        }
        return isAncestorOf(a, b) || isAncestorOf(b, a)
    }

    /** 以种子关系为起点反复执行自动推断，直到不再新增（级联：配偶父母、兄弟姐妹继承父母等） */
    private fun autoDeriveFixpoint(seed: Relation, scope: Set<String>?) {
        var guard = 0
        val queue = ArrayDeque<Relation>()
        queue.add(seed)
        while (queue.isNotEmpty() && guard < 8) {
            guard++
            val r = queue.removeFirst()
            val before = data.relations.size
            autoDerive(r, scope)
            if (data.relations.size > before) {
                data.relations.subList(before, data.relations.size).forEach { queue.add(it) }
            }
        }
    }

    /**
     * 添加/修改一条关系后，自动推断并保存关联亲属：
     *  - 父母关系的配偶 → 自动成为子女的另一父母；
     *  - 兄弟姐妹 → 按共享父母自动互连；
     *  - 配偶关系 → 互为对方子女的父/母，双方子女互认兄弟姐妹。
     * 自动保存的关系与普通关系一样，可手动修改或删除。
     */
    /**
     * 自动补全的公共实现。
     * @param scope 范围（成员 id 集合）：仅在该范围内推断与新增关系；null 表示全部成员。
     */
    private fun autoDerive(r: Relation, scope: Set<String>? = null) {
        fun inScope(a: String, b: String): Boolean = scope == null || (a in scope && b in scope)
        val byId = data.persons.associateBy { it.id }
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        val parentsOf = mutableMapOf<String, MutableList<String>>()
        val spousesOf = mutableMapOf<String, MutableList<String>>()
        val siblingsOf = mutableMapOf<String, MutableList<String>>()
        for (x in data.relations) {
            if (!inScope(x.fromId, x.toId)) continue
            when (x.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    childrenOf.getOrPut(x.fromId) { mutableListOf() }.add(x.toId)
                    parentsOf.getOrPut(x.toId) { mutableListOf() }.add(x.fromId)
                }
                RelationType.SON, RelationType.DAUGHTER -> {
                    childrenOf.getOrPut(x.toId) { mutableListOf() }.add(x.fromId)
                    parentsOf.getOrPut(x.fromId) { mutableListOf() }.add(x.toId)
                }
                RelationType.SPOUSE -> {
                    spousesOf.getOrPut(x.fromId) { mutableListOf() }.add(x.toId)
                    spousesOf.getOrPut(x.toId) { mutableListOf() }.add(x.fromId)
                }
                RelationType.SIBLING -> {
                    siblingsOf.getOrPut(x.fromId) { mutableListOf() }.add(x.toId)
                    siblingsOf.getOrPut(x.toId) { mutableListOf() }.add(x.fromId)
                }
                else -> Unit
            }
        }

        val extra = mutableListOf<Relation>()

        fun hasParentChild(from: String, to: String): Boolean = data.relations.any { x ->
            inScope(x.fromId, x.toId) && x.type in parentChildTypes &&
                ((x.fromId == from && x.toId == to) || (x.fromId == to && x.toId == from))
        }
        fun hasSibling(a: String, b: String): Boolean = data.relations.any { x ->
            inScope(x.fromId, x.toId) && x.type == RelationType.SIBLING &&
                ((x.fromId == a && x.toId == b) || (x.fromId == b && x.toId == a))
        }
        fun hasZhouliLianjin(a: String, b: String): Boolean =
            data.relations.any { x ->
                inScope(x.fromId, x.toId) &&
                    (x.type == RelationType.LIANJIN || x.type == RelationType.ZHOULI) &&
                    ((x.fromId == a && x.toId == b) || (x.fromId == b && x.toId == a))
            } ||
            extra.any { x ->
                (x.type == RelationType.LIANJIN || x.type == RelationType.ZHOULI) &&
                    ((x.fromId == a && x.toId == b) || (x.fromId == b && x.toId == a))
            }

        fun isAncestorOf(ancestor: String, descendant: String): Boolean {
            if (ancestor == descendant) return true
            val stack = ArrayDeque<String>()
            stack.add(descendant)
            val seen = mutableSetOf<String>()
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!seen.add(cur)) continue
                if (cur == ancestor) return true
                parentsOf[cur].orEmpty().forEach { stack.add(it) }
            }
            return false
        }

        fun addParent(parentId: String, childId: String) {
            if (!inScope(parentId, childId) || parentId == childId || hasParentChild(parentId, childId)) return
            // 防环：child 不能是 parent 的祖先（避免爷爷和孙子是同一个人）
            if (isAncestorOf(childId, parentId)) return
            val t = when (byId[parentId]?.gender) {
                Gender.MALE -> RelationType.FATHER
                Gender.FEMALE -> RelationType.MOTHER
                else -> RelationType.PARENT
            }
            extra.add(Relation(UUID.randomUUID().toString(), t, parentId, childId))
            parentsOf.getOrPut(childId) { mutableListOf() }.add(parentId)
            childrenOf.getOrPut(parentId) { mutableListOf() }.add(childId)
        }
        fun addSibling(a: String, b: String) {
            if (!inScope(a, b) || a == b || hasSibling(a, b)) return
            if (extra.any { x -> x.type == RelationType.SIBLING &&
                    ((x.fromId == a && x.toId == b) || (x.fromId == b && x.toId == a)) }) return
            // 同代关系不得出现在祖先后代之间（防矛盾数据）
            if (isAncestorOf(a, b) || isAncestorOf(b, a)) return
            extra.add(Relation(UUID.randomUUID().toString(), RelationType.SIBLING, a, b))
        }
        // 妯娌（兄弟之妻）/ 连襟（姐妹之夫）：按双方性别判定
        fun addZhouliLianjin(a: String, b: String) {
            if (!inScope(a, b) || a == b || hasZhouliLianjin(a, b)) return
            // 同代关系不得出现在祖先后代之间（防矛盾数据）
            if (isAncestorOf(a, b) || isAncestorOf(b, a)) return
            val t = when {
                byId[a]?.gender == Gender.FEMALE && byId[b]?.gender == Gender.FEMALE -> RelationType.ZHOULI
                byId[a]?.gender == Gender.MALE && byId[b]?.gender == Gender.MALE -> RelationType.LIANJIN
                else -> return
            }
            extra.add(Relation(UUID.randomUUID().toString(), t, a, b))
        }

        when (r.type) {
            RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                val parent = r.fromId
                val child = r.toId
                spousesOf[parent].orEmpty().forEach { s -> addParent(s, child) }
                // 子女的兄弟姐妹继承同一父母（刘华的父亲 → 刘荣的父亲）
                siblingsOf[child].orEmpty().forEach { s -> if (s != child) addParent(parent, s) }
                (childrenOf[parent].orEmpty()).forEach { c -> if (c != child) addSibling(child, c) }
                spousesOf[parent].orEmpty().forEach { s ->
                    (childrenOf[s].orEmpty()).forEach { c -> if (c != child) addSibling(child, c) }
                }
            }
            RelationType.SON, RelationType.DAUGHTER -> {
                val parent = r.toId
                val child = r.fromId
                spousesOf[parent].orEmpty().forEach { s -> addParent(s, child) }
                // 子女的兄弟姐妹继承同一父母
                siblingsOf[child].orEmpty().forEach { s -> if (s != child) addParent(parent, s) }
                (childrenOf[parent].orEmpty()).forEach { c -> if (c != child) addSibling(child, c) }
                spousesOf[parent].orEmpty().forEach { s ->
                    (childrenOf[s].orEmpty()).forEach { c -> if (c != child) addSibling(child, c) }
                }
            }
            RelationType.SPOUSE -> {
                val a = r.fromId
                val b = r.toId
                childrenOf[a].orEmpty().forEach { c -> addParent(b, c) }
                childrenOf[b].orEmpty().forEach { c -> addParent(a, c) }
                childrenOf[a].orEmpty().forEach { ca ->
                    childrenOf[b].orEmpty().forEach { cb -> if (ca != cb) addSibling(ca, cb) }
                }
                // 连襟/妯娌：双方的兄弟姐妹的配偶
                siblingsOf[a].orEmpty().forEach { s ->
                    spousesOf[s].orEmpty().forEach { s2 -> addZhouliLianjin(b, s2) }
                }
                siblingsOf[b].orEmpty().forEach { s ->
                    spousesOf[s].orEmpty().forEach { s2 -> addZhouliLianjin(a, s2) }
                }
            }
            RelationType.SIBLING -> {
                val a = r.fromId
                val b = r.toId
                // 兄弟姐妹共享父母：把双方已登记的父母互相补全
                parentsOf[a].orEmpty().forEach { pa -> addParent(pa, b) }
                parentsOf[b].orEmpty().forEach { pb -> addParent(pb, a) }
                spousesOf[a].orEmpty().forEach { sa ->
                    spousesOf[b].orEmpty().forEach { sb -> addZhouliLianjin(sa, sb) }
                }
            }
            else -> Unit
        }

        if (extra.isNotEmpty()) {
            data = data.copy(relations = data.relations + extra)
        }
    }

    /**
     * 刷新全部关系：按现有全部关系重新执行自动推断，补全缺失的关联亲属
     * （配偶互为对方子女的父/母、兄弟姐妹互认、连襟/妯娌），反复执行直到不再新增。
     * 返回新增条数。家谱拓扑图会在数据变化后自动更新。
     */
    fun refreshAllRelations(): Int = refreshFamilyRelations(null)

    /**
     * 按家族范围刷新：仅对所选家族（filter=null 表示全部）内的成员执行推断补全：
     *  1) 数据清洗：去除矛盾/重复关系（同一对父母子女只留一条、同一对自定义只留一条等）；
     *  2) 配偶互为对方子女的父/母、兄弟姐妹互认并继承父母、连襟/妯娌（迭代至闭合，防环）；
     *  3) 为家族内所有已关联成员保存跨代/姻亲推断关系（一对只留一条）。
     * 返回关系净变化数。切换家族后点击“刷新”即可按规则更新该家族的拓扑。
     */
    fun refreshFamilyRelations(filter: String?): Int {
        val before = data.relations.size
        applyAllRules(filter)
        persist()
        return data.relations.size - before
    }

    /** 按最新规则全局更新所有关系：清洗 + 关联补全 + 跨代/姻亲推断 */
    private fun applyAllRules(filter: String?) {
        cleanupRelations()
        removeStaleInferredLabels()
        val subset = data.filteredBy(filter)
        val memberIds = subset.persons.map { it.id }.toSet()
        var guard = 0
        while (guard < 6) {
            guard++
            val snapshot = data.relations
                .filter { it.fromId in memberIds && it.toId in memberIds }
                .toList()
            val sizeBefore = data.relations.size
            snapshot.forEach { autoDerive(it, memberIds) }
            if (data.relations.size == sizeBefore) break
        }
        inferCrossGenForAll(subset)
    }

    /**
     * 数据清洗（按唯一性规则）：
     *  - 删除悬空/自环关系；
     *  - 父母子女类同一对只留一条；
     *  - 配偶/兄弟姐妹/连襟/妯娌同一对同类型只留一条；
     *  - 自定义关系同一对只留一条（避免“祖父/孙子同人”等矛盾并存）。
     */
    private fun cleanupRelations() {
        val ids = data.persons.map { it.id }.toSet()
        val personById = data.persons.associateBy { it.id }
        val kept = mutableListOf<Relation>()
        val seenPairs = mutableMapOf<String, Relation>()
        for (raw in data.relations) {
            if (raw.fromId !in ids || raw.toId !in ids || raw.fromId == raw.toId) continue
            // 按成员当前性别规范关系类型：父/母、子/女与性别保持一致（性别修改后自动纠正）
            var r = raw
            val fromGender = personById[raw.fromId]?.gender
            when (raw.type) {
                RelationType.PARENT -> when (fromGender) {
                    Gender.MALE -> r = raw.copy(type = RelationType.FATHER)
                    Gender.FEMALE -> r = raw.copy(type = RelationType.MOTHER)
                    else -> Unit
                }
                RelationType.FATHER -> if (fromGender == Gender.FEMALE) r = raw.copy(type = RelationType.MOTHER)
                RelationType.MOTHER -> if (fromGender == Gender.MALE) r = raw.copy(type = RelationType.FATHER)
                RelationType.SON -> if (fromGender == Gender.FEMALE) r = raw.copy(type = RelationType.DAUGHTER)
                RelationType.DAUGHTER -> if (fromGender == Gender.MALE) r = raw.copy(type = RelationType.SON)
                else -> Unit
            }
            val a = minOf(r.fromId, r.toId)
            val b = maxOf(r.fromId, r.toId)
            val pairKey = "$a|$b"
            val pc = r.type in parentChildTypes
            val sym = r.type == RelationType.SPOUSE || r.type == RelationType.SIBLING ||
                r.type == RelationType.LIANJIN || r.type == RelationType.ZHOULI
            val existing = seenPairs[pairKey]
            if (existing != null) {
                val dup = when {
                    pc && existing.type in parentChildTypes -> true
                    sym && existing.type == r.type -> true
                    r.type == RelationType.CUSTOM && existing.type == RelationType.CUSTOM -> true
                    else -> false
                }
                if (dup) continue
            }
            seenPairs[pairKey] = r
            kept.add(r)
        }
        data = data.copy(relations = removeContradictions(kept))
    }

    /**
     * 删除“亲属矛盾”关系，防止拓扑图代际被无限抬高。按安全顺序迭代清理：
     *  1) 亲子关系 p→c，而 p 的配偶是 c 的后代（p 娶/嫁了自己的晚辈）→ 删除该亲子关系；
     *  2) 同代关系（配偶/兄弟姐妹/连襟/妯娌）出现在祖先后代之间 → 删除该同代关系；
     * 每轮先删“配偶认证”的矛盾亲子关系，再用干净的亲子链判断同代矛盾，
     * 避免矛盾亲子关系反过来污染同代判定（如误删正常婚姻）。
     */
    private fun removeContradictions(list: List<Relation>): List<Relation> {
        var cur = list
        var guard = 0
        while (guard < 20 && cur.isNotEmpty()) {
            guard++
            val before = cur.size

            // 1) 配偶是子女后代的亲子关系
            val spouses = mutableMapOf<String, MutableList<String>>()
            for (r in cur) {
                if (r.type != RelationType.SPOUSE) continue
                spouses.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                spouses.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
            }
            fun childrenOf(rels: List<Relation>): Map<String, List<String>> {
                val m = mutableMapOf<String, MutableList<String>>()
                for (r in rels) {
                    when (r.type) {
                        RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT ->
                            m.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                        RelationType.SON, RelationType.DAUGHTER ->
                            m.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                        else -> Unit
                    }
                }
                return m
            }
            val ch1 = childrenOf(cur)
            fun descendantsOf(start: String): Set<String> {
                val out = mutableSetOf<String>()
                val stack = ArrayDeque<String>()
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    if (!out.add(x)) continue
                    ch1[x].orEmpty().forEach { stack.add(it) }
                }
                return out
            }
            cur = cur.filterNot { r ->
                if (r.type !in parentChildTypes) return@filterNot false
                val p = if (r.type == RelationType.SON || r.type == RelationType.DAUGHTER) r.toId else r.fromId
                val c = if (r.type == RelationType.SON || r.type == RelationType.DAUGHTER) r.fromId else r.toId
                spouses[p].orEmpty().any { it in descendantsOf(c) }
            }

            // 2) 祖先后代之间的同代关系
            val ch2 = childrenOf(cur)
            fun isAncestorOf(anc: String, desc: String): Boolean {
                if (anc == desc) return true
                val parentsOf = mutableMapOf<String, MutableList<String>>()
                for (r in cur) {
                    when (r.type) {
                        RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT ->
                            parentsOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                        RelationType.SON, RelationType.DAUGHTER ->
                            parentsOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                        else -> Unit
                    }
                }
                val stack = ArrayDeque<String>()
                stack.add(desc)
                val seen = mutableSetOf<String>()
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    if (!seen.add(x)) continue
                    if (x == anc) return true
                    parentsOf[x].orEmpty().forEach { stack.add(it) }
                }
                return false
            }
            cur = cur.filterNot { r ->
                r.type in sameGenTypes &&
                    (isAncestorOf(r.fromId, r.toId) || isAncestorOf(r.toId, r.fromId))
            }

            if (cur.size == before) break
        }
        return cur
    }

    /**
     * 为范围内所有成员保存推断亲属关系：
     *  祖辈、叔伯姑舅姨、堂表亲、侄甥辈、孙辈（跨代血缘），
     *  配偶父母（岳父/岳母/公公/婆婆）、子女配偶（儿媳/女婿）、继父母/继子女（姻亲与继亲）。
     * 以自定义关系保存（标签为中文规范称谓），跳过已有直接关系的成员对；
     * 兄弟姐妹类已由关联补全步骤以正式关系保存，此处不再重复。
     * 存储方向统一为：label 描述 fromId 相对 toId（如“刘华 — 儿媳 — 姜自安”读作“刘华是姜自安的儿媳”）。
     */
    private fun inferCrossGenForAll(subset: FamilyData) {
        val saveCats = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10)
        val existingPairs = mutableSetOf<Pair<String, String>>()
        data.relations.forEach { r ->
            existingPairs.add(minOf(r.fromId, r.toId) to maxOf(r.fromId, r.toId))
        }
        // derive(p) 的 label 描述 d.other 相对 p；要得到“a 相对 b”的标签，须取 derive(b) 视角。
        // 所有类目都是对称的（祖辈↔孙辈、叔伯↔侄甥、姻亲↔子女配偶、继父母↔继子女），
        // 因此每个成员对都能从 b 的视角推导出 a 的称谓。
        val pairLabels = mutableMapOf<Pair<String, String>, String>()
        for (p in subset.persons) {
            val derived = KinshipInference.derive(p, subset, I18n.ZH)
            for (d in derived) {
                if (d.category !in saveCats) continue
                if (d.otherId == p.id) continue
                val a = minOf(p.id, d.otherId)
                val b = maxOf(p.id, d.otherId)
                if (existingPairs.contains(a to b)) continue
                // 同一对成员只保留一条推断关系（避免“祖父/孙子同人”等矛盾并存）
                if (p.id == b) pairLabels.getOrPut(a to b) { d.label }
            }
        }
        val added = pairLabels.map { (pair, label) ->
            Relation(UUID.randomUUID().toString(), RelationType.CUSTOM, pair.first, pair.second, label)
        }
        if (added.isNotEmpty()) {
            data = data.copy(relations = data.relations + added)
        }
    }

    /**
     * 删除旧版自动推断保存的自定义关系（其称谓方向/内容可能已过时或由矛盾数据产生），
     * 随后由 inferCrossGenForAll 按最新关系与统一方向重新推导。
     * 用户手工输入的自定义称谓不在规范词表中，不会被删除。
     */
    private fun removeStaleInferredLabels() {
        val vocab = KinshipInference.standardLabelsZh()
        val kept = data.relations.filterNot { r ->
            r.type == RelationType.CUSTOM && r.label.trim() in vocab
        }
        if (kept.size != data.relations.size) {
            data = data.copy(relations = kept)
        }
    }

    fun deleteRelation(id: String) {
        data = data.copy(relations = data.relations.filterNot { it.id == id })
        persist()
    }

    /** 批量把已有成员加入指定家族（多家族归属：已在该家族则跳过） */
    fun movePersonsToFamily(ids: List<String>, familyId: String) {
        if (ids.isEmpty() || familyId.isBlank()) return
        val idSet = ids.toSet()
        data = data.copy(
            persons = data.persons.map { p ->
                if (p.id in idSet && familyId != p.familyId && familyId !in p.familyIds) {
                    p.copy(familyIds = p.familyIds + familyId)
                } else p
            },
        )
        persist()
    }

    fun loadSample() {
        data = sampleFamily()
        persist()
    }

    // ---------- 成员照片（最多 3 张/人） ----------
    fun photoFile(name: String): java.io.File = repo.photoFile(name)

    fun addPhoto(personId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val p = data.persons.firstOrNull { it.id == personId } ?: return
        if (p.photos.size >= 3) return
        val name = repo.savePhoto(bytes)
        data = data.copy(
            persons = data.persons.map { if (it.id == personId) it.copy(photos = it.photos + name) else it },
        )
        persist()
    }

    fun removePhoto(personId: String, name: String) {
        repo.deletePhotoFile(name)
        data = data.copy(
            persons = data.persons.map {
                if (it.id == personId) it.copy(photos = it.photos - name) else it
            },
        )
        persist()
    }

    fun clearAll() {
        repo.listPhotoFiles().forEach { it.delete() }
        data = FamilyData()
        persist()
    }

    /** 导出 JSON（含照片内容 Base64 内嵌，换设备导入不丢照片） */
    fun toJson(): String = repo.exportWithPhotos(data)

    /** 导入 JSON；merge=false 替换现有数据，merge=true 合并追加 */
    fun importJson(text: String, merge: Boolean): ImportResult {
        return try {
            val (incoming, photos) = repo.importWithPhotos(text)
            importFamilyData(incoming, merge, photos)
        } catch (e: Exception) {
            ImportResult.Error("导入失败：${e.message ?: "文件格式不正确"}")
        }
    }

    /** 通用家族数据导入（JSON / GEDCOM 解析后共用）；merge=false 替换，merge=true 合并追加。
     *  photos 为 JSON 中内嵌的照片文件内容（旧版/第三方文件可为空）。 */
    fun importFamilyData(
        incoming: FamilyData,
        merge: Boolean,
        photos: Map<String, ByteArray> = emptyMap(),
    ): ImportResult {
        val ids = incoming.persons.map { it.id }.toSet()
        val validRelations = incoming.relations
            .filter { it.fromId in ids && it.toId in ids && it.fromId != it.toId }
        if (incoming.persons.isEmpty()) return ImportResult.Error("文件中没有有效数据")
        data = if (merge) {
            val idMap = incoming.persons.associate { it.id to UUID.randomUUID().toString() }
            // 家族同名合并：与现有同名家族并入同一组，避免重复分组
            val famByName = data.families.associateBy { it.name.trim() }
            val famMap = incoming.families.associate { f ->
                f.id to (famByName[f.name.trim()]?.id ?: UUID.randomUUID().toString())
            }
            val newFamilies = incoming.families.mapNotNull { f ->
                if (famByName.containsKey(f.name.trim())) null else f.copy(id = famMap.getValue(f.id))
            }
            val newPersons = incoming.persons.map { p ->
                val pid = idMap.getValue(p.id)
                // 照片文件重命名保存，避免与现有成员的照片文件冲突
                val newPhotos = p.photos.map { old ->
                    val bytes = photos[old]
                    if (bytes != null) repo.savePhoto(bytes) else old
                }
                p.copy(
                    id = pid,
                    familyId = if (p.familyId.isBlank()) "" else famMap[p.familyId] ?: "",
                    familyIds = p.familyIds.mapNotNull { f -> famMap[f] },
                    photos = newPhotos,
                )
            }
            val newRelations = validRelations.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    fromId = idMap.getValue(it.fromId),
                    toId = idMap.getValue(it.toId),
                )
            }
            data.copy(
                persons = data.persons + newPersons,
                relations = data.relations + newRelations,
                families = data.families + newFamilies,
            )
        } else {
            // 替换：按原名写回照片文件
            photos.forEach { (n, b) -> repo.writePhoto(n, b) }
            FamilyData(incoming.persons, validRelations, incoming.families)
        }
        // 数据量不大时立即补全关联亲属；大数据量导入由“刷新”按钮按需执行，避免卡顿
        if (data.persons.size <= 300) applyAllRules(null)
        // 替换后清理不再被引用的旧照片文件
        if (!merge) cleanupOrphanPhotos()
        persist()
        return ImportResult.Success
    }

    /** 删除所有未被任何成员引用的照片文件（替换导入/清理后调用） */
    private fun cleanupOrphanPhotos() {
        val referenced = data.persons.flatMap { it.photos }.toSet()
        repo.listPhotoFiles().filter { it.name !in referenced }.forEach { it.delete() }
    }

    /** 自然语言解析结果导入：同名成员自动复用（视为同一人），返回（新增成员数，新增关系数） */
    fun importParsed(
        parsedPersons: List<ParsedPerson>,
        parsedRelations: List<ParsedRel>,
        familyId: String,
    ): Pair<Int, Int> {
        val existingByName = data.persons.associateBy { it.name.trim() }
        val idMap = LinkedHashMap<String, String>()
        var addedPersons = 0
        val toAdd = mutableListOf<Person>()
        val toUpdate = mutableListOf<Person>()
        for (p in parsedPersons) {
            val name = p.name.trim()
            if (name.isBlank()) continue
            val existing = existingByName[name]
            if (existing != null) {
                idMap[name] = existing.id
                if (existing.gender == Gender.UNKNOWN && p.gender != Gender.UNKNOWN) {
                    toUpdate.add(existing.copy(gender = p.gender))
                }
            } else {
                val id = UUID.randomUUID().toString()
                idMap[name] = id
                toAdd.add(
                    Person(
                        id = id,
                        name = name,
                        gender = p.gender,
                        familyId = familyId,
                        familyIds = if (familyId.isBlank()) emptyList() else listOf(familyId),
                    ),
                )
                addedPersons++
            }
        }
        val parentChildTypes = setOf(
            RelationType.FATHER, RelationType.MOTHER,
            RelationType.SON, RelationType.DAUGHTER, RelationType.PARENT,
        )
        var addedRels = 0
        val toAddRels = mutableListOf<Relation>()
        for (r in parsedRelations) {
            val from = idMap[r.from] ?: continue
            val to = idMap[r.to] ?: continue
            if (from == to) continue
            // 亲子关系成环检测（文字描述自相矛盾时拒绝该条）
            if (r.type in parentChildTypes && parentChildCycle(r.type, from, to)) continue
            val dup = data.relations.any { x ->
                when {
                    r.type in parentChildTypes && x.type in parentChildTypes ->
                        setOf(x.fromId, x.toId) == setOf(from, to)
                    r.type == RelationType.SPOUSE || r.type == RelationType.SIBLING ||
                        r.type == RelationType.LIANJIN || r.type == RelationType.ZHOULI ->
                        x.type == r.type &&
                            ((x.fromId == from && x.toId == to) || (x.fromId == to && x.toId == from))
                    else -> false
                }
            } || toAddRels.any { x ->
                when {
                    r.type in parentChildTypes && x.type in parentChildTypes ->
                        setOf(x.fromId, x.toId) == setOf(from, to)
                    r.type == RelationType.SPOUSE || r.type == RelationType.SIBLING ||
                        r.type == RelationType.LIANJIN || r.type == RelationType.ZHOULI ->
                        x.type == r.type &&
                            ((x.fromId == from && x.toId == to) || (x.fromId == to && x.toId == from))
                    else -> false
                }
            }
            if (dup) continue
            toAddRels.add(Relation(UUID.randomUUID().toString(), r.type, from, to, r.label))
            addedRels++
        }
        val updatedPersons = data.persons.map { p ->
            toUpdate.firstOrNull { it.id == p.id } ?: p
        }
        data = data.copy(persons = updatedPersons + toAdd, relations = data.relations + toAddRels)
        // 导入后立即按最新规则补全关联亲属（兄弟姐妹互认、跨代称谓等）
        applyAllRules(null)
        persist()
        return addedPersons to addedRels
    }

    private fun persist() = repo.save(data)

    /** 按规则版本执行全局更新（升级后自动触发一次） */
    companion object {
        private const val RULES_VERSION = 8
    }
}
