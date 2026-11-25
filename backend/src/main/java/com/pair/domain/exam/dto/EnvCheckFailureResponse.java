package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

/**
 * 환경 점검에 실패한 채 입장한 응시자 한 명. failed 는 동의와 함께 저장된 점검 결과에서 status 가 fail 인 항목이고,
 * noticeSent 는 담당자가 이 응시자에게 안내 메시지를 한 번이라도 보냈는지 나타낸다.
 */
public record EnvCheckFailureResponse(Long participantId, String name, String email, Instant enteredAt, Instant checkedAt,
        List<Item> failed, boolean noticeSent) {

    /** 실패한 점검 항목 (id 는 점검 항목 코드, detail 은 점검이 남긴 사유) */
    public record Item(String id, String label, String detail) {}
}
