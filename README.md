# pAIr-back
AI 활용 역량 진단 플랫폼 백엔드 (Spring Boot 3.5, Spring AI). 프런트는 [pAIr-front](https://github.com/KosAmigo/pAIr-front).

## 실행
1. 개발용 DB와 메일: `docker compose up -d`
2. API 키: 환경 변수 `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`
3. 서버: `./backend/gradlew -p backend bootRun` (포트 8180). IntelliJ 는 `backend/` 를 Gradle 프로젝트로 연다

## 폴더
- `backend/` 백엔드 코드 (Gradle 프로젝트, `backend/src/`), `grader/` 채점 툴 프롬프트 버전과 샘플 응시, `poc/` 기술검증 코드, `docs/` 기획·ERD·평가·기술검증 문서
- `pilot-data/` 응시 기록 (git 에 올리지 않음)
