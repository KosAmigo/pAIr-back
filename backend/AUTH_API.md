# 회원 프로필 API

아래 API는 `Authorization: Bearer <accessToken>` 헤더가 필요하다.

## 프로필 조회

`GET /api/auth/me`는 `id`, `email`, `name`, `role`, `companyId`를 반환한다.

## 비밀번호 변경

`PATCH /api/auth/password`

```json
{"currentPassword":"현재 비밀번호","newPassword":"새 비밀번호"}
```

새 비밀번호는 8~72자이면서 UTF-8 기준 72바이트 이하여야 한다. 성공하면 `204 No Content`를 반환하고 모든 기기의 액세스·리프레시 토큰을 무효화한다. 클라이언트는 저장된 토큰을 지우고 다시 로그인해야 한다.

## 회원 탈퇴

`POST /api/auth/withdrawal`

```json
{"currentPassword":"현재 비밀번호"}
```

성공하면 `204 No Content`를 반환하고 모든 기기의 토큰을 무효화한다. `USER`는 언제든 탈퇴할 수 있고 `ADMIN`은 탈퇴할 수 없다. `MANAGER`와 `PROCTOR`는 소속 기업의 취소되지 않은 예정·진행 중 회차가 있으면 `409 Conflict`를 반환한다.

계정은 `WITHDRAWN`으로 바뀌고 이름과 이메일이 익명값으로 교체된다. 응시·채점 기록, 크레딧 원장, 기업 초대 및 실전 응시자 정보는 보존된다. 동일 이메일로 재가입하면 신규 가입 크레딧이 다시 지급된다.

유효하지 않은 토큰 또는 현재 비밀번호는 `401 Unauthorized`, 잘못된 입력은 `400 Bad Request`, 관리자 탈퇴는 `403 Forbidden`이다.
