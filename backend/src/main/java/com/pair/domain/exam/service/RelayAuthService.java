package com.pair.domain.exam.service;

import java.util.Optional;

import com.pair.domain.exam.dto.RelayIdentityDto;

/** 응시자 PC 가 중계에 붙을 때 쓰는 입장 토큰 확인 (테스트에서 바꿔 끼울 수 있게 경계로 둔다) */
public interface RelayAuthService {

    /** 입장 토큰이 유효하고 감독을 쓰는 회차이며 아직 끝나지 않았으면 true */
    boolean authorize(String inviteToken);

    /** 입장 토큰이 가리키는 응시자. 감독관이 이 응시자를 열 수 있게 짝에 붙여 둔다. 모르면 empty */
    default Optional<RelayIdentityDto> identify(String inviteToken) {
        return Optional.empty();
    }
}
