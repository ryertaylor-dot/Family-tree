package com.example.familytree.data

/** 三代入示例数据，便于快速体验拓扑图 */
fun sampleFamily(): FamilyData {
    fun p(id: String, name: String, gender: Gender, birth: String = "", death: String = "") =
        Person(id = id, name = name, gender = gender, birth = birth, death = death)

    val persons = listOf(
        p("g1", "张建国", Gender.MALE, "1935"),
        p("g2", "李秀英", Gender.FEMALE, "1938"),
        p("f1", "张伟", Gender.MALE, "1962"),
        p("m1", "王芳", Gender.FEMALE, "1965"),
        p("u1", "张强", Gender.MALE, "1968"),
        p("me", "张小明", Gender.MALE, "1992"),
        p("s1", "张小红", Gender.FEMALE, "1995"),
        p("sp", "刘婷婷", Gender.FEMALE, "1994"),
        p("c1", "张子轩", Gender.MALE, "2020"),
    )

    fun r(id: String, type: RelationType, from: String, to: String) = Relation(id, type, from, to)

    val relations = listOf(
        r("r1", RelationType.SPOUSE, "g1", "g2"),
        r("r2", RelationType.FATHER, "g1", "f1"),
        r("r3", RelationType.MOTHER, "g2", "f1"),
        r("r4", RelationType.FATHER, "g1", "u1"),
        r("r5", RelationType.MOTHER, "g2", "u1"),
        r("r6", RelationType.SPOUSE, "f1", "m1"),
        r("r7", RelationType.FATHER, "f1", "me"),
        r("r8", RelationType.MOTHER, "m1", "me"),
        r("r9", RelationType.FATHER, "f1", "s1"),
        r("r10", RelationType.MOTHER, "m1", "s1"),
        r("r11", RelationType.SPOUSE, "me", "sp"),
        r("r12", RelationType.FATHER, "me", "c1"),
        r("r13", RelationType.MOTHER, "sp", "c1"),
    )
    return FamilyData(persons, relations)
}
