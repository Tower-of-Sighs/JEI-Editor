# 1.21.1 NeoForge JEI 附属清单

本清单面向 `targets/neoforge-1.21.1/` 的开发运行环境。所有列出的 jar 均已放入
`targets/neoforge-1.21.1/run/mods/`，并已用该 target 的开发客户端实际启动验证：
客户端进入主菜单，JEI 完成插件加载，无 mod 加载错误。

- 目标环境：Minecraft 1.21.1 / NeoForge 21.1.238 / JEI 19.44.0.403
- 判定标准：jar 实现了 JEI 的 `mezz.jei.api.IModPlugin` 接口，即确实向 JEI 注册内容
- 入选条件：除 JEI 外不依赖其他内容模组
- 安装数量：38

`run/` 已被 `.gitignore` 忽略，这些 jar 属于本机运行环境，不进入版本库。

## 清单

### JEI 专用附属（33）

| 名称 | mod id | 版本 | 功能简介 | 下载链接 |
| --- | --- | --- | --- | --- |
| Just Enough Characters | `jecharacters` | 4.5.29 | 让 JEI 搜索框支持拼音/汉字输入，中文环境按拼音检索物品 | https://modrinth.com/mod/justenoughcharacters |
| Just Enough Effect Descriptions (JEED) | `jeed` | 1.21-2.3.4 | 为状态效果添加 JEI 信息页，显示效果说明与数值 | https://modrinth.com/mod/just-enough-effect-descriptions-jeed |
| Just Enough Breeding (JEBr) | `justenoughbreeding` | 3.3.1 | 显示生物繁殖信息：繁殖食物、幼体、驯服方式等 | https://modrinth.com/mod/justenoughbreeding |
| Just Enough Resources (JER) | `jeresources` | 1.6.0.12 | 显示矿物分布与生成高度、怪物掉落、作物与附魔等资源信息 | https://modrinth.com/mod/just-enough-resources-jer |
| Just Enough Professions (JEP) | `justenoughprofessions` | 4.0.5 | 显示村民职业与其对应工作站方块 | https://modrinth.com/mod/just-enough-professions-jep |
| More Overlays Updated | `moreoverlays` | 1.24.2-mc1.21.1-neoforge | 亮度/刷怪范围与区块边界叠加显示，以及 JEI 物品搜索叠加层 | https://modrinth.com/mod/more-overlays-updated |
| Just Enough Archaeology | `jearchaeology` | 1.21.0-1.1.5 | 考古系统与嗅探兽相关内容的 JEI 集成 | https://modrinth.com/mod/just-enough-archaeology |
| JEI Trim Hider | `jei_trim_hider` | 1.0.2-NeoForge-1.21.1 | 隐藏装饰性盔甲纹饰配方，减少 JEI 列表杂乱与卡顿 | https://modrinth.com/mod/jei-trim-hider |
| Smithing Template Viewer | `smithingtemplateviewer` | 1.21-1.0.4 | 在 JEI 中预览锻造模板套用到盔甲后的外观 | https://modrinth.com/mod/smithing-template-viewer |
| JEI / REI / EMI WorldGen | `jeiworldgen` | 1.4.5+neoforge-1.21.1 | 基于生物群系生成数据显示矿石等世界生成信息页面 | https://modrinth.com/mod/jei-worldgen |
| Universal Enchantment Info (UEI) | `uei` | 1.4.0 | 显示附魔的详细信息：可否在附魔台获得、可附物品、冲突等 | https://modrinth.com/mod/uei |
| Just Enough Serverless Recipes (JESR) | `justenoughserverlessrecipes` | 1.2.1 | 在未安装 JEI 的服务器上也能让客户端 JEI 显示原版配方 | https://modrinth.com/mod/just-enough-serverless-recipes |
| Just Enough Recipe Sharing (JERS) | `justenoughrecipesharing` | 1.21.1-1.0.2 | 把 JEI 配方以可点击文本分享到聊天栏 | https://modrinth.com/mod/just-enough-recipe-sharing |
| Just Enough Repair Materials (JERM) | `jerm` | 1.21.1-1.2 | 在 JEI 中显示物品的修复材料与修复量 | https://modrinth.com/mod/jerm |
| Block Detective | `block_detective` | v.2.1.0 | 以 tooltip 与 JEI 信息页显示方块/物品属性（硬度、抗爆、工具需求等） | https://modrinth.com/mod/block-detective |
| JEI Crafting Tree | `jeict` | 1.0.0 | 在 JEI 配方界面显示递归合成树，可逐层追溯材料来源 | https://modrinth.com/mod/jei-crafting-tree |
| JEI++-- | `jeiptweakp` | 1.1.0 | 客户端模组，联动 JEI 与 EMI 的行为并加入若干 QoL 调整 | https://modrinth.com/mod/jei++- |
| Just Enough Freaky Additions (JEFA) | `jefa` | 1.1.0 | 为 JEI 添加 7 个新的配方分类 | https://modrinth.com/mod/jefa |
| Just Enough Crafting Tree | `just_enough_crafting_tree` | 2.0.0 | 在 JEI 中查看合成树，展开材料的配方来源 | https://modrinth.com/mod/just-enough-crafting-tree |
| Sengoku EMI/JEI integration | `sengoku_compats_nf` | 2.0.0 | 为 Sengoku Jidai 提供 EMI/JEI 集成 | https://modrinth.com/mod/sengoku-emijei |
| Just Enough Recipe Book (JERB) | `jerb` | 1.0.0+1.21.1 | 把配方浏览器类模组的便利功能带入原版配方书 | https://modrinth.com/mod/jerb |
| Jei QuickCraft | `jeiquickcraft` | 1.0 | 直接从背包内即时完成 JEI 中的配方合成 | https://modrinth.com/mod/jei-quickcraft |
| JEI Bookmark Sync | `jeibookmarksync` | 1.0.0 | 把 JEI 书签上传到服务器，在多台电脑间同步 | https://modrinth.com/mod/jei-bookmark-sync |
| Jake's Build Tools: EMI/JEI/Creative Compatibility | `jbtviewer` | 1.0.0 | Jake's Build Tools 的 EMI/JEI/创造模式配方查看兼容 | https://modrinth.com/mod/jakes-build-tools-emijeicreative-compatibility |
| JEIRecipeManager | `jeirecipemanager` | 1.0.2 | 在 JEI 界面内管理配方：禁用配方、调整显示等 | https://modrinth.com/mod/jeirecipemanager |
| JackItToMe (JEI/REI/EMI) | `jackittome` | 1.0.0+1.21.1 | 从任意容器界面把 JEI 中的物品快速拉取到背包 | https://modrinth.com/mod/jackittome |
| JEI - Just Enough Comprehension (JEC) | `gatedjei` | 1.0.0 | 为 JEI 加入"随探索逐步解锁认知"的渐进机制 | https://modrinth.com/mod/comprehension-jei-addon |
| JEI Unhidden | `jei_unhidden` | 1.0.1 | 显示全部已注册物品，包括被模组隐藏或不在创造标签页的 | https://modrinth.com/mod/jei-unhidden |
| Crafting Station: J/EMI Edition Updated | `craftingstation` | 2.2.0 | 添加带 JEI 支持的合成站方块（含相邻容器合成） | https://modrinth.com/mod/crafting-station-jei-edition-updated |
| FastPipes | `fastpipes` | 1.3.9 | 物品/流体管道模组，随包提供 JEI 配方展示 | https://modrinth.com/mod/fast-pipes |
| ProgressiveStages | `progressivestages` | 3.0.6 | 分阶段解锁进度模组，带 JEI 支持 | https://modrinth.com/mod/progressivestages |
| Recipe Item Sync | `recipeitemsync` | 3.1.0 | 让数据包自定义物品与配方即时在 JEI/REI/EMI 中可见，无需硬编码 | https://modrinth.com/mod/recipe-item-sync |
| Rack It Up | `rackitup` | Neo-1.0.0-MC-1.21.1 | 工具架/储物架模组，带 JEI 集成 | https://modrinth.com/mod/rack-it-up |

### 内容模组附带 JEI 集成（5）

这些是提供了 JEI 插件的内容模组，除 JEI 外无其他硬依赖，因此可一并安装。

| 名称 | mod id | 版本 | 功能简介 | 下载链接 |
| --- | --- | --- | --- | --- |
| Horse Powered | `horsepowered` | 2.7.3 | 畜力/人力驱动的早期机械（研磨、切割、压榨、烘干、粉碎） | https://modrinth.com/mod/horse-powered |
| Chest helper | `chesthelper` | 1.0.16 | 容器与背包辅助增强，带 JEI 集成 | https://modrinth.com/mod/chest-helper |
| Better Piglin Trades | `betterpiglintrades` | 1.21.1-1.2.0 | 数据驱动的猪灵以物易物，JEI 中展示交易项 | https://modrinth.com/mod/better-piglin-trades |
| Resource Geodes & Catalysts | `createresourcegeodes` | 0.3.3 | 资源晶洞与催化剂，带 JEI 支持 | https://modrinth.com/mod/resource-geodes-catalysts |
| No Name Provided's Easy Farming | `nnp_easy_farming` | 1.2.4 | 重做锄头层级并添加农业工具的农业模组 | https://modrinth.com/mod/nnp-easy-farming |

## 因依赖冲突未安装

以下模组同样是 JEI 附属，但在当前 target 的固定版本下会导致启动失败，因此未放入 `run/mods`。
实际启动日志中的报错如下。

| 名称 | mod id | 未安装原因 |
| --- | --- | --- |
| JEI Enhancements | `jei_enhancements` | 要求 JEI `[19.50.0,)`，本 target 固定 19.44.0.403 |
| JEIOptimizer | `jeioptimizer` | 要求 JEI `[19.56.0,)`，本 target 固定 19.44.0.403 |
| Just Enough Cobblemon | `justenoughcobblemon` | 要求 `cobblemon [1.7.1,)`，未安装 Cobblemon |
| Missions JEI Compat | `missionsjei` | 要求 NeoForge `[21.1.244,)`，本 target 固定 21.1.238 |
| Enough Folders | `enoughfolders` | 运行期需要 Architectury API，但 metadata 未声明该依赖 |

若把这四个模组留在 `run/mods`，客户端会在 `Missing or unsupported mandatory dependencies`
阶段直接崩溃。修正方式是提升 JEI / NeoForge 版本，或补装缺失的前置模组。

## 需要其他内容模组的 JEI 附属

这些附属的用途就是与特定内容模组集成，单独安装会因缺少前置而无法加载。安装对应内容模组后可一并加入。

| 名称 | mod id | 依赖的内容模组 |
| --- | --- | --- |
| Just Enough Advancements (JEA) | `jea` | LibX |
| Just Enough Beacons Reforged | `just_enough_beacons` | Cerbons API |
| Just Enough Immersive Multiblocks | `jeimultiblocks` | Immersive Engineering |
| Just Enough Mekanism Multiblocks | `jei_mekanism_multiblocks` | Mekanism |
| Refined Storage - JEI Integration | `refinedstorage_jei_integration` | Refined Storage |
| Sophisticated JEI Index | `sophisticated_jei_index` | Sophisticated Core / Backpacks |
| Create JEI Compat | `createjeicompat` | Create |
| Create: Redstone Link GUI | `createredstonelinkgui` | Create |
| Create: Satisfied | `createsatisfied` | Create |
| Create: Just Filter Stamps | `filter_stamp` | Create |
| SpectrumJEI | `spectrumjei` | Spectrum（+ JEI Drawables） |
| PastelJEI | `pasteljei` | Pastel（+ JEI Drawables） |
| HalcyonJEI | `halcyonjei` | Data Essence（+ JEI Drawables） |
| SilentGearJEI | `silentgearjei` | Silent Gear、Advanced Loot Info |
| AE2 Utility | `ae2utility` | AE2 |
| AE2 QoL Client | `aeqc` | AE2、Mafglib、AE2 JEI Integration |
| AE2 Tangible Bookmarks | `ae2tb` | AE2、Myotus |
| Requesting Interface | `irequesting` | AE2 |
| Advanced Loot Info (ALI) | `ali` | Advanced Core Info |
| Advanced Worldgen Info (AWI) | `awi` | Advanced Core Info |
| Cobblemon Info for REI / JEI / EMI | `cobbledex_rei_emi_jei` | Cobblemon |
| JustEnoughAnvilCraft | `jeac` | AnvilCraft |
| KubeJS JEI Info Removal | `jeiinforemover` | KubeJS |
| QIO Sync | `qiosync` | Mekanism |
| EMI JEI Grid Fix | `emijeigridfix` | EMI |

## 检索与判定方法

清单不是凭印象列出的，过程如下，便于复核：

1. 用 Modrinth 搜索接口在 `neoforge` + `1.21.1` 两个 facet 下检索 `jei`、`just enough`、
   `recipe viewer`、`jei plugin` 等 20 个关键词，去重得到 135 个候选项目。
2. 取每个项目最新的 1.21.1 NeoForge 版本，下载 jar 到 `build/tmp/modsearch/jars/`。
3. 逐个读取 jar 内的 `META-INF/neoforge.mods.toml`，提取 `modId` 与依赖声明。
   注意 Modrinth 的依赖元数据不可靠（例如 Just Enough Resources 的 JEI 依赖就没有记录），
   因此依赖关系以 jar 自身 metadata 为准；同时兼容 `type="required"` 与 `type="REQUIRED"` 两种写法。
4. 扫描 class 常量池确认 jar 引用了 `mezz/jei`，并检查是否实现了 `mezz.jei.api.IModPlugin`。
   JEED、JEBr、Just Enough Characters 等并不声明 JEI 依赖，而是运行期探测配方查看器，
   仅靠依赖声明会漏掉，必须用接口实现来判定。
5. 按 jar 声明的必选依赖（JEI 与 JEI 侧库除外）过滤出可独立安装的集合，复制到 `run/mods/`。
6. 用 `build/tmp/modsearch/run-client.ps1` 启动开发客户端（JDK 21）验证，
   逐个消除依赖冲突，直到客户端进入主菜单且日志无 mod 加载错误。

中间产物位于 `build/tmp/modsearch/`（`staged.json` 为全部候选的元数据，
`final.json` / `report.json` 为最终选择结果），该目录已被 `.gitignore` 忽略。
