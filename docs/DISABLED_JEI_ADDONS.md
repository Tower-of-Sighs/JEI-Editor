# 被禁用的 JEI 附属

`targets/neoforge-1.21.1/run/mods/` 下有若干 JEI 附属被禁用（文件名改为 `*.jar.disabled`，
NeoForge 只加载 `.jar`，因此它们不参与启动）。

**禁用原因统一是同一个：它们会调用 JEI 的配方/物品可见性 API，与 JEI Editor 的持久化机制
直接竞争。** JEI Editor 的删除与合成配方不是写 JSON，而是调用
`IJeiRuntime#getRecipeManager()` 的 `hideRecipes` / `unhideRecipes` / `addRecipes`；
谁后执行谁生效。这些附属在相同时机做同样的操作，会把编辑器的结果覆盖掉。

禁用是可逆的：把文件名改回 `*.jar` 即可恢复。

## 禁用清单（8 个）

| 名称 | mod id | 它调用的 JEI API | 与编辑器冲突的具体表现 |
| --- | --- | --- | --- |
| JEIRecipeManager | `jeirecipemanager` | `hideRecipes`、`unhideRecipes`、`addRecipes`、`addIngredients` | 它本身就是一个"配方管理器"，功能定位与编辑器重叠；两边都会改同一份 JEI 可见性状态，互相覆写。**冲突等级最高。** |
| JEI Unhidden | `jei_unhidden` | `hideRecipes`、`unhideRecipes`、`addIngredients` | 主动把所有被隐藏的内容恢复可见，会**直接撤销编辑器的删除操作**。 |
| JEI Trim Hider | `jei_trim_hider` | `hideRecipes`、`unhideRecipes`、`addIngredients` | 隐藏纹饰配方；编辑器若编辑同类配方会被它覆盖。 |
| JEI - Just Enough Comprehension (JEC) | `gatedjei` | `hideRecipes`、`unhideRecipes`、`addIngredients` | 按探索进度动态隐藏/显示，会在编辑器写入后改回自己的可见性状态。 |
| ProgressiveStages | `progressivestages` | `hideRecipes`、`unhideRecipes`、`addRecipes`、`addIngredients` | 阶段解锁会重设配方可见性，覆盖编辑器结果。 |
| Recipe Item Sync | `recipeitemsync` | `hideRecipes`、`addRecipes`、`addIngredients` | 服务端同步时会重新注册配方，与编辑器写入的合成页冲突。 |
| KubeJS | `kubejs` | `hideRecipes`、`addIngredients` | 脚本层可任意改 JEI 可见性；同时它接管资源包与大量注册流程，是编辑器之外的第二个"事实来源"。 |
| KubeJS JEI Info Removal | `jeiinforemover` | `hideRecipes` | KubeJS 的附属，随 KubeJS 一起禁用；它专门移除 JEI 信息页，会与编辑器的 information 页编辑冲突。 |

## 保留但需要留意的

| 名称 | mod id | 说明 |
| --- | --- | --- |
| Rhino | `rhino` | KubeJS 的前置。KubeJS 禁用后它成了孤儿（不再被任何保留的 jar 依赖）。它自身不调用任何 JEI API，留着无害，因此未禁用。若要一并清掉，把它也改成 `.jar.disabled` 即可。 |

## 仍需观察的（未禁用）

下面这些也调用 `addRecipes` / `addIngredients`，但那是**注册自己的内容**（正常的 JEI 插件用法），
不是争夺已有配方的可见性，因此判断为低风险，暂时保留：

`advanced-loot-info`、`advanced-worldgen-info`、`better-piglin-trades`、`cobbledex-rei-emi-jei`、
`cobblemon`、`create`、`create-jei-compat`、`create-redstone-link-gui`、`horse-powered`、
`immersiveengineering`、`jea`、`jefa`、`jei-multiblocks`、`jei-worldgen`、`jerb`、`jerm`、
`just-enough-archaeology`、`just-enough-beacons-reforged`、`just-enough-effect-descriptions-jeed`、
`just-enough-mekanism-multiblocks`、`just-enough-professions-jep`、`just-enough-resources-jer`、
`just-enough-serverless-recipes`、`justenoughbreeding`、`mekanism`、`nnp-easy-farming`、
`rack-it-up`、`resource-geodes-catalysts`、`sengoku-emijei`、`silent-gear`、`silentgearjei`、
`smithing-template-viewer`、`sophisticated-backpacks`、`sophisticated-core`、`uei`

其中 `better-piglin-trades`、`cobbledex-rei-emi-jei` 也调用了 `hideRecipes`，
但它们是为了隐藏自己不展示的条目，若发现与编辑器互相干扰，可加入禁用名单。

## 另有一类风险：引用 JEI 内部类

下面 5 个模组引用了 JEI 的内部 GUI 类 `mezz.jei.gui.recipes.RecipesGui`，
而编辑器正是用反射改这个类（`bookmarks` 字段、`IRecipeGuiLogic`、`RecipeGuiLayouts` 等）：

`ae2utility`、`create-jei-compat`、`jeirecipemanager`、`just-enough-crafting-tree`、
`just-enough-mekanism-multiblocks`

这属于**升级风险而非当前冲突**：JEI 版本变动时，这些模组和编辑器的反射会同时踩空。
其中 `jeirecipemanager` 已在上面的禁用名单里。若日后升级 JEI，这 4 个（加上本模组自身）
需要一起回归测试。

## 已做的验证

1. 依赖检查：禁用后**没有任何保留的 jar 依赖被禁用的 mod id**，无悬空依赖。
2. 静态依赖校验（`build/tmp/modsearch/verify.py`）：68 个 jar，无缺失必需依赖。
3. 客户端实测：开发客户端启动进入主菜单，日志无 mod 加载错误、无 mod 冲突。

`run/mods/` 当前状态：**68 个启用的 jar + 8 个已禁用**（原 76 个）。

跨两份清单的分布：

- 第一批（[JEI_ADDONS_1.21.1_NEOFORGE.md](JEI_ADDONS_1.21.1_NEOFORGE.md)）中禁用 6 个：
  `jeirecipemanager`、`jei-unhidden`、`jei-trim-hider`、`comprehension-jei-addon`、
  `progressivestages`、`recipe-item-sync`
- 第二批（[JEI_ADDONS_CONTENT_MODS.md](JEI_ADDONS_CONTENT_MODS.md)）中禁用 2 个：
  `kubejs-jei-info-removal`（附属本身）与 `kubejs`（它的前置，也是 Batch 2 引入的内容模组）

## 复现与回滚

```powershell
# 重新执行禁用（幂等，已禁用的会跳过）
python build\tmp\modsearch\disable_addons.py

# 依赖影响评估（禁用前应先跑，确认没有别的模组依赖它们）
python build\tmp\modsearch\check_disable_impact.py

# 回滚单个模组
cd targets\neoforge-1.21.1\run\mods
Rename-Item 'jei-unhidden__JEI Unhidden NeoForge v1.0.1 for mc1.21.1.jar.disabled' `
            'jei-unhidden__JEI Unhidden NeoForge v1.0.1 for mc1.21.1.jar'

# 全部回滚
Get-ChildItem *.jar.disabled | Rename-Item -NewName { $_.Name -replace '\.disabled$','' }
```

## 待定问题

编辑器的删除操作与"恢复可见"类模组（`jei-unhidden`）的优先级目前是**未定义的**——
表现为后执行者赢。现在通过禁用规避了，但更彻底的做法是在编辑器侧记录"本会话主动隐藏的
配方"，并在 JEI 重建后重新应用，而不是依赖执行顺序。这是代码改动，需要另行确认。
