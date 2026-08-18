package com.example.familytree.data

/** 解析出的成员 */
data class ParsedPerson(val name: String, var gender: Gender)

/** 解析出的关系（from 是 to 的 …，类型语义与 Relation 一致） */
data class ParsedRel(
    val from: String,
    val to: String,
    val type: RelationType,
    val label: String = "",
)

/** 解析结果 */
data class ParseResult(
    val persons: List<ParsedPerson>,
    val relations: List<ParsedRel>,
    val unmatched: List<String>,
)

/**
 * 自然语言家谱解析器（离线、基于规则）：
 * 支持“X和Y是夫妻”“X娶了Y”“X是Y的父亲”“Y的父亲是X”
 * “X和Y的儿子是Z”“X生了Z”“X和Y是兄弟/姐妹”等常见句式，
 * 并根据称谓词自动推断性别；祖孙/叔侄等称谓记录为自定义关系。
 */
object TextParser {

    private val fatherWords = setOf("父亲", "爸爸", "爸", "爹", "爹爹")
    private val motherWords = setOf("母亲", "妈妈", "妈", "娘")
    private val sonWords = setOf("儿子", "长子", "次子", "幼子", "独子")
    private val daughterWords = setOf("女儿", "长女", "次女", "幼女", "独女")
    private val brotherWords = setOf("哥哥", "大哥", "二哥", "兄长", "弟弟", "小弟", "兄弟")
    private val sisterWords = setOf("姐姐", "大姐", "二姐", "妹妹", "小妹", "姐妹")
    private val husbandWords = setOf("丈夫", "老公", "先生")
    private val wifeWords = setOf("妻子", "老婆", "太太", "夫人")

    fun parse(text: String): ParseResult {
        val members = LinkedHashMap<String, ParsedPerson>()
        fun person(name: String, gender: Gender = Gender.UNKNOWN): ParsedPerson =
            members.getOrPut(name) { ParsedPerson(name, gender) }.also { p ->
                if (p.gender == Gender.UNKNOWN && gender != Gender.UNKNOWN) p.gender = gender
            }
        val rels = mutableListOf<ParsedRel>()
        fun addRel(from: String, to: String, type: RelationType, label: String = "") {
            if (from == to || from.isBlank() || to.isBlank()) return
            if (rels.any { it.from == from && it.to == to && it.type == type }) return
            rels.add(ParsedRel(from, to, type, label))
        }
        val unmatched = mutableListOf<String>()

        val sentences = text.split(Regex("[。！？；\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        val rel3: (String, String, RelationType) -> Unit = { a, b, t -> addRel(a, b, t) }
        val relCustom: (String, String, String) -> Unit = { a, b, l -> addRel(a, b, RelationType.CUSTOM, l) }
        for (s in sentences) {
            if (!parseSentence(s, ::person, rel3, relCustom)) unmatched.add(s)
        }
        return ParseResult(members.values.toList(), rels, unmatched)
    }

    private fun cleanName(s: String): String = s.trim()
        .trim('，', '、', '。', '；', ' ', '的', '是', '了', '和', '与', '有', '叫')
        .replace(Regex("[，、。；（）()]"), "")
        .trim()

    private fun splitNames(s: String): List<String> {
        var t = s.trim()
        t = t.replace(Regex("^(?:一|两|二|三|四|五|六|七|八|九|十)?个?(?:儿子|女儿|孩子|人)?"), "")
        t = t.replace(Regex("^(?:叫|名|名为|是|为)"), "")
        return t.split(Regex("[、，,和与及]"))
            .map { cleanName(it) }
            .filter { it.isNotBlank() }
    }

    private fun applyKinship(
        subject: String,
        objectName: String,
        word: String,
        person: (String, Gender) -> ParsedPerson,
        addRel: (String, String, RelationType) -> Unit,
        addCustom: (String, String, String) -> Unit,
    ): Boolean {
        return when {
            word in fatherWords -> { person(subject, Gender.MALE); addRel(subject, objectName, RelationType.FATHER); true }
            word in motherWords -> { person(subject, Gender.FEMALE); addRel(subject, objectName, RelationType.MOTHER); true }
            word in sonWords -> { person(subject, Gender.MALE); addRel(subject, objectName, RelationType.SON); true }
            word in daughterWords -> { person(subject, Gender.FEMALE); addRel(subject, objectName, RelationType.DAUGHTER); true }
            word in brotherWords -> { person(subject, Gender.MALE); addRel(subject, objectName, RelationType.SIBLING); true }
            word in sisterWords -> { person(subject, Gender.FEMALE); addRel(subject, objectName, RelationType.SIBLING); true }
            word == "连襟" -> { person(subject, Gender.MALE); addRel(subject, objectName, RelationType.LIANJIN); true }
            word == "妯娌" -> { person(subject, Gender.FEMALE); addRel(subject, objectName, RelationType.ZHOULI); true }
            word in husbandWords -> { person(subject, Gender.MALE); addRel(subject, objectName, RelationType.SPOUSE); true }
            word in wifeWords -> { person(subject, Gender.FEMALE); addRel(subject, objectName, RelationType.SPOUSE); true }
            word == "爱人" -> { addRel(subject, objectName, RelationType.SPOUSE); true }
            word.isNotBlank() -> { person(subject, Gender.UNKNOWN); addCustom(subject, objectName, word); true }
            else -> false
        }
    }

    private fun parseSentence(
        raw: String,
        person: (String, Gender) -> ParsedPerson,
        addRel: (String, String, RelationType) -> Unit,
        addCustom: (String, String, String) -> Unit,
    ): Boolean {
        val s = raw.trim()
        if (s.isBlank()) return true

        // 性别标注：张三（男）/ 张三，男
        Regex("^(.+?)[（(](男|女)[)）]$").find(s)?.let { m ->
            val n = cleanName(m.groupValues[1])
            if (n.isNotBlank()) {
                person(n, if (m.groupValues[2] == "男") Gender.MALE else Gender.FEMALE)
                return true
            }
        }
        Regex("^(.+?)[，,](男|女)$").find(s)?.let { m ->
            val n = cleanName(m.groupValues[1])
            if (n.isNotBlank()) {
                person(n, if (m.groupValues[2] == "男") Gender.MALE else Gender.FEMALE)
                return true
            }
        }

        fun spouse(a: String, b: String, ga: Gender, gb: Gender): Boolean {
            val na = cleanName(a)
            val nb = cleanName(b)
            if (na.isBlank() || nb.isBlank() || na == nb) return false
            person(na, ga)
            person(nb, gb)
            addRel(na, nb, RelationType.SPOUSE)
            return true
        }

        // 夫妻 / 配偶句式
        Regex("^(.+?)和(.+?)是(?:一对)?(?:夫妻|夫妇|两口子|配偶)$").find(s)?.let { m ->
            return spouse(m.groupValues[1], m.groupValues[2], Gender.UNKNOWN, Gender.UNKNOWN)
        }
        Regex("^(.+?)(?:与|和)(.+?)结(?:了)?婚$").find(s)?.let { m ->
            return spouse(m.groupValues[1], m.groupValues[2], Gender.UNKNOWN, Gender.UNKNOWN)
        }
        Regex("^(.+?)娶了(.+?)$").find(s)?.let { m ->
            return spouse(m.groupValues[1], m.groupValues[2], Gender.MALE, Gender.FEMALE)
        }
        Regex("^(.+?)嫁给了(.+?)$").find(s)?.let { m ->
            return spouse(m.groupValues[1], m.groupValues[2], Gender.FEMALE, Gender.MALE)
        }
        Regex("^(.+?)是(.+?)的(丈夫|老公|先生|妻子|老婆|太太|爱人)$").find(s)?.let { m ->
            val g = when (m.groupValues[3]) {
                "丈夫", "老公", "先生" -> Gender.MALE
                "妻子", "老婆", "太太" -> Gender.FEMALE
                else -> Gender.UNKNOWN
            }
            val na = cleanName(m.groupValues[1])
            val nb = cleanName(m.groupValues[2])
            if (na.isNotBlank() && nb.isNotBlank() && na != nb) {
                person(na, g)
                person(nb, Gender.UNKNOWN)
                addRel(na, nb, RelationType.SPOUSE)
                return true
            }
        }
        Regex("^(.+?)的(丈夫|老公|先生|妻子|老婆|太太|爱人)是(.+?)$").find(s)?.let { m ->
            val g = when (m.groupValues[2]) {
                "丈夫", "老公", "先生" -> Gender.MALE
                "妻子", "老婆", "太太" -> Gender.FEMALE
                else -> Gender.UNKNOWN
            }
            val na = cleanName(m.groupValues[3])
            val nb = cleanName(m.groupValues[1])
            if (na.isNotBlank() && nb.isNotBlank() && na != nb) {
                person(na, g)
                person(nb, Gender.UNKNOWN)
                addRel(na, nb, RelationType.SPOUSE)
                return true
            }
        }

        // 生育句式
        Regex("^(.+?)和(.+?)生了(.+?)$").find(s)?.let { m ->
            val p1 = cleanName(m.groupValues[1])
            val p2 = cleanName(m.groupValues[2])
            val children = splitNames(m.groupValues[3])
            if (p1.isNotBlank() && p2.isNotBlank() && p1 != p2 && children.isNotEmpty()) {
                val g1 = person(p1, Gender.UNKNOWN).gender
                val g2 = person(p2, Gender.UNKNOWN).gender
                val t1 = when (g1) { Gender.MALE -> RelationType.FATHER; Gender.FEMALE -> RelationType.MOTHER; else -> RelationType.PARENT }
                val t2 = when (g2) { Gender.MALE -> RelationType.FATHER; Gender.FEMALE -> RelationType.MOTHER; else -> RelationType.PARENT }
                children.forEach { c ->
                    if (c.isNotBlank() && c != p1 && c != p2) {
                        person(c, Gender.UNKNOWN)
                        addRel(p1, c, t1)
                        addRel(p2, c, t2)
                    }
                }
                return true
            }
        }
        Regex("^(.+?)生了(.+?)$").find(s)?.let { m ->
            val p = cleanName(m.groupValues[1])
            val children = splitNames(m.groupValues[2])
            if (p.isNotBlank() && children.isNotEmpty()) {
                person(p, Gender.FEMALE)
                children.forEach { c ->
                    if (c.isNotBlank() && c != p) {
                        person(c, Gender.UNKNOWN)
                        addRel(p, c, RelationType.MOTHER)
                    }
                }
                return true
            }
        }

        // X和Y的{儿子|女儿|孩子}是Z们（多子女）
        Regex("^(.+?)和(.+?)的(儿子|女儿|孩子)(?:们)?(?:是|为|叫|名)?(.+?)$").find(s)?.let { m ->
            val p1 = cleanName(m.groupValues[1])
            val p2 = cleanName(m.groupValues[2])
            val childGender = when (m.groupValues[3]) { "儿子" -> Gender.MALE; "女儿" -> Gender.FEMALE; else -> Gender.UNKNOWN }
            val children = splitNames(m.groupValues[4])
            if (p1.isNotBlank() && p2.isNotBlank() && p1 != p2 && children.isNotEmpty()) {
                val g1 = person(p1, Gender.UNKNOWN).gender
                val g2 = person(p2, Gender.UNKNOWN).gender
                val t1 = when (g1) { Gender.MALE -> RelationType.FATHER; Gender.FEMALE -> RelationType.MOTHER; else -> RelationType.PARENT }
                val t2 = when (g2) { Gender.MALE -> RelationType.FATHER; Gender.FEMALE -> RelationType.MOTHER; else -> RelationType.PARENT }
                children.forEach { c ->
                    if (c.isNotBlank() && c != p1 && c != p2) {
                        person(c, childGender)
                        addRel(p1, c, t1)
                        addRel(p2, c, t2)
                    }
                }
                return true
            }
        }

        // X的{儿子|女儿|孩子}是Z们
        Regex("^(.+?)的(儿子|女儿|孩子)(?:们)?(?:是|为|叫|名)?(.+?)$").find(s)?.let { m ->
            val p = cleanName(m.groupValues[1])
            val childGender = when (m.groupValues[2]) { "儿子" -> Gender.MALE; "女儿" -> Gender.FEMALE; else -> Gender.UNKNOWN }
            val children = splitNames(m.groupValues[3])
            if (p.isNotBlank() && children.isNotEmpty()) {
                val pp = person(p, Gender.UNKNOWN)
                val t = when (pp.gender) { Gender.MALE -> RelationType.FATHER; Gender.FEMALE -> RelationType.MOTHER; else -> RelationType.PARENT }
                children.forEach { c ->
                    if (c.isNotBlank() && c != p) {
                        person(c, childGender)
                        addRel(p, c, t)
                    }
                }
                return true
            }
        }

        // X和Y的{称谓}是Z（如 张伟和张强的父亲是张建国）
        Regex("^(.+?)和(.+?)的(.+?)是(.+?)$").find(s)?.let { m ->
            val subs = listOf(cleanName(m.groupValues[1]), cleanName(m.groupValues[2]))
            val word = m.groupValues[3].trim()
            val obj = cleanName(m.groupValues[4])
            if (subs.all { it.isNotBlank() } && word.isNotBlank() && obj.isNotBlank()) {
                subs.forEach { sub -> applyKinship(obj, sub, word, person, addRel, addCustom) }
                return true
            }
        }

        // X是Y的{称谓}
        Regex("^(.+?)是(.+?)的(.+?)$").find(s)?.let { m ->
            val sub = cleanName(m.groupValues[1])
            val obj = cleanName(m.groupValues[2])
            val word = m.groupValues[3].trim()
            if (sub.isNotBlank() && obj.isNotBlank() && sub != obj) {
                if (applyKinship(sub, obj, word, person, addRel, addCustom)) return true
            }
        }

        // X的{称谓}是Y
        Regex("^(.+?)的(.+?)是(.+?)$").find(s)?.let { m ->
            val obj = cleanName(m.groupValues[1])
            val word = m.groupValues[2].trim()
            val sub = cleanName(m.groupValues[3])
            if (sub.isNotBlank() && obj.isNotBlank() && sub != obj) {
                if (applyKinship(sub, obj, word, person, addRel, addCustom)) return true
            }
        }

        // X和Y是{兄弟|姐妹|兄妹|姐弟|连襟|妯娌}
        Regex("^(.+?)和(.+?)是(?:亲)?(兄弟|姐妹|兄妹|姐弟|连襟|妯娌)$").find(s)?.let { m ->
            val a = cleanName(m.groupValues[1])
            val b = cleanName(m.groupValues[2])
            if (a.isNotBlank() && b.isNotBlank() && a != b) {
                when (m.groupValues[3]) {
                    "连襟" -> {
                        person(a, Gender.MALE)
                        person(b, Gender.MALE)
                        addRel(a, b, RelationType.LIANJIN)
                    }
                    "妯娌" -> {
                        person(a, Gender.FEMALE)
                        person(b, Gender.FEMALE)
                        addRel(a, b, RelationType.ZHOULI)
                    }
                    else -> {
                        val (ga, gb) = when (m.groupValues[3]) {
                            "兄弟" -> Gender.MALE to Gender.MALE
                            "姐妹" -> Gender.FEMALE to Gender.FEMALE
                            "兄妹" -> Gender.MALE to Gender.FEMALE
                            "姐弟" -> Gender.FEMALE to Gender.MALE
                            else -> Gender.UNKNOWN to Gender.UNKNOWN
                        }
                        person(a, ga)
                        person(b, gb)
                        addRel(a, b, RelationType.SIBLING)
                    }
                }
                return true
            }
        }

        // 纯名单句：只有名字列表时，把名字作为成员
        val nameList = splitNames(s)
        if (nameList.size >= 2 && nameList.all { it.length in 2..6 }) {
            nameList.forEach { person(it, Gender.UNKNOWN) }
            return true
        }

        return false
    }
}
