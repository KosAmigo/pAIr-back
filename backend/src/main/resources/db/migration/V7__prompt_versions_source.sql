-- 채점 프롬프트 버전의 원본을 파일(grader/versions)에서 DB 로 옮긴다.

-- 화면에 보이는 버전 이름. name 은 버전 id(예: 20261119-1500_v6-판정-일관성)로 쓴다. 비어 있으면 name 을 보여준다.
ALTER TABLE prompt_versions ADD COLUMN title VARCHAR(100);
COMMENT ON COLUMN prompt_versions.title IS '화면에 보이는 버전 이름(비어 있으면 name 사용)';
COMMENT ON COLUMN prompt_versions.name IS '버전 id(예: 20261119-1500_v6-판정-일관성)';

-- 메모는 500자 제한 없이 그대로 저장한다.
ALTER TABLE prompt_versions ALTER COLUMN note TYPE TEXT;

-- 활성 버전은 하나뿐. 혹시 여러 개 켜져 있으면 가장 최근 것만 남긴다.
UPDATE prompt_versions
   SET is_active = FALSE
 WHERE is_active
   AND version_id <> (SELECT MAX(version_id) FROM prompt_versions WHERE is_active);

CREATE UNIQUE INDEX uq_prompt_versions_active ON prompt_versions (is_active) WHERE is_active;
