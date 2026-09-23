# AGENTS.md

给在本仓库里工作的 AI 协作者（以及新人）的说明。目录约定和构建命令都在这里，改代码前先读一遍。

## 语言策略

- 默认用简体中文回答，除非明确要求英文。
- 代码、命令、报错、API 名称保持原文，不要翻译。

## 工程概览

| 项 | 值 |
| --- | --- |
| 平台 | Minecraft **1.20.1** / **MinecraftForge 47.4.23** |
| Java | 17（`java.toolchain.languageVersion`；本机 JDK 路径见 `gradle.properties`） |
| Gradle | 8.14.3（wrapper 指向腾讯云镜像） |
| 构建插件 | ModDevGradle Legacy `net.neoforged.moddev.legacyforge` 2.0.147（版本在 `gradle/libs.versions.toml`） |
| 内嵌库 | MixinExtras 0.4.1（`jarJar` 打进 jar，`META-INF/jarjar/`） |
| mod id | `nextcard`（2026-09-20 已从 examplemod 模板改名，作者 klze） |
| 主包 | `com.klze.nextcard` |
| 混淆 | 1.20.1 生产用 SRG 名，`build/libs` 是重混淆后的可发布 jar，`build/devlibs` 是开发版 |

## 常用命令

```bash
./gradlew build                 # 编译 + 单测 + 重混淆，产物在 build/libs
./gradlew runClient             # 开发客户端
./gradlew runServer             # 开发服务端（首次需自己在 run/eula.txt 同意 EULA）
./gradlew runData               # 数据生成 → 写入 src/generated/resources（必须提交）
./gradlew runGameTestServer     # 跑 GameTest，全过才退出码 0
./gradlew logicTest             # 纯逻辑门套件（G1/G2/G3），中文路径下也能跑（JavaExec，非 :test）
./gradlew publishMods           # 发 CurseForge/Modrinth：id 填在 gradle.properties，token 走环境变量，id 空则跳过
./gradlew clean                 # 清理构建产物
```

构建依赖已配国内镜像（见 README 第二节），`maven.neoforged.net` 在国内不可用，替换成了官方备用 CDN。

## 目录约定

```
src/main/java/com/klze/nextcard/
├── NextCard.java            # 唯一入口：只做注册/配置/网络/端分派的编排，业务代码不要写这里
├── core/                    # 引擎（零内容知识、纯逻辑、可无头测试；G2 门禁止出现内容字面量）
│   ├── card/ tag/ pool/ draw/ effect/ load/
├── sim/                     # 蒙特卡洛与参考场景（允许引用示例内容，core 不行）
├── common/                  # MC 粘合层：registry/config/network/tags/util（不要引用 net.minecraft.client.*）
│   ├── registry/            # 一个注册表一个类：ModBlocks / ModItems / ModCreativeTabs
│   ├── config/              # ModCommonConfig / ModClientConfig（ForgeConfigSpec）
│   ├── network/             # ModNetwork（SimpleChannel）+ message/ 放包类
│   ├── tags/                # 自定义 TagKey 常量
│   └── util/                # 纯逻辑工具类（可单测，禁止引用 MC 类）
├── client/                  # 仅客户端：ClientSetup / ClientPacketHandlers / render/
├── datagen/                 # 数据生成 provider（DataGenerators 是入口）
├── gametest/                # GameTest，运行需要 run/gameteststructures/<测试名>.snbt
└── mixin/                   # Mixin 类（1.20.1 依赖 refmap，见 build.gradle 的 mixin 块）
```

（规格文档不在上面这棵树里：它在仓库根的 `docs/nextCard开发文档.md`。）

### nextCard 项目速览

- **规格**：`docs/nextCard开发文档.md`（当前 v1.1）。三条铁律：内容不得约束架构 / 不允许特例 / 极致压缩。
- **四道门**：G1 删光 `data/nextcard/` 引擎照跑；G2 `core/` 无内容字面量（连注释都查）；G3 蒙特卡洛概率性质（T5 不早于第 9 抽、标签份额 < 80%、15 抽不死局）；G4 = `logicTest`（JavaExec，见下）。
- **示例内容可整体删除**（`src/main/resources/data/nextcard/`，16 张卡 + 3 标签 + 日程），删后游戏照常加载。
- 首板正式卡表由使用者提供（整合包框架型定位）；挂点词表随卡表冻结（C3）。

## 写代码时的硬性约定

1. **新增内容按注册表拆类**，主类只加一行 `ModXxx.register(modEventBus)`。方块要配 `ModItems.registerBlockItem(...)`，否则进游戏是空物品。
2. **版本号写 `gradle/libs.versions.toml`**（构建工具：插件/JUnit/MixinExtras）；**模组元数据写 `gradle.properties`**（`mod_id`/`mod_name`/`minecraft_version`/`forge_version` 等，会被注入 `mods.toml`，别挪到 catalog）。catalog 不支持 classifier，mixin 注解处理器的 `:processor` 只能字面写。
3. **客户端代码只能放 `client/`**；common 里调用客户端逻辑必须走 `DistExecutor`，事件订阅者用 `value = Dist.CLIENT`。
4. **能用 Access Transformer 就别写 Mixin**；写 Mixin 时保证 `<mod_id>.mixins.json` 里 `defaultRequire: 1`，让注入失败直接报错而不是静默失效。
5. **MixinExtras 已内嵌**（`jarJar`）：新写注入器优先用 `@WrapOperation`/`@ModifyExpressionValue` 而不是 `@Redirect`（签名安全、可多次调用原方法）；用它的注入器**必须**保留 `annotationProcessor libs.mixinextras.common`，否则 refmap 缺条目、运行期注入失败。
6. **datagen 优先**：模型、语言、战利品表、配方、标签都写 provider，不要手写 json。
7. **1.20.1 的网络是 `SimpleChannel`**（`ModNetwork`），不要照抄 1.20.2+ 教程里的 `CustomPacketPayload`/`PayloadRegistrar`。协议版本变更时必须双向校验并升版本。
8. 发布前 `./gradlew runGameTestServer` 必须全绿；发布只发 `build/libs`（重混淆版），**不要发 `build/devlibs`**。

## 不要手改的路径

- `src/generated/resources/**` —— `runData` 的产物（`.cache` 是它的记账文件，要提交、但不进 jar）。
- `build/**`、`.gradle/**`、`run/**` —— 构建与运行产物。
- 特例：`run/gameteststructures/*.snbt` 是手写的测试结构，**必须提交**（Forge 运行时从磁盘读它，CI 也要用）。

## 已知环境问题

- **工程路径含中文时单测会被跳过**：Gradle 用 UTF-8 的 argfile 传递测试工作进程的 classpath，而 JVM 启动器按系统 ANSI 解码，导致 classpath 里含中文的项失效（表现为 `ClassNotFoundException: 你自己的测试类`）。`build.gradle` 里检测到非 ASCII 路径会自动跳过 `:test` 并打印提示；从纯 ASCII 路径（如 `mklink /J C:\mcdev "...\开发\mod"`）或 CI 上运行则正常执行。
  **本项目的纯逻辑测试已改走 `./gradlew logicTest`（JavaExec 直接喂 classpath，中文路径下可用）**；`:test` 任务仅在 ASCII 路径/CI 上补充执行 JUnit 平台。
- Parchment 映射默认关闭（下载走 Google Storage，国内不通），需要时按 README 打开。
- 若依赖下载出现 `Connection reset`，先检查是不是又走了 `maven.neoforged.net`（应使用 `neoforged.forgecdn.net`）。

## 参考文档

- ModDevGradle Legacy（1.20.1 官方工具链）：https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md
- Forge 1.20.1 文档：https://docs.minecraftforge.net/en/1.20.1/
- 本仓库 README：镜像清单、改名步骤、发布说明
