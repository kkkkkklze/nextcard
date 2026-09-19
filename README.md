# nextCard（MinecraftForge 1.20.1 / 作者 klze）

抽卡构筑框架型 mod（适配整合包）：从世界中的抽卡方块获得卡片，以方向（A）/方式（B）/质变（C）三类卡构筑体系。
**规格与拍板记录见 `docs/nextCard开发文档.md`（v1.0 冻结）**；本 README 保留模板的工程说明，工程实践部分依然适用。

> 本工程由 forge-1.20.1-mod-2 工程化模板派生（模板原始说明见下方各节；`六、改成你自己的模组` 已于 2026-09-20 执行完毕：mod_id=nextcard、包 com.klze.nextcard、作者 klze）。

以 **NeoForged 官方维护的 Forge 1.20.1 MDK**（`NeoForgeMDKs/MDK-Forge-1.20.1-ModDevGradle`）为基底，
配好国内镜像，并按 591 个开源 mod 仓库的生态调研补齐了工程化骨架（datagen / mixin / 网络 / 配置 /
测试 / CI / AI 协作说明）。

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| MinecraftForge | **47.4.23**（1.20.1 仍在维护的 47.4 线最新版） |
| Java | **17**（本机已备好：`C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8`） |
| Gradle | 8.14.3（wrapper 从腾讯云镜像下载） |
| 构建工具链 | ModDevGradle Legacy `net.neoforged.moddev.legacyforge` 2.0.147 |

> 1.20.1 属于 legacy 版本，生产环境使用 SRG 混淆名，构建时会自动执行 `reobfJar`。
> **`build/libs` 是可发布成品，`build/devlibs` 是开发版。**

## 一、常用命令

```bash
./gradlew build                 # 编译 + 单测 + 重混淆 → build/libs/examplemod-1.0.0.jar
./gradlew runClient             # 开发客户端
./gradlew runServer             # 开发服务端（首次需自行在 run/eula.txt 同意 EULA）
./gradlew runData               # 数据生成 → src/generated/resources（生成物要提交）
./gradlew runGameTestServer     # GameTest，全过才退出码 0
./gradlew publishMods           # 发布到 CurseForge/Modrinth（需先填 id 与 token，见第七节）
./gradlew clean
```

Windows 用 `gradlew.bat`。首次构建 5~15 分钟（下载 MC + 反编译重编译），之后增量构建十几秒。

## 二、国内镜像配置

| 用途 | 地址 | 说明 |
| --- | --- | --- |
| Forge 构件 | `https://bmclapi2.bangbang93.com/maven/` | BMCLAPI 镜像：`net.minecraftforge:forge`、`mcp_config`、forgeflower |
| Forge 官方 Maven | `https://maven.minecraftforge.net/` | 可直连，作为 BMCLAPI 的后备 |
| NeoForge 构件 | `https://neoforged.forgecdn.net/releases` | **maven.neoforged.net 在国内大文件必被重置**，官方备用 CDN 是完整镜像 |
| 中央仓库 | 阿里云 `maven.aliyun.com/repository/public`、腾讯云 `mirrors.cloud.tencent.com` | |
| Gradle 插件 | `https://maven.aliyun.com/repository/gradle-plugin` | |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` | 见 `gradle/wrapper/gradle-wrapper.properties` |

配置位置：`settings.gradle`（插件仓库）、`build.gradle`（依赖仓库）、wrapper properties（发行版）。

**Parchment 映射默认关闭**：下载会 302 到 `storage.googleapis.com`（国内不可达）。有代理后把
`gradle.properties` 的 `parchment_*` 两行与 `build.gradle` 的 `parchment { }` 取消注释即可。

## 三、目录结构

```
forge-1.20.1-mod-3/
├── AGENTS.md                     # 给 AI 协作者/新人的说明（目录约定、硬性规则、已知问题）
├── build.gradle                  # 工具链、镜像仓库、run 配置、mixin、datagen、测试
├── gradle.properties             # 模组元数据与版本号（会被注入 mods.toml）、JDK17 路径
├── gradle/libs.versions.toml      # 构建工具版本（MDG 插件、发布插件、JUnit、MixinExtras）
├── LICENSE                       # 与 mods.toml 的 license 一致（默认 All Rights Reserved）
├── .github/workflows/build.yml   # CI：build + runGameTestServer + 上传 jar
├── run/gameteststructures/       # GameTest 结构（手写 SNBT，必须提交）
└── src/
    ├── main/java/com/example/examplemod/
    │   ├── ExampleMod.java       # 入口，只做编排（≤40 行）
    │   ├── common/               # 两端逻辑：registry / config / network / tags / util
    │   ├── client/               # 仅客户端：ClientSetup / ClientPacketHandlers / render
    │   ├── datagen/              # 数据生成 provider（DataGenerators 为入口）
    │   ├── gametest/             # GameTest
    │   └── mixin/                # Mixin 示例（PlayerTickMixin）
    ├── main/resources/           # 手写资源：mixins.json、accesstransformer 等
    ├── main/templates/           # mods.toml / pack.mcmeta 模板（构建时展开占位符）
    ├── generated/resources/      # runData 生成物（提交，`.cache` 不进 jar）
    └── test/java/                # JUnit 纯逻辑单测
```

## 四、已内置的工程实践（以及为什么）

1. **datagen 全量接管资源**：方块状态、方块/物品模型、中英语言、战利品表、合成/熔炼配方、方块与物品标签、
   以及进原版 `mineable/pickaxe` 标签 —— 全部由 `runData` 生成。生态调研里只有 15% 的仓库做 datagen，
   而它是内容变更成本最低的一项。示例方块借用原版材质，开箱就能正常渲染。
2. **Mixin 已启用并生成 refmap**：1.20.1 的 mixin 必须靠 refmap 把官方映射名映射到运行期 SRG 名，
   且 `<mod_id>.mixins.json` 里不写 `refmap` 字段（由 Gradle 注入）、`defaultRequire: 1` 保证注入失败直接报错。
   **注意**：mixin 需要 `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'`，删掉它构建会在 `reobfJar`
   阶段报找不到 `build/mixin/<mod_id>.refmap.json.mappings.tsrg`。
3. **网络层用 1.20.1 的 `SimpleChannel`**：不是 1.20.2+ 的 `CustomPacketPayload`/`PayloadRegistrar`。
   示例含 S2C 包 + 登录时下发 + 客户端 `DistExecutor` 处理，并在注释里写清了四个 1.20.1 特有的坑
   （协议版本双向校验、包 id 只追加、`consumerNetworkThread` 不切主线程、S2C 引用客户端类会崩专用服、字符串/集合要限长）。
4. **注册类拆分**：`ModBlocks` / `ModItems` / `ModCreativeTabs` 各自一个 `DeferredRegister`，主类只调用 `register(bus)`；
   `registerBlockItem()` 消掉了"方块+物品"的重复样板（漏了 BlockItem 会导致空物品报错）。
5. **配置分类型拆文件**：`ModCommonConfig` / `ModClientConfig`，静态字段缓存 + `ModConfigEvent` 刷新。
   1.20.1 没有内置配置界面，需要的话自行接 Cloth/Configured —— 生态调研里 1.20.1 主流是原生 `ModConfigSpec`。
6. **客户端隔离两件套**：`client/` 包 + `@Mod.EventBusSubscriber(value = Dist.CLIENT)`，common 调客户端逻辑走 `DistExecutor`。
7. **测试骨架**：JUnit 纯逻辑单测（`src/test/java`）+ GameTest（`src/main/java/.../gametest`，结构文件在 `run/gameteststructures/`）。
   生态里真正有测试的 1.20.1 仓库不到 8%，这是最容易拉开差距的一项。
8. **CI**：GitHub Actions 跑 `build` + `runGameTestServer` 并上传 jar。
9. **AGENTS.md**：写清目录约定、硬性规则、"不要手改的路径"和已知环境问题，方便 AI 协作。
10. **版本目录**：`gradle/libs.versions.toml` 统一管理构建工具版本（MDG/发布插件/JUnit/MixinExtras）。
    分工是：**工具版本进 catalog，模组元数据留在 gradle.properties**（后者要注入 `mods.toml`）。
    注意 catalog 不支持 classifier，`org.spongepowered:mixin:0.8.5:processor` 仍在 build.gradle 里字面写。
11. **MixinExtras 已内嵌**：`jarJar` 把 `mixinextras-forge` 打进 jar（`META-INF/jarjar/`），
    这样多个 mod 用 MixinExtras 时共享同一版本而不是各自塞一份；示例用 `@WrapOperation` 包了
    `Player#tick` 里的 `isSpectator()` 调用（返回原值，行为不变），演示这个"`@Redirect` 的签名安全替代"。
    **注意**：用 MixinExtras 的注入器必须挂 `annotationProcessor libs.mixinextras.common`，
    否则 refmap 里不会有对应条目（本项目已配好，refmap 里能看到 `isSpectator()Z → m_5833_()Z`）。
12. **发布流程**：`gradlew publishMods` 一把发 CurseForge + Modrinth（`me.modmuss50.mod-publish-plugin`）。
    project id 填在 `gradle.properties`，access token 走环境变量，**id 留空即跳过该平台**，
    所以 `gradlew build` 永远不需要 token。发布的文件固定取 `jar` 任务产物（即 `build/libs` 里重混淆后的那份）。

## 五、已知环境问题：工程路径含中文时单测会被跳过

Gradle 把测试工作进程的 classpath 写进一个 **UTF-8 的 argfile**，而 JVM 启动器按系统 ANSI 解码它。
工程路径里有中文（`...\Documents\开发\mod\...`）时，classpath 项会被解码坏，测试全部报
`ClassNotFoundException: 你自己的测试类`（编译其实是成功的）。这是 JDK/Windows 的既有行为，构建侧无法修，
所以 `build.gradle` 检测到非 ASCII 路径时会跳过 `:test` 并打印提示：

```
:test skipped - project path contains non-ASCII characters (...)
```

本项目的纯逻辑测试走 `./gradlew logicTest`（JavaExec 直接喂 classpath，中文路径下可用，`check` 已挂接）。
想让 JUnit 平台（`:test`）也跑起来，用纯 ASCII 路径访问工程即可，例如建一个 junction：

```
mklink /J C:\mcdev "C:\Users\Administrator\Documents\开发\mod"
```

然后在 `C:\mcdev\forge-1.20.1-mod-3` 里构建（CI 上路径是 ASCII，测试正常执行）。

## 六、改成你自己的模组（已执行 ✓ 2026-09-20）

已执行：`mod_id=nextcard`、`mod_name=NextCard`、`mod_authors=klze`、`mod_group_id=com.klze.nextcard`、
包 `com.klze.nextcard`、`nextcard.mixins.json`；`mod_version=0.1.0`；LICENSE 维持 All Rights Reserved。
以下步骤留给历史参考：

1. `gradle.properties`：改 `mod_id`、`mod_name`、`mod_group_id`、`mod_version`、`mod_authors`、`mod_description`。
2. 重命名包，同步改主类 `MODID` 常量（必须与 `mod_id` 一致）。
3. `<mod_id>.mixins.json` 改名（`build.gradle` 里用的是 `${mod_id}`，会自动跟随）。
4. `datagen` 里所有 `modLoc(...)`、语言键、标签会自动跟随 `mod_id`，示例内容按需删除。
5. `LICENSE` 与 `gradle.properties` 的 `mod_license` 改成你选的许可协议。

## 七、发布

`./gradlew build` 后把 **`build/libs/examplemod-1.0.0.jar`**（已 SRG 重混淆，且内嵌了 MixinExtras）丢进
`mods/` 目录即可，客户端需要同 MC 版本的 Forge 1.20.1。`build/devlibs/` 里的同名 jar 只用于开发环境，不要发布。

发到平台上：

```properties
# gradle.properties
curseforge_project_id=123456
modrinth_project_id=AbCdEfGh
```

```bash
export CURSEFORGE_TOKEN=...      # 平台后台生成的 API token
export MODRINTH_TOKEN=...
export CHANGELOG="..."           # 可选，默认占位文案
./gradlew publishMods
```

只填一个 id 就只发一个平台；两个都留空时 `publishMods` 什么也不做（也不会报错）。

## 八、参考

- MDG Legacy 文档：https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md
- Forge 1.20.1 文档：https://docs.minecraftforge.net/en/1.20.1/
- 映射许可：https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
