# JEI Editor

当前实现只面向 NeoForge 1.21.1；`common` 保留纯逻辑模型和测试，其他 target 目录已停用，不参与构建或发布。

默认包名与 Gradle group 为 `cc.sighs.JEIEditor`，默认 mod id 为 `jeieditor`。

## IDEA

直接打开 `targets/neoforge-1.21.1/`。IDEA 会导入该 target 与可编辑的 `../../common` 源码模块，只下载 NeoForge 1.21.1 的加载器和 Minecraft 依赖。

## Target

| Target | JDK | 构建命令 |
| --- | --- | --- |
| `neoforge-1.21.1` | JDK 21 | `targets\neoforge-1.21.1\.\gradlew.bat clean build` |

根项目默认只同步 `common`；使用 JDK 21 构建唯一实现 target：

```powershell
.\gradlew.bat '-Ptarget=neoforge-1.21.1' build
```

## 结构

- `common/`: 不依赖 Minecraft 或任意 loader 的共享 Java 代码。
- `targets/neoforge-1.21.1/`: 唯一实现 target 的入口、metadata、资源及 API 适配。

## 共享资源

将共享资源放在 `common/src/main/resources/`。构建 NeoForge 1.21.1 时，该目录会与 target 自己的 `src/main/resources/` 合并并写入最终 jar。

NeoForge metadata 保留在 target 的 `META-INF/neoforge.mods.toml`。

## 本地依赖

NeoForge target 会自动将自身 `libs/` 目录中的 `*.jar` 作为 `implementation` 依赖。将 jar 放入该目录后不需要在 `build.gradle` 中逐条声明；`*-sources.jar` 和 `*-javadoc.jar` 会被忽略。

```text
targets/neoforge-1.21.1/libs/
```

本地 jar 的传递依赖无法自动推导。若某个 jar 还依赖其他库，需要将这些库也放入同一个 `libs/` 目录，或按常规方式声明依赖。

## 发布

NeoForge 1.21.1 target 提供 `publishMods`，可手动发布产物至 CurseForge 与 Modrinth。两个平台的项目 ID 是非敏感信息，在根 `gradle.properties` 中取消注释并填写：

```properties
publish_curseforge_project_id=你的CurseForge项目ID
publish_modrinth_project_id=你的Modrinth项目ID
```

token 只从环境变量读取，不要写入仓库。PowerShell 示例：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
$env:PUBLISH_CHANGELOG = '本次版本的更新说明' # 可选

cd targets\neoforge-1.21.1
.\gradlew.bat publishMods
```

## 验证

改动页面级功能前先跑单一入口，它按顺序执行 common 单测、客户端配方页测试、声明式服务端冒烟测试，任一阶段失败即非零退出：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-verify.ps1
```

也可以单独运行两个 harness：

- [客户端配方页测试](scripts/jei-client-tests.ps1)：在开发客户端里遍历 JEI 注册的全部页面并断言建模与补丁往返。
- [声明式服务端冒烟测试](scripts/jei-declared-smoke.ps1)：起专用服务端导入声明式补丁，断言生成包 JSON 且能通过 `/reload`。

两个 harness 依赖 `targets/neoforge-1.21.1/run/mods/` 下的模组包，该包由 [scripts/jei-mod-env/](scripts/jei-mod-env/README.md) 中的工具装配。

## 版本参考

- [NeoForge 1.21.1 版本参考](docs/version-differences/README.md)
- [1.21.1 NeoForge JEI 附属清单](docs/JEI_ADDONS_1.21.1_NEOFORGE.md)
- [需要内容模组的 JEI 附属：安装说明](docs/JEI_ADDONS_CONTENT_MODS.md)
- [被禁用的 JEI 附属](docs/DISABLED_JEI_ADDONS.md)
- [JEI 页面兼容清单（TODO）](docs/JEI_PAGE_COMPAT_TODO.md)
- [单个 JEI 配方页面接入可视化编辑的工作流](docs/JEI_PAGE_COMPAT_WORKFLOW.md)
