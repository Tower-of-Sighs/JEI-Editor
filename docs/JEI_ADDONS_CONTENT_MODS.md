# 需要内容模组的 JEI 附属：安装说明

本文件说明第二批 JEI 附属（需要其他内容模组的那些）的安装情况。
第一批纯附属见 [JEI_ADDONS_1.21.1_NEOFORGE.md](JEI_ADDONS_1.21.1_NEOFORGE.md)。

- 目标环境：Minecraft 1.21.1 / NeoForge 21.1.238 / JEI 19.44.0.403
- 验证方式：开发客户端实际启动，进入主菜单，日志无 mod 加载错误与 mod 冲突
- 本批共装入 38 个 jar（15 个附属 + 23 个前置）；其中 `kubejs-jei-info-removal`
  与前置 `kubejs` 因与编辑器争抢 JEI 可见性状态而**已禁用**，
  见 [DISABLED_JEI_ADDONS.md](DISABLED_JEI_ADDONS.md)
- `run/mods/` 当前共 **76** 个 jar（含 8 个已禁用）：第一批 38 个 + 本批 38 个
- `run/` 已被 `.gitignore` 忽略，不进入版本库

## 结论速览

25 个待装附属中，**15 个已装上并验证通过**，10 个被剔除
（9 个因版本要求无法满足，另 1 个 HalcyonJEI 按用户要求随 Data Essence 一并移除）。
为了让这 15 个附属能跑起来，连带安装了 **23 个前置模组**（Create、AE2、Mekanism、
Cobblemon、Immersive Engineering、KubeJS、Refined Storage、Silent Gear 等及其自身依赖）。

> 更新记录：应要求删除了 Data Essence 及其配套。HalcyonJEI 硬依赖 Data Essence（modid
> `datanessence`），Data Essence 的前置 Databank 也一并移除，因此模组数从 79 降到 76。

## 已安装的附属（15）

| 名称 | mod id | 版本 | 功能简介 | 关联内容模组 | 下载链接 |
| --- | --- | --- | --- | --- | --- |
| Advanced Loot Info (ALI) | `ali` | 1.21.1-2.3.0 | 在 JEI 中展示战利品表与村民交易的高级信息 | Advanced Core Info | https://modrinth.com/mod/advanced-loot-info |
| Advanced Worldgen Info (AWI) | `awi` | 1.21.1-1.2.0 | 在 JEI 中展示世界生成信息（矿脉、结构等） | Advanced Core Info | https://modrinth.com/mod/advanced-worldgen-info |
| AE2 Utility | `ae2utility` | 1.8.0 | 为 AE2 增加 JEI 内上传按钮、配方树视图等 | Applied Energistics 2 | https://modrinth.com/mod/ae2utility |
| Create JEI Compat | `createjeicompat` | 1.0.3 | 优化 Create 序列组装配方在 JEI 中 7 步以上的显示 | Create | https://modrinth.com/mod/create-jei-compat |
| Create: Redstone Link GUI | `createredstonelinkgui` | 1.21.1-1.9.2 | Create 红石链接的 GUI 增强 | Create | https://modrinth.com/mod/create-redstone-link-gui |
| Create: Satisfied | `createsatisfied` | 0.1.2 | Create 吞吐量可视化与 JEI 转速滑块 | Create | https://modrinth.com/mod/create-satisfied |
| Create: Just Filter Stamps | `filter_stamp` | 1.0.0 | Create 过滤器缓存与 JEI/EMI 兼容 | Create | https://modrinth.com/mod/create-just-filter-stamps |
| Requesting Interface | `irequesting` | 0.1.2 | 中键点击 JEI 物品即可发起 AE2 自动合成 | Applied Energistics 2 | https://modrinth.com/mod/irequesting |
| Just Enough Advancements (JEA) | `jea` | 21.1.1 | 在 JEI 中搜索进度（成就），读作 "yeah" | LibX | https://modrinth.com/mod/jea |
| Just Enough Immersive Multiblocks | `jeimultiblocks` | 1.21.1-1.0.6 | 为 Immersive Engineering 多方块结构提供 JEI 支持 | Immersive Engineering | https://modrinth.com/mod/jei-multiblocks |
| Just Enough Mekanism Multiblocks | `jei_mekanism_multiblocks` | 7.21 | 在 JEI 中为 Mekanism 多方块计算材料成本 | Mekanism | https://modrinth.com/mod/just-enough-mekanism-multiblocks |
| KubeJS JEI Info Removal | `jeiinforemover` | 1.0.0 | 用 KubeJS 移除 JEI 信息页 | KubeJS | https://modrinth.com/mod/kubejs-jei-info-removal |
| Refined Storage - JEI Integration | `refinedstorage_jei_integration` | 1.0.0 | Refined Storage 的官方 JEI 集成 | Refined Storage | https://modrinth.com/mod/refined-storage-jei-integration |
| SilentGearJEI | `silentgearjei` | 1.1.9 | 为 Silent Gear 添加 JEI 支持 | Silent Gear、Advanced Loot Info | https://modrinth.com/mod/silentgearjei |
| Cobblemon Info for REI / JEI / EMI | `cobbledex_rei_emi_jei` | 2.28.8+neoforge | 把 JEI 变成完整的 Cobblemon 图鉴：点击宝可梦查看刷新点、进化、掉落、招式、形态等 | Cobblemon | https://modrinth.com/mod/cobbledex-rei-emi-jei |

## 连带安装的前置模组（23）

这些不是 JEI 附属，是为了让上面的附属能加载而装的内容模组。

| 名称 | mod id | 版本 | 用途 |
| --- | --- | --- | --- |
| Applied Energistics 2 | `ae2` | 19.2.18 | AE2 Utility、Requesting Interface 的前置 |
| GuideME | `guideme` | 21.1.19 | AE2 的文档前置 |
| Mafglib | `mafglib` | 0.4.3+mc1.21.1 | AE2 QoL Client 前置（该附属已剔除，库保留） |
| Myotus Lib | `myotus` | 1.21.1-19.1.1 | AE2 附属库 |
| Advanced Core Info | `aci` | 1.21.1-1.3.0 | ALI / AWI 的前置 |
| Create | `create` | 6.0.10+mc1.21.1 | 四个 Create 附属的前置，内置 Flywheel 与 Ponder |
| Immersive Engineering | `immersiveengineering` | 12.4.2-194 | JEI Immersive Multiblocks 前置 |
| Mekanism | `mekanism` | 10.7.19.85 | JEI Mekanism Multiblocks 前置 |
| KubeJS | `kubejs` | 2101.7.2-build.377 | KubeJS JEI Info Removal 前置 |
| Rhino | `rhino` | 2101.2.7-build.85 | KubeJS 前置 |
| Refined Storage | `refinedstorage` | 2.0.9 | Refined Storage JEI Integration 前置 |
| Silent Gear | `silentgear` | 4.2.1.1 | SilentGearJEI 前置 |
| Silent Lib | `silentlib` | 1.21.1-neoforge-10.6.0 | Silent Gear 前置 |
| Cobblemon | `cobblemon` | 1.8.1 | Cobbledex 前置 |
| Kotlin for Forge | `kotlinforforge` | 5.12.0 | Cobbledex 前置 |
| LibX | `libx` | 1.21.1-6.0.9 | JEA 前置 |
| Curios API | `curios` | 9.5.1+1.21.1 | Spectrum 前置（该附属已剔除，库保留） |
| Modonomicon | `modonomicon` | 1.21.1-neoforge-1.120.7 | Spectrum 前置（已剔除，库保留） |
| Revelationary | `revelationary` | 1.5.2+1.21.1 | Spectrum 前置，已剔除 |
| Cerbons API | `cerbons_api` | 1.3.0 | Just Enough Beacons Reforged 前置 |
| Anvil Lib | `anvillib` | 2.0.0+snapshot.546 | AnvilCraft 前置（已剔除，库保留） |
| AnvilCraft | `anvilcraft` | 1.21.1-1.6.0+pre-release.9 | 已剔除，见下 |
| EMI | `emi` | 1.1.24+1.21.1+neoforge | 仅作为 `emi-jei-grid-fix` 的前置被拉入，最终随该附属一并移除 |

## 未安装的附属（10）及原因

| 名称 | mod id | 未安装原因 |
| --- | --- | --- |
| HalcyonJEI | `halcyonjei` | 应要求删除其前置 Data Essence，故一并移除（依赖链见下方说明） |
| Sophisticated JEI Index | `sophisticated_jei_index` | 要求 JEI ≥19.53.0.426，当前 19.44.0.403 |
| AE2 Tangible Bookmarks | `ae2tb` | 要求 JEI ≥19.55.0.432，当前 19.44.0.403 |
| QIO Sync | `qiosync` | 要求 NeoForge ≥21.1.248，当前 21.1.238 |
| EMI JEI Grid Fix | `emijeigridfix` | 要求 NeoForge ≥21.1.242，当前 21.1.238 |
| SpectrumJEI | `spectrumjei` | 其前置 Spectrum 要求 NeoForge ≥21.1.249 |
| AE2 QoL Client | `aeqc` | 需要 `ae2jeiintegration`；Modrinth 上找不到该 1.21.1 NeoForge 版本 |
| JustEnoughAnvilCraft | `jeac` | 其前置 AnvilCraft 声明 Minecraft 上界 `[1.21,1.21.1)`，排除了 1.21.1 |
| PastelJEI | `pasteljei` | 按你的要求跳过下载前置 Pastel（91 MB） |
| JEIModelBridge | `jeimodelbridge` | 需要 Forgified Fabric API，而后者要求 NeoForge ≥21.1.248 |

### 删除 Data Essence 的连带影响

Data Essence 不是 JEI 附属，是 HalcyonJEI 的前置内容模组。依赖链如下：

```text
halcyonjei  --required-->  datanessence (Data Essence 0.3.6)
datanessence --required-->  databank (Databank)
```

因此删除 Data Essence 时一并移除了三个 jar：

| jar | 角色 | 处理原因 |
| --- | --- | --- |
| `data-essence-03__Halcyon-v0.3.6+tides-of-phenua.jar` | Data Essence 本体（modid `datanessence`） | 按你的要求删除 |
| `databank__databank-1.3.1.jar` | Data Essence 的硬性前置 | 仅服务于 Data Essence，独立存在无意义 |
| `halcyonjei__HalcyonJEI-21.1.0.jar` | JEI 附属，硬依赖 `datanessence` | 失去前置后无法加载，留在 `run/mods` 会导致启动崩溃 |

已确认没有任何其他模组依赖 `databank`、`datanessence` 或 `halcyon`，删除后依赖校验与
客户端启动均无报错。若日后想装回，三个 jar 一起放回即可。

## 过程中的几个判断

以下都是实测发现，不是推测，记录下来避免以后重复踩。

1. **`enforce_` 之外还有一条隐藏规则：optional 依赖的版本区间也会被强制校验。**
   `ae2tb` 把 JEI 声明为 `optional` 且写 `versionRange="[19.55.0.432,)"`，
   静态依赖检查（只看 required）完全不会报错，但启动到 pre-load 阶段时 NeoForge 直接 FATAL：
   `Mod ae2tb only supports jei 19.55.0.432 or above`。这类问题只能靠真实启动日志暴露。

2. **Modrinth 的 modid 与 metadata 有两处不一致，需要按 jar 内的实际 id 修正**：
   Data Essence 新版把 modid 从 `datanessence` 改成了 `halcyon`，而 HalcyonJEI 仍要求 `datanessence`。
   所以当初装的是 **0.3.6**（该版本 modid 仍为 `datanessence`），而不是最新的 0.4.3；
   HalcyonJEI 声明的区间是 `[0.3.5,)`，但它按 modid 匹配，装 0.4.x 会找不到依赖。
   这也是后来删除 Data Essence 时必须连带删除 HalcyonJEI 的原因。
   Kotlin for Forge、Forgified Fabric API 也存在 slug 与 modid 不一致的情况。

3. **Create 把 Flywheel 和 Ponder 打包在自己的 jar 内**（jar-in-jar），
   不需要单独下载；同理 SpectrumJEI、HalcyonJEI 内嵌 JEIDrawables。
   所以依赖校验必须递归读取嵌套 jar，否则会误报一堆"缺失前置"。

4. **EMI 与 JEI 是对立关系，不应作为 JEI 附属的前置自动拉入。**
   `emi-jei-grid-fix` 需要 EMI，但多个附属（HalcyonJEI、SpectrumJEI、PastelJEI）
   在 metadata 里把 EMI 标为 `discouraged`，装上会弹出"发现模组冲突"确认屏。
   最终 EMI 随 `emi-jei-grid-fix` 一起移除。

5. **版本区间求值要处理开闭区间与上界。** 例如 AnvilCraft 写的是 `[1.21,1.21.1)`，
   上界开区间排除 1.21.1，这在 1.21.1 上必然失败（应属上游笔误），只能剔除。

## 若要补齐这 9 个附属

需要同时提升两处版本，二者相互牵制：

1. **NeoForge 21.1.238 → 21.1.248+**（`spectrum` 要求 ≥21.1.249，取 21.1.249 或更高更稳妥）。
   项目当前固定 21.1.238，见 `targets/neoforge-1.21.1/gradle.properties` 的
   `neoforge_121_version`；本地依赖镜像 `.maven-mirror/net/neoforged/neoforge/` 下也只有 21.1.238，
   升级需同步补镜像或恢复 Maven 下载。
2. **JEI 19.44.0.403 → 19.55.0.432+**（`ae2tb` 要求 ≥19.55.0.432，最新为 19.57.0.450）。
   同时要更新 `targets/neoforge-1.21.1/libs/` 下的 jei jar 与 `jei_version` 属性。

改完这两处后，第一批里因 JEI 版本被剔除的 `jei-enhancements`、`jeioptimizer`，
以及第二批里的 `sophisticated-jei-index`、`ae2tb`、`qiosync`、`emijeigridfix`、
`spectrumjei` 都可以装回。这属于改动项目构建配置和发布产物的版本范围，需要另行确认。

## 复现步骤

```powershell
# 1. 解析依赖树（会下载附属与全部前置到 build/tmp/modsearch/jars/）
python build\tmp\modsearch\resolve.py
python build\tmp\modsearch\resolve_more.py
python build\tmp\modsearch\resolve_more2.py

# 2. 按当前 NeoForge / JEI 版本剔除装不上的，并装配到 run/mods
python build\tmp\modsearch\prune.py

# 3. 递归校验依赖（能识别 jar-in-jar）
python build\tmp\modsearch\verify.py

# 4. 实际启动验证（JDK 21；需要时脚本会按键越过冲突警告屏）
powershell -NoProfile -ExecutionPolicy Bypass -File build\tmp\modsearch\run-client.ps1 -TimeoutSeconds 1500
```

中间产物：`build/tmp/modsearch/`（`staged.json` 为全部候选元数据，`tree.json` 为依赖树，
`prune.json` 为剔除记录与最终集合，`addon_report.json` 为附属状态）。
该目录已被 `.gitignore` 忽略。
