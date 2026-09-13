# MinecraftForge 1.20.1 模组工程（官方 MDK + 国内镜像）· 副本 2

这是 **NeoForged 官方维护的 Forge 1.20.1 MDK**（仓库 `NeoForgeMDKs/MDK-Forge-1.20.1-ModDevGradle`），
只做了两件事：把 Gradle 和 Maven 源换成国内可直连的镜像，并把 Forge 版本升到 47.4 线的最新版。
本目录是 `forge-1.20.1-mod` 的**编号副本**（原工程已投入使用，这份用于新的模组/分支开发）。

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| MinecraftForge | **47.4.23**（1.20.1 仍在维护的 47.4 线最新版） |
| Java | **17**（本机已备好：`C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8`） |
| Gradle | 8.14.3（wrapper 从腾讯云镜像下载） |
| 构建工具链 | ModDevGradle Legacy `net.neoforged.moddev.legacyforge` 2.0.147 |

> 1.20.1 属于 legacy 版本，生产环境使用 SRG 混淆名，所以构建时会自动执行 `reobfJar` 重混淆。
> **`build/libs` 里的是可发布成品，`build/devlibs` 里的是开发用（未重混淆）版本。**

## 一、常用命令

```bash
# 打包（成品在 build/libs/examplemod-1.0.0.jar）
./gradlew build

# 启动客户端 / 服务端（服务端首次需自行在 run/eula.txt 里同意 EULA）
./gradlew runClient
./gradlew runServer

# 数据生成（输出到 src/generated/resources）
./gradlew runData

./gradlew clean
```

Windows 上可用 `gradlew.bat build`。首次构建约 5~15 分钟（要下载 MC 并反编译重编译），之后增量构建十几秒。

## 二、国内镜像配置

| 用途 | 地址 | 说明 |
| --- | --- | --- |
| Forge 构件 | `https://bmclapi2.bangbang93.com/maven/` | BMCLAPI 镜像，覆盖 `net.minecraftforge:forge`、`mcp_config`、forgeflower 反编译器 |
| Forge 官方 Maven | `https://maven.minecraftforge.net/` | 可直连，作为 BMCLAPI 的后备 |
| NeoForge 构件 | `https://neoforged.forgecdn.net/releases` | NeoForge 官方备用 CDN（构建期需要其中的 `net.neoforged.*` 工具链构件） |
| Maven 中央仓库 | `https://maven.aliyun.com/repository/public`、`https://mirrors.cloud.tencent.com/nexus/repository/maven-public/` | 阿里云 / 腾讯云 |
| Gradle 插件 | `https://maven.aliyun.com/repository/gradle-plugin` | 阿里云插件门户镜像 |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` | 见 `gradle/wrapper/gradle-wrapper.properties` |

配置位置：`settings.gradle`（插件仓库）、`build.gradle`（依赖仓库）、`gradle/wrapper/gradle-wrapper.properties`（发行版）。

**Parchment 映射默认关闭**：它的下载会被重定向到 `storage.googleapis.com`（国内不可达）。有可用代理后，把 `gradle.properties` 的 `parchment_*` 两行和 `build.gradle` 的 `parchment { }` 块取消注释即可。

## 三、相对官方 MDK 的改动

1. `gradle/wrapper/gradle-wrapper.properties`：发行版地址改为腾讯云镜像（版本 8.14.3）。
2. `settings.gradle`：新增 `pluginManagement.repositories` 镜像列表（官方文件只有 foojay 插件声明），并设 `rootProject.name`。
3. `build.gradle`：`repositories` 增加镜像仓库；插件版本 `2.0.91 → 2.0.147`；`parchment` 块注释掉。
4. `gradle.properties`：`forge_version` `47.1.3 → 47.4.23`、`forge_version_range` 改为 `[47,)`、内存 `-Xmx1G → -Xmx3G`；新增 JDK 17 路径；去掉 `org.gradle.configuration-cache=true`（避免与 MDG legacy 的配置缓存兼容性问题）。
5. 其余文件（含 `src/`、`TEMPLATE_LICENSE.txt`、`.github/workflows/build.yml`）保持官方原样。

## 四、目录结构

```
forge-1.20.1-mod-2/
├── build.gradle                    # 工具链、镜像仓库、run 配置、元数据生成
├── gradle.properties               # 版本号、模组信息、JDK17 路径
├── settings.gradle                 # 插件仓库（镜像）
├── gradlew / gradlew.bat
├── gradle/wrapper/
└── src/main/
    ├── java/com/example/examplemod/
    │   ├── ExampleMod.java         # 模组主类（示例方块/物品/物品栏）
    │   └── Config.java             # 示例配置文件
    ├── templates/                  # 元数据模板，构建时展开 ${} 占位符
    │   ├── META-INF/mods.toml
    │   └── pack.mcmeta
    └── resources/assets/examplemod/lang/   # 语言文件
```

## 五、改成你自己的模组

1. `gradle.properties`：改 `mod_id`、`mod_name`、`mod_group_id`、`mod_version`、`mod_authors`、`mod_description`。
2. 重命名包 `src/main/java/com/example/examplemod/`，并同步修改主类里的 `MODID` 常量（必须与 `mod_id` 一致）。
3. 把 `assets/examplemod` 目录名改成你的 `mod_id`。
4. 删掉或保留示例内容，开始开发。

> ⚠️ 本目录的 `mod_id` 仍是 MDK 默认的 `examplemod`，与 `forge-1.20.1-mod`、`neoforge-1.20.1-mod` 相同。
> 如果要和它们在**同一个客户端/整合包**里共存，必须先把 `mod_id` 改成不同的值，否则只会加载其中一个。

## 六、发布

`./gradlew build` 后把 **`build/libs/examplemod-1.0.0.jar`**（已做 SRG 重混淆）丢进 `mods/` 目录即可，客户端需要同 MC 版本的 Forge 1.20.1。`build/devlibs/` 里的同名 jar 只用于开发环境，不要发布。

## 七、其他

- MDG Legacy 文档：https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md
- 映射名使用 Mojang 官方映射，注意其许可：https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
- 社区文档：https://docs.neoforged.net/ ；Forge 1.20.1 专用文档：https://docs.minecraftforge.net/en/1.20.1/
