-- 서비스를 돌리는 데 필요한 기본 행 (2026-11-19).
-- 회원 API 가 생기기 전까지 프론트는 목업 계정(관리자·응시자·예시테크 매니저·감독관)으로 로그인하고, 그 회원 ID 를
-- X-Pair-User-Id 헤더로 보낸다. ERD 외래 키(exam_sessions.created_by, attempts.user_id 등)를 지키려면 같은 ID 의 회원이 있어야 한다.
-- 값은 ERD 샘플(pAIr-docs/설계/erd/sample/csv)과 같다. password_hash 는 샘플 자리표시자라 로그인에 쓰이지 않는다.
-- 과제는 ERD 규칙대로 자가진단(SELF) 원본과 기업 실전(LIVE) 복사본을 따로 둔다 (복사본의 parent_task_id = 원본).
-- 과제 내용(파일)은 아직 classpath:tasks/{원본 code} 에 있다.

INSERT INTO companies (company_id, name, business_no, contact, created_at) VALUES
    (1, '예시테크', '123-45-67890', '02-1234-5678', '2026-11-01T00:00:00Z');

INSERT INTO users (user_id, email, password_hash, name, member_status, role, company_id, created_at) VALUES
    (1, 'admin@pair.test', '$2a$10$sampleAdminHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '관리자', 'ACTIVE', 'ADMIN', NULL, '2026-11-01T00:00:00Z'),
    (2, 'a@pair.test', '$2a$10$sampleUserAHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '응시자A', 'ACTIVE', 'USER', NULL, '2026-11-02T00:00:00Z'),
    (3, 'b@pair.test', '$2a$10$sampleUserBHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '응시자B', 'ACTIVE', 'USER', NULL, '2026-11-03T00:00:00Z'),
    (4, 'hr@example-tech.test', '$2a$10$sampleCompanyHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '예시테크 매니저', 'ACTIVE', 'MANAGER', 1, '2026-11-04T00:00:00Z'),
    (6, 'proctor@example-tech.test', '$2a$10$sampleProctorHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '예시테크 감독관', 'ACTIVE', 'PROCTOR', 1, '2026-11-06T00:00:00Z');

INSERT INTO tasks (task_id, code, title, summary, description, requirements, track, category, difficulty,
                   time_limit_min, usage, status, parent_task_id, created_by, created_at, updated_at, published_at) VALUES
    (1, 'order-deadlock', '주문 처리 장애 원인 분석', '주문이 3초 넘게 멈췄다가 실패합니다. 잠금 순서와 재고 정합성 문제를 찾아 고치세요.', '어제 21시부터 주문 생성이 간헐적으로 3초 넘게 멈췄다가 LockTimeoutException으로 실패하고 있습니다. 같은 시간대에 장바구니에 상품 여러 개를 담은 주문이 몰렸습니다.
src/shop 아래 코드와 logs/app.log를 살펴보고, 내장 AI를 활용해 원인을 찾아 수정하세요. 실행 버튼을 누르면 test 폴더의 JUnit 테스트가 실행됩니다.', '- 장애의 근본 원인을 찾아 코드로 수정한다.
- 주문이 실패하면 재고는 주문 전 상태와 같아야 한다.
- 수정이 올바른지 확인할 수 있는 JUnit 테스트를 1개 이상 추가한다.
- NOTES.md에 원인과 판단 근거를 3줄 이내로 정리한다.', 'DEV', '장애 분석', '중급', 60, 'SELF', 'OPEN', NULL, 1, now(), now(), now()),
    (2, 'pricing-plan', '신규 저가 요금제 출시 기획안', '이탈 설문과 매출 자료로 저가 요금제 출시 여부를 판단하는 1페이지 기획안을 씁니다.', '영상 구독 서비스 무비랩의 월 이탈률이 5월 4.1%에서 10월 6.8%로 올랐습니다. 경영진은 월 5,900원 라이트 요금제 출시를 검토하고 있습니다.
자료 폴더의 설문 요약과 매출 데이터를 살펴보고, 내장 AI를 활용해 라이트 요금제 출시 여부에 대한 1페이지 기획안을 기획안.md에 작성하세요.', '- 출시 찬성, 반대, 조건부 중 결론을 밝히고 근거를 3개 이상 제시한다.
- 근거에 쓰는 수치는 제공 자료와 대조해 검증한다.
- 예상 리스크와 대응 방안을 1개 이상 포함한다.', 'BIZ', '기획서', '중급', 60, 'SELF', 'OPEN', NULL, 1, now(), now(), now()),
    (3, 'order-deadlock-live', '주문 처리 장애 원인 분석', '주문이 3초 넘게 멈췄다가 실패합니다. 잠금 순서와 재고 정합성 문제를 찾아 고치세요.', '어제 21시부터 주문 생성이 간헐적으로 3초 넘게 멈췄다가 LockTimeoutException으로 실패하고 있습니다. 같은 시간대에 장바구니에 상품 여러 개를 담은 주문이 몰렸습니다.
src/shop 아래 코드와 logs/app.log를 살펴보고, 내장 AI를 활용해 원인을 찾아 수정하세요. 실행 버튼을 누르면 test 폴더의 JUnit 테스트가 실행됩니다.', '- 장애의 근본 원인을 찾아 코드로 수정한다.
- 주문이 실패하면 재고는 주문 전 상태와 같아야 한다.
- 수정이 올바른지 확인할 수 있는 JUnit 테스트를 1개 이상 추가한다.
- NOTES.md에 원인과 판단 근거를 3줄 이내로 정리한다.', 'DEV', '장애 분석', '중급', 60, 'LIVE', 'OPEN', 1, 1, now(), now(), now()),
    (4, 'pricing-plan-live', '신규 저가 요금제 출시 기획안', '이탈 설문과 매출 자료로 저가 요금제 출시 여부를 판단하는 1페이지 기획안을 씁니다.', '영상 구독 서비스 무비랩의 월 이탈률이 5월 4.1%에서 10월 6.8%로 올랐습니다. 경영진은 월 5,900원 라이트 요금제 출시를 검토하고 있습니다.
자료 폴더의 설문 요약과 매출 데이터를 살펴보고, 내장 AI를 활용해 라이트 요금제 출시 여부에 대한 1페이지 기획안을 기획안.md에 작성하세요.', '- 출시 찬성, 반대, 조건부 중 결론을 밝히고 근거를 3개 이상 제시한다.
- 근거에 쓰는 수치는 제공 자료와 대조해 검증한다.
- 예상 리스크와 대응 방안을 1개 이상 포함한다.', 'BIZ', '기획서', '중급', 60, 'LIVE', 'OPEN', 2, 1, now(), now(), now());

-- 직접 넣은 ID 다음부터 새 행이 번호를 받도록 시퀀스를 맞춘다
SELECT setval(pg_get_serial_sequence('companies', 'company_id'), (SELECT max(company_id) FROM companies));
SELECT setval(pg_get_serial_sequence('users', 'user_id'), (SELECT max(user_id) FROM users));
SELECT setval(pg_get_serial_sequence('tasks', 'task_id'), (SELECT max(task_id) FROM tasks));
