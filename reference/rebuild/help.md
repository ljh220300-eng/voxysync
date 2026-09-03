# MapSyncer Rebuild Help

这是 MapSyncer Rebuild 的项目信息、文档和帮助入口页。  
如果你只是想快速安装和使用，请优先查看 [README.md](README.md)；如果你需要确认许可证，请查看 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。

## 项目信息

| 项目 | 内容 |
| --- | --- |
| 项目名称 | MapSyncer Rebuild for Xaero World Map |
| 当前版本 | `mapsyncer-rebuild-26.1.2-0.1` |
| Mod ID | `mapsyncer` |
| Minecraft / Fabric | `26.1.2` |
| Fabric Loader | `0.19.2` |
| Fabric API | `0.150.0+26.1.2` |
| Java | `25` |
| 当前 Rebuild 制作 | `ShanHe_YF` |
| 原始项目 | [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) |
| 许可证 | GNU GPLv3, `GPL-3.0-only` |

MapSyncer Rebuild 是一个 Fabric 双端 mod，用于把服务端已生成或已探索的地图区域同步到客户端 Xaero World Map，并提供公共路标、半径同步、增量缓存生成、Voxy 同步和客户端 GUI。

## 文档入口

| 文档 | 说明 |
| --- | --- |
| [README.md](README.md) | 项目主页，包含安装、命令、配置、构建和 FAQ |
| [LICENSE](LICENSE) | GNU GPLv3 官方协议全文 |
| [NOTICE](NOTICE) | 项目归属、改写来源和当前 Rebuild 作者说明 |
| `gradle.properties` | 当前 Minecraft、Fabric、Mod 版本配置 |
| `src/main/resources/fabric.mod.json` | Fabric mod 元数据 |

## 快速安装

### 服务端

把以下内容放入服务端 `mods/` 目录：

- Fabric Loader
- Fabric API
- `mapsyncer-rebuild-26.1.2-0.1.jar`

服务端不需要安装 Xaero's World Map。

### 客户端

把以下内容放入客户端 `mods/` 目录：

- Fabric Loader
- Fabric API
- Xaero's World Map
- `mapsyncer-rebuild-26.1.2-0.1.jar`

如果需要 Voxy 同步，请同时安装并启用 Voxy。公共路标功能需要客户端具备对应的 Xaero 路标环境。

## 常用命令

### 玩家命令

```text
/mapsyncer
/mapsyncer help
/mapsyncer gui
/mapsyncergui
/mapsyncer sync
/mapsyncer sync radius <blocks>
/mapsyncer sync all
/mapsyncer sync <dimension>
```

常用示例：

```text
/mapsyncer sync
/mapsyncer sync radius 1000
/mapsyncer sync all
/mapsyncer sync minecraft:the_nether
```

### OP 命令

```text
/mapsyncer generate
/mapsyncer generate <dimension>
/mapsyncer generate <dimension> force
/mapsyncer generate <dimension> <x> <z>
/mapsyncer status
/mapsyncer incremental run
/mapsyncer incremental off
/mapsyncer incremental tick <interval>
/mapsyncer incremental scheduled <hour> <minute>
```

首次开服建议 OP 先执行：

```text
/mapsyncer generate
```

生成基础缓存后，再让玩家通过 GUI 或 `/mapsyncer sync` 同步地图。

## 配置文件

| 文件 | 作用 |
| --- | --- |
| `config/mapsyncer.json` | 服务端配置，控制生成、同步、限速、半径同步、增量扫描等 |
| `config/mapsyncer-client.json` | 客户端配置，控制自动同步、HUD、聊天提示等 |
| `config/mapsyncer-public-waypoints.json` | 公共路标配置 |

配置文件会在首次启动后自动生成。修改配置后，建议重启服务端，或通过 GUI/命令保存相关设置。

## 常见问题

### 为什么客户端里会出现多个地图？

旧客户端可能保留了 `mw$map`。MapSyncer Rebuild 会把普通同步固定写入 `mw$default`，并在后台迁移旧数据，避免继续创建重复地图。

### 公共路标会覆盖私人路标吗？

不会。MapSyncer Rebuild 只替换公共组，默认公共组名是 `ServerPublic`。私人路标和其他组不会被修改。

### 没装 Voxy 能用吗？

可以。Voxy 同步是可选功能；没有 Voxy 时，普通 Xaero 地图同步仍然正常。

### 同步后地图没有马上刷新怎么办？

通常等待 Xaero 自动批量重载即可。如果仍然没有显示，可以重新打开 Xaero 地图，或者重新进入服务器。

### 构建需要什么环境？

需要 Java 25。Windows 下可以使用：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --stacktrace
```

构建产物位于：

```text
build/libs/
```

## 获取帮助

反馈问题时建议提供：

- Mod 版本，例如 `mapsyncer-rebuild-26.1.2-0.1`
- Minecraft / Fabric 版本
- Fabric Loader 与 Fabric API 版本
- 客户端和服务端是否都安装了 MapSyncer Rebuild
- 是否安装 Xaero's World Map、Voxy 或其他地图相关 mod
- 完整的报错日志或崩溃报告
- 问题复现步骤

如果是在 GitHub 仓库反馈，请优先使用 Issues，并附上日志和复现步骤。这样更容易判断是安装问题、配置问题、地图缓存问题，还是兼容性问题。

## 许可证说明

MapSyncer Rebuild 当前版本由 **ShanHe_YF** 以 GNU GPLv3 发布，SPDX 标识为 `GPL-3.0-only`。  
完整协议正文请查看 [LICENSE](LICENSE)，项目归属与改写说明请查看 [NOTICE](NOTICE)。
