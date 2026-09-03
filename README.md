# VoxySync（Minecraft 1.20.1 / Fabric）

把服务器**预生成世界的原始区域文件（r.x.z.mca）**同步给客户端，客户端暂存后反射调用
[Voxy](https://github.com/MCRcortex/voxy) 的官方导入管线（WorldImporter.importRegionDirectoryAsync），
把世界转为超远渲染 LoD —— 玩家进服即可看清远处地形，无需自己探索生成。

本 mod 为**双端合一**（服务端必装、客户端可选）：装 Voxy 的客户端自动同步，未装 Voxy 的客户端
优雅降级（仅提示一次，不影响游戏）。

> 许可：GPL-3.0-only。改编自 [Jiemoy/MapSyncer-rebuild](https://github.com/Jiemoy/MapSyncer-rebuild)
> （GPL-3.0），见 NOTICE；请保留 LICENSE/NOTICE 并附源码链接。

## 版本
- Minecraft 1.20.1 · Fabric Loader ≥0.16 · Fabric API ≥0.92.12+1.20.1 · Java 17+（开发环境：JDK 21 + Gradle 9.4.1 + Loom 1.14.6，官方 Mojang 映射）
- 客户端需另装 Voxy 1.20.1 移植版（[1.20.1移植版]voxy-0.2.7-alpha.jar）

## 配置 config/voxysync.json（首次加载自动生成）
```json
{
  "enableVoxySync": false,       // 服务端开关
  "syncMode": "radius",          // "radius" | "all"
  "radiusBlocks": 2000,          // radius 模式半径（方块）
  "speedLimitKBps": 1024,        // 每玩家限速 KB/s（≤0 不限速）
  "maxPacketSize": 262144,       // 单分片字节数（≤1MB，S2C 协议上限）
  "autoStartOnJoin": true        // 客户端进服自动同步当前维度
}
```

## 模式
- **radius（默认防泄露模式）**：只发送玩家周围 radiusBlocks 方块内的区域（按区域 Chebyshev 距离圈选，
  由近及远）。玩家可解析 MCA 的范围被限制在自己周围，风险可控。
- **all（全图模式）**：发送当前维度**全部**区域文件。⚠️ MCA 数据可暴露箱子内容、实体、矿脉、
  隐藏结构 —— 属于透视级泄露；仅建议在完全信任的服务器开启（服务启动与 /voxysync mode all
  都会打印红色警告）。**本服当前需要全图同步，部署时会将配置设为 syncMode: "all"。**

## 命令（OP）
```
/voxysync status              // 当前配置与进行中的同步
/voxysync enable|disable
/voxysync mode radius|all
/voxysync radius <blocks>
/voxysync sync [radius|all]   // 让自己立即重新同步（可临时指定模式）
/voxysync devtest [mode] [radius]  // 只读诊断：区域收集/增量/编解码（无客户端验证用）
```

## 协议（1.20.1 ResourceLocation + FriendlyByteBuf，通道前缀 voxysync:）
capability_request → capability；sync_request（分块，见下）→ sync_start → region_part* → sync_progress* → sync_complete；request_sync（OP 触发）。

**注意 1.20.1 的 C2S 自定义载荷上限为 32767 字节**，因此 sync_request 的客户端元数据按
300 条/块分块发送，服务端聚合后再开始同步；S2C 上限 1MB，region_part 默认 256KB。

## 增量
客户端在 .minecraft/voxysync/voxy-sync-cache.json 缓存（维度/文件名 → 时间戳+大小）；
下次进服上报，服务器跳过未变化文件。服务端区域被修改（mtime/大小变化）会自动重发。

## 安全与行为
- 只同步**玩家当前所在维度**（维度不匹配直接拒绝）。
- 单玩家同时一个同步线程；全局最多 2 个并发同步；每玩家 1MB/s 限速。
- 客户端暂存目录 .minecraft/voxysync/staging/<维度>/<syncId>/region/，失败自动清理残留。
- 若开启全图模式，请确保服务器只在受信环境运行。

## 构建
```
./gradlew build   # 产物 build/libs/voxysync-0.1.0.jar（Loom remap 输出，可直接放 mods/）
```

## 测试
- 客户端纯逻辑单测：`./gradlew test`（区域拼装乱序/重复/越界、缓存读写、元数据分块，11 项）
- 服务端链路回归：`bash test/verify-server.sh`（构建 → /tmp 复刻实例 → RCON devtest 诊断，11 项断言；脚本只按 `-Xmx1536M` 精确匹配测试实例）
- 客户端导入真机：需要真实客户端或 Xvfb 软渲染（本机无 GPU，不做），待玩家验证。
