#!/bin/sh
# 컨테이너 안에서 컴파일(+테스트). 사용: run.sh build|run   (소스: /workspace/src 읽기 전용, 표식: $PAIR_MARK)
# 표식으로 시작하는 줄만 러너가 구조 정보로 읽는다. 응시자 코드는 표식을 모르므로 흉내낼 수 없다(테스트 단계에선 환경변수도 지운다).
M="$PAIR_MARK"
OUT=/tmp/classes
mkdir -p "$OUT" /tmp/reports
find /workspace/src -name '*.java' > /tmp/files.txt
echo '$ javac'
if ! javac -encoding UTF-8 -proc:none -d "$OUT" -cp /opt/junit.jar @/tmp/files.txt 2>&1; then
  echo "$M COMPILE-FAILED"; exit 0
fi
echo "$M COMPILED"
[ "$1" = build ] && exit 0
echo '$ junit execute --scan-class-path'
env -u PAIR_MARK java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -jar /opt/junit.jar execute --class-path "$OUT" --scan-class-path --disable-banner --disable-ansi-colors \
  --details=tree --details-theme=ascii --reports-dir /tmp/reports 2>&1
for f in /tmp/reports/TEST-*.xml; do
  [ -f "$f" ] || continue
  echo "$M FILE"; cat "$f"; echo; echo "$M END"
done
exit 0
