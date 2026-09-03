# VoxySync 生产部署预案（待用户确认后执行）

> 本文件只是预案——**任何一步都必须在用户明确同意后执行**。
> 纪律：不提前改生产（mods/、客户端包、重启都要确认）；不改 JVM 内存配置（现为 -Xms2G -Xmx6G，用户自改）。

## 0. 部署前快照（只读检查）
```bash
ps -o pid,lstart,cmd -C java      # 确认生产 -Xmx6G 进程与启动时间
cd /opt/mc-control-panel/backend && /opt/mc-control-panel/.venv/bin/python3 -c "import asyncio,sys;sys.path.insert(0,'.');from app import rcon;print(asyncio.run(rcon.rcon_command('list')))"   # 在线玩家
```

## 1. 服务端 mod + 配置（需重启生效）
```bash
sudo -n cp /home/ubuntu/workspace/voxysync/build/libs/voxysync.jar /home/ubuntu/minecraft-fabric-server/mods/voxysync-0.1.0.jar
sudo -n chown ubuntu:ubuntu /home/ubuntu/minecraft-fabric-server/mods/voxysync-0.1.0.jar
# 配置文件（全图模式 = 用户已确认的主需求；启动会打印红色安全警告）
sudo -n tee /home/ubuntu/minecraft-fabric-server/config/voxysync.json > /dev/null <<'JSON'
{
  "enableVoxySync": true,
  "syncMode": "all",
  "radiusBlocks": 2000,
  "speedLimitKBps": 1024,
  "maxPacketSize": 262144,
  "autoStartOnJoin": true
}
JSON
sudo -n chown ubuntu:ubuntu /home/ubuntu/minecraft-fabric-server/config/voxysync.json
```

```bash
# 重启（确认在线玩家少；systemd 自动拉起，约 1 分钟）
sudo -n systemctl restart minecraft.service
# 验证
grep -iE 'VoxySync' /home/ubuntu/minecraft-fabric-server/logs/latest.log | head   # 应有 ENABLED 警告 + 'mode=all'
# rcon list / spark tps 确认 TPS 20、玩家在线数
```

## 2. 客户端包（免重启打包）
```bash
sudo -n cp /home/ubuntu/workspace/voxysync/build/libs/voxysync.jar /home/ubuntu/minecraft-fabric-server/automodpack/host-modpack/main/mods/voxysync-0.1.0.jar
sudo -n chown ubuntu:ubuntu /home/ubuntu/minecraft-fabric-server/automodpack/host-modpack/main/mods/voxysync-0.1.0.jar
```
然后在面板「🔄 客户端同步」页点【重新打包】（= POST /api/syncmods/repack，RCON 触发 automodpack generate，无需重启 MC）。
验证：面板同步页显示 voxysync-0.1.0.jar 在客户端包；automodpack/host-modpack/main/ 里已有该文件且包时间戳更新。

## 3. 玩家侧行为
- 装有 AutoModpack（包内已含 voxy 移植版）的客户端自动更新；下次进服自动开始同步（actionbar 进度）。
- **全图首轮 ≈ 1698 区域 ≈ 20GB @ 1MB/s ≈ 5.5 小时**；断线可续传（增量哈希）。
- 如需提速：改 config 的 speedLimitKBps（如 4096 → 约 1.4 小时）后重启服务端。
- 若不想全图：syncMode 改回 \"radius\"（默认 radiusBlocks 2000 方块圈选）。

## 4. 验证清单
- [ ] 服务端日志有 VoxySync 警告 + mode=all
- [ ] /voxysync status（OP 或控制台 RCON）显示 开启/模式 all
- [ ] 面板同步页客户端包含 voxysync jar（repack 后）
- [ ] 玩家真机：进服出现 \"Voxy 同步: x%\" actionbar → 完成后 \"导入超远渲染 LoD\" 消息 → 远处地形可见

## 5. 回滚（世界数据零改动）
```bash
sudo -n rm -f /home/ubuntu/minecraft-fabric-server/mods/voxysync-0.1.0.jar
sudo -n rm -f /home/ubuntu/minecraft-fabric-server/automodpack/host-modpack/main/mods/voxysync-0.1.0.jar
sudo -n rm -f /home/ubuntu/minecraft-fabric-server/config/voxysync.json
# 面板「🔄 客户端同步」重新打包 → sudo -n systemctl restart minecraft.service
# 客户端缓存无害（.minecraft/voxysync/* 删除即可；Voxy LoD 数据也可清）
```
