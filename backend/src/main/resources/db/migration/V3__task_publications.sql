-- 관리자 과제 콘솔 (팀원 4): 과제 공개 상태. ERD 의 tasks.status(HIDDEN/OPEN) 를 대신한다.
-- 과제 정의는 아직 classpath 파일(resources/tasks)에 있어서, 공개 상태만 DB 에 둔다. tasks 가 DB 로 옮겨지면 tasks.status 로 합친다.
-- 행이 없는 과제는 HIDDEN 으로 본다 (AI 가 새로 만든 과제는 검수 전까지 숨김).
CREATE TABLE task_publications (
    task_id      varchar(64) PRIMARY KEY,
    status       varchar(10) NOT NULL CHECK (status IN ('HIDDEN', 'OPEN')),
    -- 상태를 바꾼 회원 ID (users 가 DB 로 옮겨지면 외래 키)
    updated_by   bigint      NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- 처음 공개한 시각 (다시 숨겨도 남김)
    published_at timestamptz
);

-- 이미 쓰고 있는 과제 두 개는 공개 상태로 시작한다 (관리자 회원 1)
INSERT INTO task_publications (task_id, status, updated_by, published_at) VALUES
    ('order-deadlock', 'OPEN', 1, now()),
    ('pricing-plan',   'OPEN', 1, now());
