#!/usr/bin/env bash
# VoxySync 服务端链路回归验证脚本（/tmp 复刻实例，永不触碰生产）
# 用法: bash test/verify-server.sh
# 生产安全纪律: 本脚本只按 '-Xmx1536M' 唯一模式杀/查测试实例（生产为 -Xmx6G）
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/build/libs/voxysync.jar"
TESTDIR="/tmp/mcopt-voxy"
RCON="$TESTDIR/rcon.py"
PASS=0; FAIL=0
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
check() { if echo "$2" | grep -q "$3"; then ok "$1"; else bad "$1  (got: $(echo "$2" | head -3))"; fi; }

echo "== 1/5 构建 =="
export JAVA_HOME="${JAVA_HOME:-/home/ubuntu/workspace/tools/jdk-extract/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
(cd "$ROOT" && ./gradlew build --no-daemon > /tmp/verify-gradle.log 2>&1) || { echo "构建失败"; tail -30 /tmp/verify-gradle.log; exit 1; }
echo "  构建 OK ($(ls -la "$JAR" | awk '{print $5}') B)"

echo "== 2/5 部署到 /tmp 复刻实例 =="
cp "$JAR" "$TESTDIR/mods/voxysync.jar"
pkill -f 'Xmx1536M' 2>/dev/null; sleep 3
pgrep -f 'Xmx1536M' >/dev/null && { echo "旧测试实例未退出"; exit 1; }
rm -f "$TESTDIR/opttest/session.lock"

echo "== 3/5 启动测试实例（25566/RCON 25570） =="
(cd "$TESTDIR" && nohup java -Xms512M -Xmx1536M -jar fabric-server-launcher.jar nogui > server.log 2>&1 &)
sleep 1
JAVA_PID="$(pgrep -f 'Xmx1536M' | head -1)"
echo "  java pid=$JAVA_PID"; echo "$JAVA_PID" > "$TESTDIR/server.pid"
READY=0
for i in $(seq 1 40); do
  if grep -q 'Done (.*)!' "$TESTDIR/server.log" 2>/dev/null; then READY=1; break; fi
  if ! kill -0 "$JAVA_PID" 2>/dev/null; then echo "  ⚠️ 实例进程已退出"; tail -25 "$TESTDIR/server.log"; exit 1; fi
  sleep 3
done
[ "$READY" = 1 ] || { echo "启动超时"; tail -25 "$TESTDIR/server.log"; exit 1; }
echo "  启动完成"

echo "== 4/5 RCON 诊断 =="
sleep 2
OUT1="$(python3 "$RCON" '/voxysync devtest radius 512')"; sleep 1
OUT2="$(python3 "$RCON" '/voxysync devtest all')"; sleep 1
OUT3="$(python3 "$RCON" '/voxysync status')"; sleep 1

check "radius512: 全目录含 25 区域" "$OUT1" '区域数(全目录): *25'
check "radius512: 待发送 9（半径圈选）" "$OUT1" '待发送: *9'
check "radius512: 增量跳过归零" "$OUT1" '待发送 0 (应为 0)'
check "radius512: 由近及远排序" "$OUT1" 'dist=0'
check "radius512: 编解码往返 OK" "$OUT1" '编解码往返 OK'
check "radius512: 分片读取完整性 OK" "$OUT1" '分片读取完整性: OK'
check "all: 待发送 25" "$OUT2" '待发送: *25'
check "all: 增量跳过归零" "$OUT2" '待发送 0 (应为 0)'
check "status: 显示配置" "$OUT3" '状态'
check "status: 限速 1024" "$OUT3" '1024 KB/s'

echo "== 5/5 清理 =="
pkill -f 'Xmx1536M' 2>/dev/null; sleep 2
pgrep -f 'Xmx1536M' >/dev/null && bad "测试实例未停止" || ok "测试实例已停止"

echo
echo "结果: 通过 $PASS / 失败 $FAIL"
[ "$FAIL" = 0 ] && echo "==> VERIFY-SERVER 全部通过" || { echo "==> 存在失败项"; exit 1; }
