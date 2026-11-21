-- 서버가 사진 상한(참가자당 300장)에 막혀 사진을 받지 않은 이벤트에 그 사유를 남긴다.
-- 감독 화면이 "사진 없음"을 상한 초과 / 보관 기간 삭제 / 업로드 실패로 구분해 보여 주기 위한 값이다. 비어 있으면 상한 때문이 아니다.
ALTER TABLE proctor_events ADD COLUMN snapshot_skip_reason VARCHAR(20);
COMMENT ON COLUMN proctor_events.snapshot_skip_reason IS '사진을 받지 않은 서버 쪽 사유 (LIMIT_EXCEEDED: 사진 상한 초과), 없으면 비움';
