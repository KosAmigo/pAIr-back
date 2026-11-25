-- 주의: 저장소(SnapshotStorage)의 env-scan/... 파일과 운영 DB에 이미 쌓인 점검 사진 행은 이 마이그레이션이 지우지 않는다(테이블만 드롭).
-- 기존 파일은 드롭 전에 정리해야 함.
DROP TABLE env_scan_photos;
