package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * 4단계 검수의 점검 하나가 내는 결과: 위반 목록(없으면 빈 목록). 위반마다 항목 번호, 글에서 그대로 복사한 인용, 위치, 고칠 방향.
 * 점검마다 맡은 항목만 낸다(검수 점검 목록은 TaskReviewService.Check).
 */
public record ReviewResultDto(List<Violation> violations) {

    /** item: 항목 번호(M2, M5, C1~C3, Q…), quote: 인용, location: 필드·파일·문항·보기 위치, fix: 고칠 방향, stage: 정답 키·테스트 근거 점검이 적는 고칠 단계(WRITING·BLUEPRINT·ANSWER·QUESTIONS, 모르면 빈 값) */
    public record Violation(String item, String quote, String location, String fix, String stage) {

        /** 고칠 단계 표시 없이 만든다(테스트·코드가 만드는 위반용) */
        public Violation(String item, String quote, String location, String fix) {
            this(item, quote, location, fix, null);
        }

        /** Q(품질)는 경고만, 그 밖(M·C·실행검사)은 꼭 고친다 */
        public boolean mustFix() {
            return item == null || !item.strip().toUpperCase().startsWith("Q");
        }
    }
}
