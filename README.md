# 家族图谱 FamilyTree

一个**纯本地、离线运行**的安卓家谱应用。录入家庭成员与亲属关系后，应用自动推导亲属称谓，并**自动生成分支拓扑图（家谱树）**，支持 JSON / GEDCOM 导入导出。

- 界面采用 **Liquid Glass（液态玻璃）+ 悬浮式底部导航** 的现代移动端设计语言；
- 全部数据保存在应用私有目录，**不联网、不申请任何权限、不上传任何数据**。

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 成员管理 | 姓名、性别、出生/去世、备注、节点颜色、照片（最多 3 张/人）；搜索、代际排序、性别筛选、长按多选批量移动家族/删除 |
| 亲属关系 | 配偶、父亲、母亲、儿子、女儿、兄弟姐妹、连襟、妯娌、自定义称谓；自动去重、成环检测 |
| 自动推断 | 保存关系后自动补全：祖孙/叔伯姑舅姨/堂表亲/侄甥辈、配偶父母、子女配偶、继父母继子女等；称谓方向统一、性别自动校正 |
| 家族分组 | 自建多个家族，成员可属于多个家族；筛选、批量移入 |
| 拓扑图 | 三种视图：树形图（自上而下）/ 世系图（自左向右）/ 分支图（以某成员为中心）；缩放、平移、搜索定位、节点点击编辑、连线模式、背景样式切换 |
| 导入导出 | JSON 完整备份（含照片与家族分组）、GEDCOM 5.5.1（与其他家谱软件交换）、复制/粘贴 JSON |
| 文字导入 | 用自然语言描述（如「张三和李四生了张小三」）一键生成成员与关系 |
| 多语言 | 简体中文 / 繁體中文 / English，即时切换 |
| 自动版本 | 版本号按构建时间自动生成，设置页同步显示 |

## 构建运行

### 方式一：Android Studio（推荐）

1. 安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17+ 与 Android SDK）。
2. `File → Open`，选择项目根目录。
3. 等待 Gradle 同步完成（首次需联网下载依赖）。
4. 选择模拟器或真机，点击 Run ▶。

### 方式二：命令行

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

> 工程要求：compileSdk 35、minSdk 26（Android 8.0+）、JDK 17。已内置 Gradle Wrapper（8.10.2）。

## 工程结构

```
FamilyTree/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/
│   ├── libs.versions.toml          # 依赖版本目录
│   └── wrapper/                    # Gradle Wrapper
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/familytree/
        │   ├── MainActivity.kt             # 入口 + 底部导航 + 全局对话框
        │   ├── data/
        │   │   ├── Models.kt               # Person / Relation / FamilyData
        │   │   ├── FamilyRepository.kt     # 本地 JSON 读写 + 照片文件
        │   │   ├── FamilyViewModel.kt      # 状态、增删改、关系推断、导入导出
        │   │   ├── KinshipInference.kt     # 跨代/姻亲称谓推断
        │   │   ├── TextParser.kt           # 自然语言家谱解析
        │   │   ├── Gedcom.kt               # GEDCOM 5.5.1 编解码
        │   │   ├── I18n.kt                 # 应用内多语言
        │   │   └── SampleData.kt           # 三代示例数据
        │   ├── graph/
        │   │   └── TreeLayoutEngine.kt     # 家谱拓扑布局算法（核心）
        │   └── ui/
        │       ├── theme/Theme.kt          # 液态玻璃主题
        │       ├── MembersScreen.kt        # 成员列表
        │       ├── RelationsScreen.kt      # 关系列表
        │       ├── TreeScreen.kt           # 拓扑图 Canvas 渲染
        │       ├── TransferScreen.kt       # 导入 / 导出
        │       ├── SettingsScreen.kt       # 设置
        │       ├── PersonEditScreen.kt     # 成员编辑 + 关系管理
        │       ├── TextImportScreen.kt     # 文字导入
        │       └── Components.kt           # 共享组件
        └── res/                            # 图标 / 主题 / 字符串
```

## 数据格式

### JSON（完整备份）

```json
{
  "persons": [
    { "id": "g1", "name": "张建国", "gender": "MALE", "birth": "1935", "death": "", "notes": "", "colorIndex": null, "familyIds": [], "photos": [] }
  ],
  "relations": [
    { "id": "r1", "type": "SPOUSE", "fromId": "g1", "toId": "g2", "label": "" },
    { "id": "r2", "type": "FATHER", "fromId": "g1", "toId": "f1", "label": "" }
  ],
  "families": [ { "id": "fam1", "name": "张家" } ]
}
```

- `gender`：`MALE` / `FEMALE` / `UNKNOWN`
- `type`：`FATHER` / `MOTHER` / `SON` / `DAUGHTER` / `SPOUSE` / `SIBLING` / `LIANJIN` / `ZHOULI` / `CUSTOM` / `PARENT`（`FATHER/MOTHER` 方向为 `fromId 是 toId 的父母`）

### GEDCOM 5.5.1

- 导出 `INDI`（个人）与 `FAM`（父母+子女）；
- 家族分组、自定义称谓、节点颜色通过 `_FAMGRP` / `ASSO+RELA` / `_CLR` 扩展标签保存，第三方软件可安全忽略。

## 技术栈

- Kotlin 2.0、Jetpack Compose（Material 3）、kotlinx-serialization
- 自定义树布局引擎（分层 + 分支归属优化 + 视口裁剪）

## 隐私

应用完全离线运行：不联网、不申请任何权限、不上传任何数据。导入导出由系统文件选择器完成，数据由用户自己保管。

## 许可证

[MIT License](LICENSE) © Xiaoshuai
