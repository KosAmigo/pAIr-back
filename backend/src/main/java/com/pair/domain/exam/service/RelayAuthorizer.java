package com.pair.domain.exam.service;

/** 응시자 PC 가 중계에 붙을 때 쓰는 입장 토큰 확인 (테스트에서 바꿔 끼울 수 있게 경계로 둔다) */
public interface RelayAuthorizer {

    /** 입장 토큰이 유효하고 감독을 쓰는 회차이며 아직 끝나지 않았으면 true */
    boolean authorize(String inviteToken);
}
