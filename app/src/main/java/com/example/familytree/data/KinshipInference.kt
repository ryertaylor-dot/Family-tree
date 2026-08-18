package com.example.familytree.data

/** 一条推断出的跨代关系 */
data class DerivedRelation(val otherId: String, val label: String, val category: Int)

/**
 * 跨代亲属自动推断：
 * 根据已添加的直接关系（父亲/母亲/儿子/女儿/配偶/兄弟姐妹），
 * 自动推导祖父/祖母/外祖父/外祖母、孙子/孙女/外孙/外孙女、
 * 叔伯姑舅姨、兄弟姐妹、岳父岳母/公公婆婆、儿媳/女婿、堂表亲、侄甥辈、继父母/继子女。
 * 直接关系一旦变更，推断结果随之自动更新。
 */
object KinshipInference {

    /** 推断称谓使用的全部 i18n key：用于识别“由自动推断保存的自定义关系”，
     *  刷新规则时先清除这些旧称谓（方向/内容可能已过时），再按最新关系重新推导。 */
    val inferredLabelKeys = listOf(
        "l_grandfather", "l_grandmother", "l_grandparent",
        "l_m_grandfather", "l_m_grandmother", "l_m_grandparent",
        "l_uncle_o", "l_uncle_y", "l_aunt_p", "l_p_generic",
        "l_uncle_m", "l_aunt_m", "l_m_generic", "l_uncle_g", "l_aunt_g", "l_ua_g",
        "l_obro", "l_ybro", "l_bro", "l_osis", "l_ysis", "l_sis", "l_sibs",
        "l_half_f", "l_half_m", "l_half_g",
        "l_fil", "l_mil", "l_fil_g", "l_hfil", "l_hmil", "l_inlaws_g",
        "l_sp_father", "l_sp_mother", "l_sp_parents",
        "l_dil", "l_sil", "l_child_spouse",
        "l_gson", "l_gdaughter", "l_gchild",
        "l_m_gson", "l_m_gdaughter", "l_m_gchild",
        "l_nephew", "l_niece", "l_neph_g",
        "l_m_nephew", "l_m_niece", "l_m_neph_g", "l_neph_all",
        "l_cousin_o_m_p", "l_cousin_y_m_p", "l_cousin_m_p",
        "l_cousin_o_f_p", "l_cousin_y_f_p", "l_cousin_f_p", "l_cousin_p",
        "l_cousin_o_m_m", "l_cousin_y_m_m", "l_cousin_m_m",
        "l_cousin_o_f_m", "l_cousin_y_f_m", "l_cousin_f_m", "l_cousin_m",
        "l_step_father", "l_step_mother", "l_step_parent",
        "l_step_son", "l_step_daughter", "l_step_child",
    )

    /** 推断称谓的简体中文取值集合（推断结果统一按中文保存） */
    fun standardLabelsZh(): Set<String> = inferredLabelKeys.mapNotNull { Langs.zh[it] }.toSet()

    // 类目顺序（用于展示排序）：祖辈1、叔伯姑舅姨2、兄弟姐妹3、堂表亲4、侄甥辈5、
    // 配偶父母6、子女配偶7、孙辈8、继父母9、继子女10
    fun derive(me: Person, data: FamilyData, i18n: I18n): List<DerivedRelation> {
        if (data.persons.isEmpty()) return emptyList()
        val byId = data.persons.associateBy { it.id }

        // 规范化邻接表
        val parentsOf = mutableMapOf<String, MutableSet<String>>()   // childId -> parentIds
        val childrenOf = mutableMapOf<String, MutableSet<String>>()  // parentId -> childIds
        val spousesOf = mutableMapOf<String, MutableSet<String>>()   // personId -> spouseIds
        // childId -> parentId -> 有效性别（边类型优先，其次本人性别）
        val parentGenderOf = mutableMapOf<String, MutableMap<String, Gender>>()

        fun linkParent(parentId: String, childId: String, effectiveGender: Gender) {
            parentsOf.getOrPut(childId) { mutableSetOf() }.add(parentId)
            childrenOf.getOrPut(parentId) { mutableSetOf() }.add(childId)
            parentGenderOf.getOrPut(childId) { mutableMapOf() }[parentId] = effectiveGender
        }

        for (r in data.relations) {
            val a = byId[r.fromId] ?: continue
            val b = byId[r.toId] ?: continue
            when (r.type) {
                RelationType.FATHER -> linkParent(r.fromId, r.toId, Gender.MALE)
                RelationType.MOTHER -> linkParent(r.fromId, r.toId, Gender.FEMALE)
                RelationType.PARENT -> linkParent(r.fromId, r.toId, a.gender)
                RelationType.SON -> linkParent(r.toId, r.fromId, b.gender)
                RelationType.DAUGHTER -> linkParent(r.toId, r.fromId, b.gender)
                RelationType.SPOUSE -> {
                    spousesOf.getOrPut(r.fromId) { mutableSetOf() }.add(r.toId)
                    spousesOf.getOrPut(r.toId) { mutableSetOf() }.add(r.fromId)
                }
                else -> Unit
            }
        }

        val result = LinkedHashMap<String, DerivedRelation>()

        fun add(otherId: String, label: String, category: Int) {
            if (otherId == me.id) return
            val existing = result[otherId]
            if (existing == null || category < existing.category) {
                result[otherId] = DerivedRelation(otherId, label, category)
            }
        }

        fun yearOf(p: Person): Int? = Regex("\\d{4}").find(p.birth)?.value?.toIntOrNull()

        val myParents = parentsOf[me.id].orEmpty()
        val myParentGender = parentGenderOf[me.id].orEmpty()
        val myYear = yearOf(me)

        // 1. 祖辈（祖父/祖母/外祖父/外祖母）
        for (pId in myParents) {
            val p = byId[pId] ?: continue
            val side = myParentGender[pId] ?: p.gender
            for (gpId in parentsOf[pId].orEmpty()) {
                val gp = byId[gpId] ?: continue
                val label = when (side) {
                    Gender.MALE -> when (gp.gender) {
                        Gender.MALE -> i18n.s("l_grandfather")
                        Gender.FEMALE -> i18n.s("l_grandmother")
                        Gender.UNKNOWN -> i18n.s("l_grandparent")
                    }
                    Gender.FEMALE -> when (gp.gender) {
                        Gender.MALE -> i18n.s("l_m_grandfather")
                        Gender.FEMALE -> i18n.s("l_m_grandmother")
                        Gender.UNKNOWN -> i18n.s("l_m_grandparent")
                    }
                    Gender.UNKNOWN -> i18n.s("l_grandparent")
                }
                add(gpId, label, 1)
            }
        }

        // 2. 叔伯姑舅姨（父母的兄弟姐妹）
        val uncleAunts = mutableMapOf<String, Boolean>() // 成员id -> 是否为父系
        for (pId in myParents) {
            val p = byId[pId] ?: continue
            val side = myParentGender[pId] ?: p.gender
            val pParents = parentsOf[pId].orEmpty()
            val pYear = yearOf(p)
            for (other in data.persons) {
                if (other.id == me.id || other.id == pId) continue
                val shared = parentsOf[other.id].orEmpty() intersect pParents
                if (shared.isEmpty()) continue
                val oYear = yearOf(other)
                val older = if (pYear != null && oYear != null) oYear < pYear else null
                val label = when (side) {
                    Gender.MALE -> when (other.gender) {
                        Gender.MALE -> if (older == true) i18n.s("l_uncle_o") else i18n.s("l_uncle_y")
                        Gender.FEMALE -> i18n.s("l_aunt_p")
                        Gender.UNKNOWN -> i18n.s("l_p_generic")
                    }
                    Gender.FEMALE -> when (other.gender) {
                        Gender.MALE -> i18n.s("l_uncle_m")
                        Gender.FEMALE -> i18n.s("l_aunt_m")
                        Gender.UNKNOWN -> i18n.s("l_m_generic")
                    }
                    Gender.UNKNOWN -> when (other.gender) {
                        Gender.MALE -> i18n.s("l_uncle_g")
                        Gender.FEMALE -> i18n.s("l_aunt_g")
                        Gender.UNKNOWN -> i18n.s("l_ua_g")
                    }
                }
                add(other.id, label, 2)
                uncleAunts[other.id] = side == Gender.MALE
            }
        }

        // 3. 兄弟姐妹（含同父/同母标记）
        val siblings = mutableMapOf<String, Gender>()
        for (other in data.persons) {
            if (other.id == me.id) continue
            val shared = parentsOf[other.id].orEmpty() intersect myParents
            if (shared.isEmpty()) continue
            val oYear = yearOf(other)
            val older = if (myYear != null && oYear != null) oYear < myYear else null
            val base = when (other.gender) {
                Gender.MALE -> when (older) { true -> i18n.s("l_obro"); false -> i18n.s("l_ybro"); null -> i18n.s("l_bro") }
                Gender.FEMALE -> when (older) { true -> i18n.s("l_osis"); false -> i18n.s("l_ysis"); null -> i18n.s("l_sis") }
                Gender.UNKNOWN -> i18n.s("l_sibs")
            }
            val suffix = if (shared.size >= myParents.size && shared.size >= parentsOf[other.id].orEmpty().size) {
                ""
            } else {
                when (myParentGender[shared.first()] ?: byId[shared.first()]?.gender) {
                    Gender.MALE -> i18n.s("l_half_f")
                    Gender.FEMALE -> i18n.s("l_half_m")
                    else -> i18n.s("l_half_g")
                }
            }
            add(other.id, base + suffix, 3)
            siblings[other.id] = other.gender
        }

        // 4. 堂表亲（叔伯姑舅姨的子女）
        for ((uId, paternal) in uncleAunts) {
            for (kId in childrenOf[uId].orEmpty()) {
                if (kId == me.id) continue
                val k = byId[kId] ?: continue
                val oYear = yearOf(k)
                val older = if (myYear != null && oYear != null) oYear < myYear else null
                val label = if (paternal) {
                    when (k.gender) {
                        Gender.MALE -> when (older) { true -> i18n.s("l_cousin_o_m_p"); false -> i18n.s("l_cousin_y_m_p"); null -> i18n.s("l_cousin_m_p") }
                        Gender.FEMALE -> when (older) { true -> i18n.s("l_cousin_o_f_p"); false -> i18n.s("l_cousin_y_f_p"); null -> i18n.s("l_cousin_f_p") }
                        Gender.UNKNOWN -> i18n.s("l_cousin_p")
                    }
                } else {
                    when (k.gender) {
                        Gender.MALE -> when (older) { true -> i18n.s("l_cousin_o_m_m"); false -> i18n.s("l_cousin_y_m_m"); null -> i18n.s("l_cousin_m_m") }
                        Gender.FEMALE -> when (older) { true -> i18n.s("l_cousin_o_f_m"); false -> i18n.s("l_cousin_y_f_m"); null -> i18n.s("l_cousin_f_m") }
                        Gender.UNKNOWN -> i18n.s("l_cousin_m")
                    }
                }
                add(kId, label, 4)
            }
        }

        // 5. 侄甥辈（兄弟姐妹的子女）
        for ((sibId, sibGender) in siblings) {
            for (nId in childrenOf[sibId].orEmpty()) {
                if (nId == me.id) continue
                val n = byId[nId] ?: continue
                val label = when (sibGender) {
                    Gender.MALE -> when (n.gender) {
                        Gender.MALE -> i18n.s("l_nephew")
                        Gender.FEMALE -> i18n.s("l_niece")
                        Gender.UNKNOWN -> i18n.s("l_neph_g")
                    }
                    Gender.FEMALE -> when (n.gender) {
                        Gender.MALE -> i18n.s("l_m_nephew")
                        Gender.FEMALE -> i18n.s("l_m_niece")
                        Gender.UNKNOWN -> i18n.s("l_m_neph_g")
                    }
                    Gender.UNKNOWN -> i18n.s("l_neph_all")
                }
                add(nId, label, 5)
            }
        }

        // 6. 配偶的父母（岳父/岳母/公公/婆婆）
        for (sId in spousesOf[me.id].orEmpty()) {
            for (pId in parentsOf[sId].orEmpty()) {
                val p = byId[pId] ?: continue
                val label = when (me.gender) {
                    Gender.MALE -> when (p.gender) {
                        Gender.MALE -> i18n.s("l_fil")
                        Gender.FEMALE -> i18n.s("l_mil")
                        Gender.UNKNOWN -> i18n.s("l_fil_g")
                    }
                    Gender.FEMALE -> when (p.gender) {
                        Gender.MALE -> i18n.s("l_hfil")
                        Gender.FEMALE -> i18n.s("l_hmil")
                        Gender.UNKNOWN -> i18n.s("l_inlaws_g")
                    }
                    Gender.UNKNOWN -> when (p.gender) {
                        Gender.MALE -> i18n.s("l_sp_father")
                        Gender.FEMALE -> i18n.s("l_sp_mother")
                        Gender.UNKNOWN -> i18n.s("l_sp_parents")
                    }
                }
                add(pId, label, 6)
            }
        }

        // 7. 子女的配偶（儿媳/女婿）
        for (cId in childrenOf[me.id].orEmpty()) {
            val c = byId[cId] ?: continue
            val side = c.gender.takeIf { it != Gender.UNKNOWN }
                ?: parentGenderOf[cId]?.get(me.id)
                ?: Gender.UNKNOWN
            for (sId in spousesOf[cId].orEmpty()) {
                if (sId == me.id) continue
                val label = when (side) {
                    Gender.MALE -> i18n.s("l_dil")
                    Gender.FEMALE -> i18n.s("l_sil")
                    Gender.UNKNOWN -> i18n.s("l_child_spouse")
                }
                add(sId, label, 7)
            }
        }

        // 8. 孙辈（孙子/孙女/外孙/外孙女）
        for (cId in childrenOf[me.id].orEmpty()) {
            val c = byId[cId] ?: continue
            val side = c.gender.takeIf { it != Gender.UNKNOWN }
                ?: parentGenderOf[cId]?.get(me.id)
                ?: Gender.UNKNOWN
            for (gcId in childrenOf[cId].orEmpty()) {
                val gc = byId[gcId] ?: continue
                val label = when (side) {
                    Gender.MALE -> when (gc.gender) {
                        Gender.MALE -> i18n.s("l_gson")
                        Gender.FEMALE -> i18n.s("l_gdaughter")
                        Gender.UNKNOWN -> i18n.s("l_gchild")
                    }
                    Gender.FEMALE -> when (gc.gender) {
                        Gender.MALE -> i18n.s("l_m_gson")
                        Gender.FEMALE -> i18n.s("l_m_gdaughter")
                        Gender.UNKNOWN -> i18n.s("l_m_gchild")
                    }
                    Gender.UNKNOWN -> i18n.s("l_gchild")
                }
                add(gcId, label, 8)
            }
        }

        val myChildren = childrenOf[me.id].orEmpty()

        // 9. 继父母（父母的其他配偶）
        for (pId in myParents) {
            for (sId in spousesOf[pId].orEmpty()) {
                if (sId == me.id || sId in myParents) continue
                val s = byId[sId] ?: continue
                val label = when (s.gender) {
                    Gender.MALE -> i18n.s("l_step_father")
                    Gender.FEMALE -> i18n.s("l_step_mother")
                    Gender.UNKNOWN -> i18n.s("l_step_parent")
                }
                add(sId, label, 9)
            }
        }

        // 10. 继子女（配偶与其他人的子女）
        for (sId in spousesOf[me.id].orEmpty()) {
            for (kId in childrenOf[sId].orEmpty()) {
                if (kId == me.id || kId in myChildren) continue
                val k = byId[kId] ?: continue
                val label = when (k.gender) {
                    Gender.MALE -> i18n.s("l_step_son")
                    Gender.FEMALE -> i18n.s("l_step_daughter")
                    Gender.UNKNOWN -> i18n.s("l_step_child")
                }
                add(kId, label, 10)
            }
        }

        return result.values.sortedWith(compareBy({ it.category }, { it.label }))
    }
}
