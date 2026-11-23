package com.pair.domain.exam.dto;

import java.util.List;

/**
 * 초대 메일을 보낼 대상 (새로 등록했거나 다시 보내기). 트랜잭션이 커밋된 뒤에 메일을 보낸다.
 * 토큰 원문은 DB 에 없으므로 이 이벤트(메모리)로만 메일러에 넘긴다.
 */
public record InvitesCreatedDto(List<Invite> invites) {

    public record Invite(Long participantId, String token) {
        /** 로그에 토큰이 찍히지 않게 */
        @Override
        public String toString() {
            return "Invite[participantId=" + participantId + "]";
        }
    }
}
