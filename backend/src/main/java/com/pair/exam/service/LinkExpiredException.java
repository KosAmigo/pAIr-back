package com.pair.exam.service;

/** 시험 중 연결이 끊긴 뒤 유예 시간(5분) 안에 돌아오지 못해 입장 링크가 만료됨. 만료 기록은 남아야 해서 트랜잭션을 되돌리지 않는다 */
public class LinkExpiredException extends IllegalStateException {
    public LinkExpiredException() {
        super("유예 시간(5분) 안에 돌아오지 않아 입장 링크가 만료되었습니다. 기업 담당자에게 문의해 주세요.");
    }
}
