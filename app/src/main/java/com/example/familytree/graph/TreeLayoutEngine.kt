package com.example.familytree.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.familytree.data.FamilyData
import com.example.familytree.data.RelationType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一条折线（dashed=true 表示虚线；isCustom=true 表示自定义/推断虚线，可整体隐藏） */
data class EdgePath(val points: List<Offset>, val dashed: Boolean, val isCustom: Boolean = false)

/** 布局结果：节点矩形 + 连线 + 总尺寸（单位均为逻辑像素）+ 总代数 */
data class TreeLayout(
    val boxes: Map<String, Rect>,
    val paths: List<EdgePath>,
    val width: Float,
    val height: Float,
    val generations: Int,
)

/**
 * 一个“家庭单元”：一对夫妻（或单身）及其子女单元。
 * 整棵单元树就是家谱的分支拓扑。
 */
private class FamilyUnit(val key: String, val adults: MutableList<String>) {
    val children = mutableListOf<FamilyUnit>()
    var x = 0f
    var depth = 0
    var width = 0f
    val adultWidth: Float
        get() = TreeLayoutEngine.BOX_W * adults.size + TreeLayoutEngine.COUPLE_GAP * (adults.size - 1)
}

/**
 * 家谱分层布局引擎：
 *  - 无父母者为“根”，从根向下按代分行（row）；
 *  - 夫妻并排显示，子女居中挂在夫妻下方（经典家族树分支）；
 *  - 不再由正常分支承载的父母用虚线连接（如姻亲长辈、再婚家庭）；
 *  - 兄弟姐妹/自定义关系用虚线直连。
 */
object TreeLayoutEngine {
    const val BOX_W = 112f
    const val BOX_H = 54f
    const val H_GAP = 26f
    const val COUPLE_GAP = 12f
    const val V_GAP = 56f
    const val COMPONENT_GAP = 70f
    /** 垂直留白 */
    const val PAD = 28f
    /** 左侧代际标签留白 */
    const val GUTTER = 46f
    /** 每一代的行高 */
    const val ROW_H = BOX_H + V_GAP
    /** 世系图列间距与行间距 */
    const val COL_GAP = BOX_W + 150f
    const val ROW_GAP2 = BOX_H + 16f
    private const val BRANCH_OFFSET = 16f
    private const val HEAD_DROP = 12f

    fun layout(data: FamilyData): TreeLayout {
        val personById = data.persons.associateBy { it.id }

        // 邻接索引
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        val parentsOf = mutableMapOf<String, MutableList<String>>()
        val spousesOf = mutableMapOf<String, MutableList<String>>()
        for (r in data.relations) {
            when (r.type) {
                // 父→子方向
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    childrenOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                    parentsOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                }
                // 子→父方向
                RelationType.SON, RelationType.DAUGHTER -> {
                    childrenOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                    parentsOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                }
                RelationType.SPOUSE -> {
                    spousesOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                    spousesOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                }
                else -> Unit
            }
        }

        val visited = mutableSetOf<String>()

        // ---------- 代际计算 ----------
        // 规则：
        //  1) 无父母者初始为第 1 代（gen 0），随后可被配偶/兄弟姐妹关系抬低到后代；
        //  2) 子女代 = 父母代 + 1（单调向下传播，连锁更新）；
        //  3) 配偶/兄弟姐妹同代：双方代际不同时，把较低一方抬到较高一方（并连锁抬低其子女）。
        // 迭代直到稳定——保证配偶/兄弟姐妹永远显示在同一行。
        val gen = mutableMapOf<String, Int>()
        for (p in data.persons) {
            if (parentsOf[p.id].isNullOrEmpty()) gen[p.id] = 0
        }
        var genChanged = true
        var guard = 0
        val maxIter = data.persons.size * 2 + 4
        while (genChanged && guard < maxIter) {
            genChanged = false
            guard++
            for (r in data.relations) {
                when (r.type) {
                    RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                        val pg = gen[r.fromId] ?: continue
                        val want = pg + 1
                        if ((gen[r.toId] ?: -1) < want) {
                            gen[r.toId] = want
                            genChanged = true
                        }
                    }
                    RelationType.SON, RelationType.DAUGHTER -> {
                        val pg = gen[r.toId] ?: continue
                        val want = pg + 1
                        if ((gen[r.fromId] ?: -1) < want) {
                            gen[r.fromId] = want
                            genChanged = true
                        }
                    }
                    RelationType.SPOUSE, RelationType.SIBLING, RelationType.LIANJIN, RelationType.ZHOULI -> {
                        val ga = gen[r.fromId]
                        val gb = gen[r.toId]
                        when {
                            ga == null && gb != null -> {
                                gen[r.fromId] = gb
                                genChanged = true
                            }
                            gb == null && ga != null -> {
                                gen[r.toId] = ga
                                genChanged = true
                            }
                            ga != null && gb != null && ga != gb -> {
                                // 同代对齐：把低的一方抬到高的一方
                                val g = maxOf(ga, gb)
                                if (ga < g) {
                                    gen[r.fromId] = g
                                    genChanged = true
                                }
                                if (gb < g) {
                                    gen[r.toId] = g
                                    genChanged = true
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        // 数据存在“亲属矛盾环”（如某人同时是另一人的配偶与祖先）时，上面的同代对齐
        // 无法收敛，代际会被无限抬高（三代人可能算出上百代）。此时退化为仅按亲子关系
        // 计算代际，并对代际值设上限，保证显示正常。
        if (genChanged) {
            gen.clear()
            for (p in data.persons) {
                if (parentsOf[p.id].isNullOrEmpty()) gen[p.id] = 0
            }
            val cap = data.persons.size + 4
            var guard2 = 0
            var changed2 = true
            while (changed2 && guard2 < maxIter) {
                changed2 = false
                guard2++
                for (r in data.relations) {
                    when (r.type) {
                        RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                            val pg = gen[r.fromId] ?: continue
                            val want = min(pg + 1, cap)
                            if ((gen[r.toId] ?: -1) < want) {
                                gen[r.toId] = want
                                changed2 = true
                            }
                        }
                        RelationType.SON, RelationType.DAUGHTER -> {
                            val pg = gen[r.toId] ?: continue
                            val want = min(pg + 1, cap)
                            if ((gen[r.fromId] ?: -1) < want) {
                                gen[r.fromId] = want
                                changed2 = true
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        for (p in data.persons) gen.putIfAbsent(p.id, 0)
        val maxGen = gen.values.maxOrNull() ?: 0

        /** 子单元 key -> 正常分支所挂靠的父单元（用于判定“额外父母”） */
        val attachParent = mutableMapOf<String, FamilyUnit>()
        /** 子单元 key -> 亲子关系中具体作为子女的成员 id（用于把连线精确画到该节点头上） */
        val attachChild = mutableMapOf<String, String>()
        /** 单元 key -> 该单元的主轴成员（通过他挂接到父辈分支的人） */
        val unitMainOf = mutableMapOf<String, String>()

        fun buildUnit(mainId: String): FamilyUnit? {
            if (mainId !in personById) return null
            val adults = mutableListOf(mainId)
            spousesOf[mainId]?.forEach { s -> if (s in personById && s !in adults) adults.add(s) }
            val key = adults.sorted().joinToString(",")
            if (key in visited) return null // 防环
            visited.add(key)
            val unit = FamilyUnit(key, adults)
            unitMainOf[unit.key] = mainId
            val childIds = linkedSetOf<String>()
            adults.forEach { a ->
                childrenOf[a]?.forEach { c -> if (c in personById) childIds.add(c) }
            }
            // 子女从左到右按年龄从大到小（出生年份升序）；无出生年份的排在最右
            val orderedChildIds = childIds.sortedWith(
                compareBy { id ->
                    Regex("\\d{4}").find(personById[id]?.birth ?: "")?.value?.toIntOrNull()
                        ?: Int.MAX_VALUE
                },
            )
            for (c in orderedChildIds) {
                buildUnit(c)?.let { child ->
                    unit.children.add(child)
                    attachParent[child.key] = unit
                    attachChild[child.key] = c
                }
            }
            return unit
        }

        // 根组件：无父母者；再从根向下生长出整棵单元树
        val roots = data.persons.filter { parentsOf[it.id].isNullOrEmpty() }.map { it.id }
        val components = roots.mapNotNull { buildUnit(it) }.toMutableList()

        // 兜底：未进入任何单元的孤立成员（例如只有兄弟姐妹/自定义关系）
        val covered = mutableSetOf<String>()
        fun collectIds(u: FamilyUnit) {
            u.adults.forEach { covered.add(it) }
            u.children.forEach { collectIds(it) }
        }
        components.forEach { collectIds(it) }
        for (p in data.persons) {
            if (p.id !in covered) {
                buildUnit(p.id)?.let { components.add(it) }
                    ?: components.add(FamilyUnit(p.id, mutableListOf(p.id)))
            }
        }

        // ---------- 分支归属优化 ----------
        // 夫妻单元挂接到“子女更多”的父母一侧，使成员与其兄弟姐妹共用同一条分支横线，
        // 避免出现另起一条平行线的“额外父母”连接（如刘华应挂到刘榜明+金传英下）。
        val unitByKey = mutableMapOf<String, FamilyUnit>()
        fun registerUnit(u: FamilyUnit) {
            unitByKey[u.key] = u
            u.children.forEach { registerUnit(it) }
        }
        components.forEach { registerUnit(it) }

        fun unitOfX(id: String): FamilyUnit? {
            fun find(u: FamilyUnit): FamilyUnit? {
                if (id in u.adults) return u
                for (c in u.children) find(c)?.let { return it }
                return null
            }
            for (c in components) find(c)?.let { return it }
            return null
        }

        val candidates = mutableMapOf<String, MutableMap<FamilyUnit, String>>()
        for (r in data.relations) {
            val parentId: String
            val childId: String
            when (r.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    parentId = r.fromId
                    childId = r.toId
                }
                RelationType.SON, RelationType.DAUGHTER -> {
                    parentId = r.toId
                    childId = r.fromId
                }
                else -> continue
            }
            val cu = unitOfX(childId) ?: continue
            val pu = unitOfX(parentId) ?: continue
            if (pu == cu || parentId in cu.adults) continue
            candidates.getOrPut(cu.key) { mutableMapOf() }[pu] = childId
        }
        for ((cuKey, map) in candidates) {
            val cu = unitByKey[cuKey] ?: continue
            val curParent = attachParent[cuKey] ?: continue
            val curCount = curParent.children.size
            var best: FamilyUnit? = null
            var bestChildId: String? = null
            var bestCount = -1
            for ((pu, childId) in map) {
                val n = pu.children.size
                if (n > bestCount) {
                    bestCount = n
                    best = pu
                    bestChildId = childId
                }
            }
            val chosen = best ?: continue
            val childId = bestChildId ?: continue
            if (chosen == curParent) continue
            if (bestCount <= curCount) continue // 仅当候选父母子女更多时才重挂
            // 防环：chosen 不能在 cu 的子树中
            var anc = attachParent[chosen.key]
            var cycle = false
            var guard = 0
            while (anc != null && guard < 100) {
                if (anc == cu) {
                    cycle = true
                    break
                }
                anc = attachParent[anc.key]
                guard++
            }
            if (cycle) continue
            // 重挂：从原父母移除，挂到新父母，并重排子女顺序（年长靠左）
            curParent.children.remove(cu)
            chosen.children.add(cu)
            attachParent[cuKey] = chosen
            attachChild[cuKey] = childId
            val sortedChildren = chosen.children.sortedWith(
                compareBy { u ->
                    Regex("\\d{4}").find(
                        personById[attachChild[u.key] ?: u.adults.first()]?.birth ?: "",
                    )?.value?.toIntOrNull() ?: Int.MAX_VALUE
                },
            )
            chosen.children.clear()
            chosen.children.addAll(sortedChildren)
        }

        // ---------- 夫妻左右顺序 ----------
        // 有自己本家（父母已录入，或有兄弟姐妹/自定义亲属）的配偶放左侧，
        // 主轴人放右侧，其本家家族整体在左侧展示。
        val personIsMain = mutableMapOf<String, Boolean>()
        fun markMains(u: FamilyUnit) {
            val main = unitMainOf[u.key]
            u.adults.forEach { personIsMain[it] = (it == main) }
            u.children.forEach { markMains(it) }
        }
        components.forEach { markMains(it) }

        val leftSpouseCandidates = mutableSetOf<String>()
        for (r in data.relations) {
            when (r.type) {
                RelationType.SIBLING, RelationType.CUSTOM -> {
                    leftSpouseCandidates.add(r.fromId)
                    leftSpouseCandidates.add(r.toId)
                }
                else -> Unit
            }
        }
        for (p in data.persons) {
            if (!parentsOf[p.id].isNullOrEmpty()) leftSpouseCandidates.add(p.id)
        }

        // ---------- 组件就近排列 ----------
        // 排列规则（夫妻为主轴布局）：
        //  - 兄弟姐妹/自定义旁系 → 关联组件【左侧】；
        //  - 父母家族：子女为主轴人 → 放【右侧】；子女为配偶（非主轴）→ 放【左侧】，
        //    即“刘华”的本家整个家族都展示在左边；
        //  - 无连接 → 追加到末尾。
        val compOf = mutableMapOf<String, Int>()
        components.forEachIndexed { i, c ->
            fun mark(u: FamilyUnit) {
                u.adults.forEach { compOf[it] = i }
                u.children.forEach { mark(it) }
            }
            mark(c)
        }
        val compCount = components.size
        if (compCount > 1) {
            val weights = Array(compCount) { IntArray(compCount) }
            val leftLink = Array(compCount) { IntArray(compCount) }  // i 应放在 j 的左侧
            val rightLink = Array(compCount) { IntArray(compCount) } // i 应放在 j 的右侧
            for (r in data.relations) {
                val ci = compOf[r.fromId] ?: continue
                val cj = compOf[r.toId] ?: continue
                if (ci == cj) continue
                when (r.type) {
                    RelationType.SIBLING, RelationType.CUSTOM, RelationType.LIANJIN, RelationType.ZHOULI -> {
                        weights[ci][cj] += 1
                        weights[cj][ci] += 1
                        leftLink[ci][cj] += 1
                        leftLink[cj][ci] += 1
                    }
                    RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                        // from=父母(ci), to=子女(cj)：子女为主轴人 → 父母家族在右；否则在左
                        weights[ci][cj] += 2
                        weights[cj][ci] += 2
                        if (personIsMain[r.toId] == true) {
                            rightLink[ci][cj] += 1
                        } else {
                            leftLink[ci][cj] += 1
                        }
                    }
                    RelationType.SON, RelationType.DAUGHTER -> {
                        // from=子女(ci), to=父母(cj)
                        weights[ci][cj] += 2
                        weights[cj][ci] += 2
                        if (personIsMain[r.fromId] == true) {
                            rightLink[cj][ci] += 1
                        } else {
                            leftLink[cj][ci] += 1
                        }
                    }
                    RelationType.SPOUSE -> {
                        weights[ci][cj] += 2
                        weights[cj][ci] += 2
                        rightLink[ci][cj] += 1
                        rightLink[cj][ci] += 1
                    }
                }
            }
            val order = mutableListOf(0)
            val rest = (1 until compCount).toMutableSet()
            while (rest.isNotEmpty()) {
                var best = -1
                var bestScore = -1
                for (i in rest) {
                    var score = 0
                    for (j in order) score += weights[i][j]
                    // 与已放置组件的总连接为主，与最近放置组件的连接加权，保证贴近
                    val total = score * 2 + weights[i][order.last()]
                    if (total > bestScore) {
                        bestScore = total
                        best = i
                    }
                }
                if (best < 0) best = rest.minOrNull() ?: 0

                // 应放左侧的连接 → 插到第一个关联组件的左侧；否则紧跟关联组件右侧
                var insertAt = -1
                for ((idx, j) in order.withIndex()) {
                    if (leftLink[best][j] > 0) {
                        insertAt = idx
                        break
                    }
                }
                if (insertAt < 0) {
                    var afterAt = -1
                    for ((idx, j) in order.withIndex()) {
                        if (rightLink[best][j] > 0 || weights[best][j] > 0) {
                            afterAt = idx
                            break
                        }
                    }
                    insertAt = if (afterAt >= 0) afterAt + 1 else order.size
                }
                order.add(insertAt, best)
                rest.remove(best)
            }
            val sorted = order.map { components[it] }
            components.clear()
            components.addAll(sorted)
        }

        // 子树宽度
        fun computeWidths(u: FamilyUnit): Float {
            if (u.children.isEmpty()) {
                u.width = u.adultWidth
                return u.width
            }
            var w = 0f
            u.children.forEach { w += computeWidths(it) }
            w += H_GAP * (u.children.size - 1)
            u.width = max(w, u.adultWidth)
            return u.width
        }
        components.forEach { computeWidths(it) }

        fun translate(u: FamilyUnit, dx: Float) {
            u.x += dx
            u.children.forEach { translate(it, dx) }
        }

        // 分层布局：每行 = 代际；子女整行水平居中于父母之下
        fun layoutUnit(u: FamilyUnit, left: Float) {
            u.x = left
            // 单元的行 = 单元内成员（同代）的代际
            u.depth = u.adults.minOfOrNull { gen[it] ?: 0 } ?: 0
            if (u.children.isEmpty()) return
            var cx = left
            for (c in u.children) {
                layoutUnit(c, cx)
                cx += c.width + H_GAP
            }
            val first = u.children.first()
            val last = u.children.last()
            val spanCenter = (first.x + (last.x + last.width)) / 2f
            val center = left + u.width / 2f
            val shift = center - spanCenter
            if (abs(shift) > 0.5f) u.children.forEach { translate(it, shift) }
        }

        // 组件横向排列（代际行全局对齐：所有组件的同一代在同一水平行）
        var compLeft = GUTTER
        for (comp in components) {
            layoutUnit(comp, compLeft)
            compLeft += comp.width + COMPONENT_GAP
        }
        val totalWidth = if (components.isEmpty()) GUTTER * 2 else compLeft - COMPONENT_GAP + GUTTER

        val boxes = mutableMapOf<String, Rect>()
        val paths = mutableListOf<EdgePath>()

        fun topY(depth: Int): Float = PAD + depth * ROW_H

        fun placeUnit(u: FamilyUnit) {
            val centerX = u.x + u.width / 2f
            // 夫妻左右顺序：有本家的配偶在左，主轴人在右
            val main = unitMainOf[u.key]
            val ordered = u.adults.sortedBy { a ->
                when {
                    a == main -> 1
                    a in leftSpouseCandidates -> 0
                    else -> 2
                }
            }
            var ax = centerX - u.adultWidth / 2f
            val y = topY(u.depth)
            for (a in ordered) {
                boxes[a] = Rect(ax, y, ax + BOX_W, y + BOX_H)
                ax += BOX_W + COUPLE_GAP
            }
            u.children.forEach { placeUnit(it) }
        }
        components.forEach { placeUnit(it) }

        // 亲子分支折线：按实际箱体坐标绘制，
        // 竖线从父母箱底正中垂下，横脊线连接各子女，再精确落到“子女本人”箱体顶部中心
        fun branchPaths(u: FamilyUnit) {
            if (u.children.isNotEmpty()) {
                val pBoxes = u.adults.mapNotNull { boxes[it] }.sortedBy { it.left }
                if (pBoxes.isNotEmpty()) {
                    val parentJunctionX = (pBoxes.first().right + pBoxes.last().left) / 2f
                    val parentBottom = pBoxes.maxOf { it.bottom }
                    val branchY = parentBottom + BRANCH_OFFSET
                    // 每个子女的目标：本人箱体顶部中心（已婚也精确到本人，而非夫妻之间）
                    val targets = u.children.mapNotNull { c ->
                        val cid = attachChild[c.key]
                        val box = if (cid != null) boxes[cid]
                        else c.adults.mapNotNull { boxes[it] }.firstOrNull()
                        box?.let { it.center.x to it.top }
                    }
                    if (targets.isNotEmpty()) {
                        var spineFrom = min(parentJunctionX, targets.first().first)
                        var spineTo = max(parentJunctionX, targets.last().first)
                        // 夫妻单元中“有额外父母”的配偶（如姜兴峰的父母在另一分支）：
                        // 横脊线延伸到其头顶，与其父母分支的连线在脊线高度重合，避免出现平行线
                        val shownParents = u.adults.toSet()
                        for (c in u.children) {
                            for (a in c.adults) {
                                val extra = (parentsOf[a] ?: emptyList())
                                    .filter { it !in c.adults && it !in shownParents }
                                if (extra.isNotEmpty()) {
                                    boxes[a]?.let { b ->
                                        spineFrom = min(spineFrom, b.center.x)
                                        spineTo = max(spineTo, b.center.x)
                                    }
                                }
                            }
                        }
                        paths += EdgePath(
                            listOf(
                                Offset(parentJunctionX, parentBottom),
                                Offset(parentJunctionX, branchY),
                                Offset(spineFrom, branchY),
                                Offset(spineTo, branchY),
                            ),
                            dashed = false,
                        )
                        for ((sx, top) in targets) {
                            paths += EdgePath(listOf(Offset(sx, branchY), Offset(sx, top)), dashed = false)
                        }
                    }
                }
            }
            u.children.forEach { branchPaths(it) }
        }
        components.forEach { branchPaths(it) }

        // 夫妻连线（按实际箱体左右顺序连接相邻箱子）
        fun spouseLines(u: FamilyUnit) {
            val sorted = u.adults.mapNotNull { boxes[it] }.sortedBy { it.left }
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                val y = (a.top + a.bottom) / 2f
                paths += EdgePath(listOf(Offset(a.right, y), Offset(b.left, y)), dashed = false)
            }
            u.children.forEach { spouseLines(it) }
        }
        components.forEach { spouseLines(it) }

        fun unitOf(id: String): FamilyUnit? {
            fun find(u: FamilyUnit): FamilyUnit? {
                if (id in u.adults) return u
                for (c in u.children) find(c)?.let { return it }
                return null
            }
            for (c in components) find(c)?.let { return it }
            return null
        }

        // 额外父母：未通过正常分支展示的父母（另一端分支），用实线连接。
        // 水平段走父母行的“脊线高度”（父母箱底 + BRANCH_OFFSET），与兄弟姐妹的主干线
        // 重合在同一条线上，再竖线落到本人头顶——不做侧面进入、不取消连线、不产生平行线。
        for (p in data.persons) {
            val pBox = boxes[p.id] ?: continue
            val own = unitOf(p.id) ?: continue
            val shown = attachParent[own.key]?.adults?.toSet() ?: emptySet()
            val extra = (parentsOf[p.id] ?: emptyList()).filter { it !in own.adults && it !in shown }
            if (extra.isEmpty()) continue

            val startX: Float
            val startY: Float
            if (extra.size >= 2) {
                val list = extra.mapNotNull { boxes[it] }.sortedBy { it.left }
                if (list.size < 2) continue
                val first = list.first()
                val last = list.last()
                startX = if (kotlin.math.abs(first.top - last.top) < 1f && first.right <= last.left) {
                    (first.right + last.left) / 2f
                } else {
                    (first.center.x + last.center.x) / 2f
                }
                startY = maxOf(first.bottom, last.bottom)
            } else {
                val box = boxes[extra[0]] ?: continue
                startX = box.center.x
                startY = box.bottom
            }

            // 与分支横线同一高度；若父母行在本人行下方（异常数据）则退回头顶上方
            val spineY = startY + BRANCH_OFFSET
            val headY = if (spineY < pBox.top - 6f) spineY else pBox.top - HEAD_DROP
            paths += EdgePath(
                listOf(
                    Offset(startX, startY),
                    Offset(startX, headY),
                    Offset(pBox.center.x, headY),
                    Offset(pBox.center.x, pBox.top),
                ),
                dashed = false,
            )
        }

        // 兄弟姐妹 / 连襟 / 妯娌 / 自定义关系：虚线直连
        for (r in data.relations) {
            if (r.type != RelationType.SIBLING && r.type != RelationType.CUSTOM &&
                r.type != RelationType.LIANJIN && r.type != RelationType.ZHOULI
            ) continue
            val a = boxes[r.fromId] ?: continue
            val b = boxes[r.toId] ?: continue
            val (l, rr) = if (a.left <= b.left) a to b else b to a
            val y = (l.top + l.bottom) / 2f
            paths += EdgePath(
                listOf(Offset(l.right, y), Offset(rr.left, y)),
                dashed = true,
                isCustom = r.type == RelationType.CUSTOM,
            )
        }

        val height = PAD + (maxGen + 1) * ROW_H + PAD
        return TreeLayout(boxes, paths, totalWidth, height, maxGen + 1)
    }

    /** 分支图数据：仅保留某成员的祖先、后代及其配偶 */
    fun subtree(data: FamilyData, focusId: String): FamilyData {
        val byId = data.persons.associateBy { it.id }
        if (byId[focusId] == null) return data
        val parentsOf = mutableMapOf<String, MutableList<String>>()
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        val spousesOf = mutableMapOf<String, MutableList<String>>()
        for (r in data.relations) {
            when (r.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    childrenOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                    parentsOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                }
                RelationType.SON, RelationType.DAUGHTER -> {
                    childrenOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                    parentsOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                }
                RelationType.SPOUSE -> {
                    spousesOf.getOrPut(r.fromId) { mutableListOf() }.add(r.toId)
                    spousesOf.getOrPut(r.toId) { mutableListOf() }.add(r.fromId)
                }
                else -> Unit
            }
        }
        val keep = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.add(focusId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!keep.add(id)) continue
            parentsOf[id]?.forEach { if (it !in keep) stack.add(it) }
        }
        stack.add(focusId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!keep.add(id)) continue
            childrenOf[id]?.forEach { if (it !in keep) stack.add(it) }
        }
        keep.toList().forEach { spousesOf[it]?.forEach { keep.add(it) } }
        val relations = data.relations.filter { it.fromId in keep && it.toId in keep }
        return FamilyData(data.persons.filter { it.id in keep }, relations, data.families)
    }

    /** 世系图（左→右）：每代一列，成员纵向排列，亲子肘形连线 */
    fun layoutLineage(data: FamilyData): TreeLayout {
        val base = layout(data)
        val groups = sortedMapOf<Int, MutableList<String>>()
        base.boxes.forEach { (id, r) ->
            val g = ((r.top - PAD) / ROW_H).roundToInt().coerceAtLeast(0)
            groups.getOrPut(g) { mutableListOf() }.add(id)
        }
        val newBoxes = mutableMapOf<String, Rect>()
        groups.forEach { (g, ids) ->
            val sorted = ids.sortedBy { base.boxes[it]?.left ?: 0f }
            sorted.forEachIndexed { i, id ->
                val x = PAD + g * COL_GAP
                val y = PAD + i * ROW_GAP2
                newBoxes[id] = Rect(x, y, x + BOX_W, y + BOX_H)
            }
        }
        val paths = mutableListOf<EdgePath>()

        fun elbow(p: Rect, c: Rect) {
            if (p.left == c.left) return
            val midX = (p.right + c.left) / 2f
            paths += EdgePath(
                listOf(
                    Offset(p.right, p.center.y),
                    Offset(midX, p.center.y),
                    Offset(midX, c.center.y),
                    Offset(c.left, c.center.y),
                ),
                dashed = false,
            )
        }

        for (r in data.relations) {
            when (r.type) {
                RelationType.FATHER, RelationType.MOTHER, RelationType.PARENT -> {
                    val p = newBoxes[r.fromId] ?: continue
                    val c = newBoxes[r.toId] ?: continue
                    elbow(p, c)
                }
                RelationType.SON, RelationType.DAUGHTER -> {
                    val p = newBoxes[r.toId] ?: continue
                    val c = newBoxes[r.fromId] ?: continue
                    elbow(p, c)
                }
                RelationType.SPOUSE -> {
                    val a = newBoxes[r.fromId] ?: continue
                    val b = newBoxes[r.toId] ?: continue
                    if (a.left == b.left) {
                        val (t, bo) = if (a.top <= b.top) a to b else b to a
                        paths += EdgePath(
                            listOf(Offset(t.center.x, t.bottom), Offset(bo.center.x, bo.top)),
                            dashed = false,
                        )
                    } else {
                        val y = (a.center.y + b.center.y) / 2f
                        paths += EdgePath(listOf(Offset(a.right, y), Offset(b.left, y)), dashed = true)
                    }
                }
                RelationType.SIBLING, RelationType.LIANJIN, RelationType.ZHOULI -> {
                    val a = newBoxes[r.fromId] ?: continue
                    val b = newBoxes[r.toId] ?: continue
                    paths += EdgePath(listOf(a.center, b.center), dashed = true)
                }
                RelationType.CUSTOM -> {
                    val a = newBoxes[r.fromId] ?: continue
                    val b = newBoxes[r.toId] ?: continue
                    paths += EdgePath(listOf(a.center, b.center), dashed = true, isCustom = true)
                }
                else -> Unit
            }
        }

        val maxG = groups.keys.maxOrNull() ?: 0
        val maxRows = groups.values.maxOfOrNull { it.size } ?: 0
        return TreeLayout(
            boxes = newBoxes,
            paths = paths,
            width = PAD + (maxG + 1) * COL_GAP,
            height = PAD + maxRows * ROW_GAP2 + PAD,
            generations = maxG + 1,
        )
    }
}
