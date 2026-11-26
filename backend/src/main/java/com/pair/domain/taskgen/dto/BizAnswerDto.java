package com.pair.domain.taskgen.dto;

import java.util.List;

/** 4단계(BIZ) 정답 쪽: 정답 키(문항별 정답·인정 답·검산식·근거 자료·흔한 오답)와 채점표. 응시자에게 보이는 글은 쓰지 않는다 */
public record BizAnswerDto(String checklist, List<Key> keys) {

    /** id: 사례 name(Q번호). 객관식의 정답 보기는 코드가 사례의 기대 결과로 정하므로 accepted·calc 는 단답형만 쓴다 */
    public record Key(String id, List<String> accepted, String calc, List<BizDraftDto.Source> sources,
            List<BizDraftDto.WrongAnswer> commonWrongAnswers) {}
}
