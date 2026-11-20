-- 문항 답 저장(ANSWER)을 작업 기록 종류에 추가한다. 기존 행과 이름은 그대로 두고 허용 값만 늘린다.
ALTER TABLE attempt_events DROP CONSTRAINT attempt_events_type_check;
ALTER TABLE attempt_events ADD CONSTRAINT attempt_events_type_check
    CHECK (type IN ('START', 'SAVE', 'BUILD', 'RUN', 'CHAT', 'CHAT_EMPTY',
                    'AI_EDIT', 'SUBMIT', 'FOLLOWUPS', 'FIRST_VALID', 'ANSWER'));
COMMENT ON COLUMN attempt_events.type IS '종류(START 시작/SAVE 저장/BUILD 빌드/RUN 실행/CHAT 질문/CHAT_EMPTY AI 빈 답(채점 제외)/AI_EDIT 수정 제안 적용·거절/SUBMIT 제출/FOLLOWUPS 후속 답변/FIRST_VALID 첫 유효 결과/ANSWER 문항 답 저장)';
