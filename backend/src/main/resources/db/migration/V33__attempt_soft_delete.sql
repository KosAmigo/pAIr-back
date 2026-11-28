-- 복기에서 응시자가 제출하지 않은 자가진단 응시를 지운다. 크레딧 원장이 응시를 참조하므로 행은 남기고 시각만 적는다.
ALTER TABLE attempts ADD COLUMN deleted_at TIMESTAMPTZ;
COMMENT ON COLUMN attempts.deleted_at IS '응시자가 삭제한 시각(자가진단 미제출만). 값이 있으면 화면과 API 에서 없는 응시로 본다';
