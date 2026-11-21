-- 감독 이벤트 재전송 중복 방지: 응시자 브라우저가 이벤트마다 만든 고유값(client_id).
-- 사진 업로드를 DB 트랜잭션 밖으로 빼면서, 업로드가 실패해 같은 이벤트를 다시 보내도 이벤트가 두 건이 되지 않게 한다.
-- 다시 온 요청은 이미 저장된 이벤트를 찾아 사진만 다시 올려 같은 이벤트에 연결한다.
ALTER TABLE proctor_events ADD COLUMN client_id VARCHAR(36);
CREATE UNIQUE INDEX uq_proctor_events_client_id ON proctor_events (participant_id, client_id) WHERE client_id IS NOT NULL;
COMMENT ON COLUMN proctor_events.client_id IS '응시자 브라우저가 만든 이벤트 고유값(재전송 중복 방지, 없으면 비움)';
