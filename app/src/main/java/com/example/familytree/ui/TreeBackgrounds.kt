package com.example.familytree.ui

import androidx.compose.ui.graphics.Color

/** 拓扑图背景样式（用户可在拓扑图右上角调色板按钮切换） */
data class TreeBackground(
    val name: String,
    val top: Color,
    val bottom: Color,
    val grid: Color,
    val band: Color,
    val edge: Color,
    val shadowAlpha: Float,
)

val TreeBackgrounds = listOf(
    TreeBackground(
        name = "浅蓝纸张",
        top = Color(0xFFE9F1FC), bottom = Color(0xFFFDFEFF),
        grid = Color(0x332A3A55), band = Color(0x102A3A55),
        edge = Color(0xFF5F7390), shadowAlpha = 0.16f,
    ),
    TreeBackground(
        name = "纯白",
        top = Color(0xFFFFFFFF), bottom = Color(0xFFFFFFFF),
        grid = Color(0x1F90A4AE), band = Color(0x0F90A4AE),
        edge = Color(0xFF607D8B), shadowAlpha = 0.10f,
    ),
    TreeBackground(
        name = "羊皮纸",
        top = Color(0xFFF7EFDC), bottom = Color(0xFFFBF6EC),
        grid = Color(0x26B0895A), band = Color(0x14B0895A),
        edge = Color(0xFF8D6E63), shadowAlpha = 0.18f,
    ),
    TreeBackground(
        name = "薄荷绿",
        top = Color(0xFFE3F3E8), bottom = Color(0xFFF5FBF6),
        grid = Color(0x2E4CAF50), band = Color(0x164CAF50),
        edge = Color(0xFF4E7A5B), shadowAlpha = 0.14f,
    ),
    TreeBackground(
        name = "深色蓝图",
        top = Color(0xFF152436), bottom = Color(0xFF0B1322),
        grid = Color(0x40FFFFFF), band = Color(0x0DFFFFFF),
        edge = Color(0xFF8FB3E0), shadowAlpha = 0.35f,
    ),
    TreeBackground(
        name = "深灰",
        top = Color(0xFF1C212B), bottom = Color(0xFF0F1116),
        grid = Color(0x14FFFFFF), band = Color(0x0AFFFFFF),
        edge = Color(0xFFB9C5DA), shadowAlpha = 0.30f,
    ),
    TreeBackground(
        name = "粉彩",
        top = Color(0xFFFBE9F0), bottom = Color(0xFFFDF6F9),
        grid = Color(0x2ED81B60), band = Color(0x14D81B60),
        edge = Color(0xFFA95A78), shadowAlpha = 0.15f,
    ),
    TreeBackground(
        name = "液态玻璃",
        top = Color(0xFFDCE9FD), bottom = Color(0xFFF5F2FC),
        grid = Color(0x2E4A6FA8), band = Color(0x124A6FA8),
        edge = Color(0xFF6E86B0), shadowAlpha = 0.14f,
    ),
)
