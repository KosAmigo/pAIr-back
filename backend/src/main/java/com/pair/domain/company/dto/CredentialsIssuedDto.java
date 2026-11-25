package com.pair.domain.company.dto;

import java.util.List;

/**
 * 임시 비밀번호를 만들어 메일로 보낼 계정들. 트랜잭션이 커밋된 뒤에 처리한다.
 * placeholderHash 는 발급 때 저장한 로그인할 수 없는 비밀번호 해시다. 그 사이 재발급됐으면 값이 달라서 이 발급은 무시된다.
 */
public record CredentialsIssuedDto(List<Long> accountIds, String placeholderHash) {}
