#!/usr/bin/env bash
# 可复用的验证入口（对应原文 "verify skill"）：跑完整验证并打印可核对的证据块。
# 规则见项目根目录 REVIEW.md；本脚本不定义"通过"的含义，只产出证据。
set -uo pipefail
cd "$(dirname "$0")/.."

echo "=== verify $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "no-git")
echo "[rule]     REVIEW.md"
echo "[commit]   $COMMIT"
echo "[sample]   E2E=src/test/resources/report_all.html"
echo "[sample]   idem=SubmitIdempotencyTest/SubmitIdempotencyPropertyTest/SubmitServiceKeyTest/SubmitServiceImplIdempotencyTest"
echo "[sample]   real-smoke=SubmitRealRedisSmoke (需真实Redis/MySQL+口令, 手动跑, 不并入mvn test)"

mvn -q clean test
TEST_RC=$?
echo "[run]      mvn clean test exit=$TEST_RC"

TOTAL=0; ERR=0; FAIL=0
for f in target/surefire-reports/TEST-*.xml; do
  [ -e "$f" ] || continue
  line=$(grep -m1 '<testsuite ' "$f")
  t=$(echo "$line" | sed -n 's/.*tests="\([0-9]*\)".*/\1/p')
  e=$(echo "$line" | sed -n 's/.*errors="\([0-9]*\)".*/\1/p')
  fl=$(echo "$line" | sed -n 's/.*failures="\([0-9]*\)".*/\1/p')
  TOTAL=$((TOTAL + t)); ERR=$((ERR + e)); FAIL=$((FAIL + fl))
  echo "           $(basename "$f" .xml): tests=$t errors=$e failures=$fl"
done
echo "[tests]    total=$TOTAL errors=$ERR failures=$FAIL"

mvn -q javadoc:javadoc
DOC_RC=$?
echo "[run]      mvn javadoc:javadoc exit=$DOC_RC"

if [ "$TEST_RC" -eq 0 ] && [ "$DOC_RC" -eq 0 ] && [ "$ERR" -eq 0 ] && [ "$FAIL" -eq 0 ]; then
  echo "[verdict]  PASS"
else
  echo "[verdict]  FAIL"
fi
echo "[note]     代码再变（含其他进程并改）→ 本结论 STALE，须重跑"
echo "[note]     真实中间件 smoke（可选，口令走环境变量，不写文件）："
echo "           PBCCRC_DB_PWD=<dbPwd> PBCCRC_REDIS_PWD=<redisPwd> mvn -Dtest=SubmitRealRedisSmoke test"
echo "=== end verify ==="
