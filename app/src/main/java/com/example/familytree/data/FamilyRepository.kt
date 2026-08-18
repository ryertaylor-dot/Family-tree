package com.example.familytree.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 本地 JSON 文件仓库：全部数据序列化到应用私有目录，
 * 与导出/导入共用同一套 JSON 格式。
 */
class FamilyRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    fun load(): FamilyData = try {
        val raw = if (file.exists()) json.decodeFromString<FamilyData>(file.readText()) else FamilyData()
        migrate(raw)
    } catch (e: Exception) {
        // 数据损坏时退回空数据，避免崩溃
        FamilyData()
    }

    /** 旧版数据迁移：PARENT（父母）按性别转换为 FATHER/MOTHER，性别未知的保留为 PARENT（父/母）；
     *  旧版单家族 familyId 提升为多家族 familyIds。 */
    private fun migrate(data: FamilyData): FamilyData {
        var d = data
        if (d.relations.any { it.type == RelationType.PARENT }) {
            val byId = d.persons.associateBy { it.id }
            d = d.copy(
                relations = d.relations.map { r ->
                    if (r.type == RelationType.PARENT) {
                        when (byId[r.fromId]?.gender) {
                            Gender.MALE -> r.copy(type = RelationType.FATHER)
                            Gender.FEMALE -> r.copy(type = RelationType.MOTHER)
                            else -> r
                        }
                    } else r
                },
            )
        }
        if (d.persons.any { it.familyIds.isEmpty() && it.familyId.isNotBlank() }) {
            d = d.copy(
                persons = d.persons.map { p ->
                    if (p.familyIds.isEmpty() && p.familyId.isNotBlank()) {
                        p.copy(familyIds = listOf(p.familyId))
                    } else p
                },
            )
        }
        return d
    }

    fun save(data: FamilyData) {
        file.writeText(json.encodeToString(data))
    }

    fun toJson(data: FamilyData): String = json.encodeToString(data)

    fun fromJson(text: String): FamilyData = migrate(json.decodeFromString(text))

    /** 导出：数据 + 照片内容（Base64 内嵌），单文件即可完整备份 */
    fun exportWithPhotos(data: FamilyData): String {
        val photos = data.persons.flatMap { it.photos }.distinct().mapNotNull { n ->
            val f = photoFile(n)
            if (f.exists()) n to f.readBytes() else null
        }.toMap()
        return json.encodeToString(FamilyExport(data, photos))
    }

    /** 导入：优先解析带照片的新格式，兼容旧版纯数据 JSON */
    fun importWithPhotos(text: String): Pair<FamilyData, Map<String, ByteArray>> {
        return try {
            val w = json.decodeFromString<FamilyExport>(text)
            migrate(w.data) to w.photos
        } catch (e: Exception) {
            migrate(json.decodeFromString(text)) to emptyMap()
        }
    }

    // ---------- 成员照片（应用私有目录，最多 3 张/人） ----------
    private val photoDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun photoFile(name: String): File = File(photoDir, name)

    fun savePhoto(bytes: ByteArray): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        photoFile(name).writeBytes(bytes)
        return name
    }

    /** 按指定文件名写回照片（导入时还原原名） */
    fun writePhoto(name: String, bytes: ByteArray) {
        photoFile(name).writeBytes(bytes)
    }

    /** 全部照片文件（用于清理未引用的孤儿文件） */
    fun listPhotoFiles(): List<File> = photoDir.listFiles()?.toList() ?: emptyList()

    fun deletePhotoFile(name: String) {
        if (name.isNotBlank()) photoFile(name).delete()
    }

    companion object {
        private const val FILE_NAME = "family_data.json"
    }
}
