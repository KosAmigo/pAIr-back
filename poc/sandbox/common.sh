# 공통: 이미지 빌드, 컨테이너 시작/정리 (응시 1건 = 컨테이너 1개 + 작업 폴더 볼륨 1개)
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
IMAGE=pair-sandbox
ms() { echo $(( $(date +%s%N) / 1000000 )); }

build_image() {
  local jar ctx
  jar=$(find ~/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-console-standalone -name 'junit-platform-console-standalone-*.jar' ! -name '*sources*' | sort | tail -1)
  ctx=$(mktemp -d); cp "$HERE/Dockerfile" "$ctx/"; cp "$jar" "$ctx/junit.jar"
  docker build -q -t $IMAGE "$(cygpath -w "$ctx" 2>/dev/null || echo "$ctx")" >/dev/null || { echo "이미지 빌드 실패"; exit 1; }; rm -rf "$ctx"
  # 준비용(작업 폴더 권한 설정): 같은 이미지를 root 로
  printf 'FROM %s\nUSER root\n' $IMAGE | docker build -q -t $IMAGE-setup - >/dev/null
}

# --init: 끝난 자식 프로세스를 거둬 좀비가 쌓이지 않게 한다(쌓이면 프로세스 128개 제한을 차지)
# 격리 설정: 인터넷 차단, 메모리 512MB(스왑 없음), CPU 1개, 프로세스 128개, 루트 읽기 전용, 일반 사용자, 권한 모두 제거
# 작업 폴더: 크기 제한 tmpfs 볼륨(기본 64MB, 파일 2만 개). 일반 볼륨은 호스트 디스크를 끝까지 채울 수 있다.
#   tmpfs 라 컨테이너가 모두 내려가면 내용이 사라진다 → 응시 중에는 상자를 계속 띄워 두고, 보관은 체크포인트 tar 로 한다.
WS_SIZE=${WS_SIZE:-64m}; WS_INODES=${WS_INODES:-20000}
make_ws() { docker volume create --opt type=tmpfs --opt device=tmpfs --opt o=size=$WS_SIZE,nr_inodes=$WS_INODES,uid=10001,mode=0755 "$1-ws" >/dev/null; }
start_box() {
  docker volume inspect "$1-ws" >/dev/null 2>&1 || make_ws "$1"
  docker run -d --init --name "$1" --network none --memory 512m --memory-swap 512m --cpus 1 --pids-limit 128 \
    --read-only --tmpfs /tmp:size=64m,exec,uid=10001 --security-opt no-new-privileges --cap-drop ALL \
    -v "$1-ws:/workspace" $IMAGE sleep infinity >/dev/null
}
stop_box() { docker rm -f "$1" >/dev/null 2>&1; docker volume rm "$1-ws" >/dev/null 2>&1; }

# 호스트 폴더 내용을 컨테이너 작업 폴더에 넣기
put_dir() { tar -C "$2" -cf - . | docker exec -i "$1" tar -C /workspace -xf -; }
# 실행이 끝나면 init 과 sleep 만 빼고 남은 프로세스를 모두 끊는다. timeout 은 sh 만 끊고 그 안의 java 는 남기기 때문
cleanup() { docker exec "$1" sh -c 'for p in $(pgrep -v -x "sleep|docker-init"); do [ "$p" != $$ ] && kill -9 "$p"; done; true' 2>/dev/null; sleep 0.3; }
procs() { docker exec "$1" ps -eo comm= | grep -cvxE 'sleep|docker-init|ps'; }
alive() { docker exec "$1" echo ok 2>/dev/null || echo DEAD; }
