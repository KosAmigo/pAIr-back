package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 생성 프롬프트에 기준 문서(좋은_과제_기준.md, 2026-11-24 개정)의 핵심 정의·E1~E5·경계·품질 규칙이 들어갔는지 확인한다 */
class GoodTaskPromptTest {

    private static TaskGenRequest form(String track, int minutes) {
        return new TaskGenRequest(track, "주제", "함정", "LIVE", minutes);
    }

    private static List<String> systems() {
        return List.of(new DevTaskGeneratorService(null, null, null, null, null).system(form("DEV", 60)),
                new BizTaskGeneratorService(null, null, null, null, null, null).system(form("BIZ", 60)));
    }

    @Test
    @DisplayName("1·2장: 맨 앞에 핵심 정의(역할 구별·지시·대조 검증)와 E1~E5 다섯 요소, '꼬아서 어렵게 만들지 않음'이 있다")
    void purposeFirst() {
        for (String s : systems()) {
            assertThat(s).startsWith("[과제의 목적]");
            assertThat(s).contains("그것을 구별하고 AI 에 잘 지시하고 자료와 대조해 검증한 사람이 더 좋은 결과를 내는 과제", "AI 가 혼자 풀 수 있어도 됩니다",
                    "꼬아서 어렵게 만들면 안 됩니다", "E1 맡길 거리", "E2 판단 지점", "E3 숨은 결함", "E4 검증 수단", "E5 이해 확인 후속 질문");
            assertThat(s).doesNotContain("나온 답을 손대지 않고 내면 함정에 걸려야 합니다");
        }
    }

    @Test
    @DisplayName("3장: 명세와 답 노출의 경계(줘도 되는 것 / 주면 안 되는 것 / 문항 문구가 계산법을 지정하지 않음 / 꼭 필요한 기대 결과는 여러 예 중 하나)가 한 번만 있다")
    void boundaryOnce() {
        for (String s : systems()) {
            assertThat(s).contains("[명세와 답 노출의 경계]", "줘도 되는 것은 무엇이 맞는 결과인지입니다", "주면 안 되는 것은 왜 틀렸는지와 어디를 어떻게 고칠지입니다",
                    "계산 순서나 절차", "문항 문구도 계산법을 지정하지 않습니다", "여러 예 가운데 하나로 두고, 따로 떼어 규칙 문장으로 강조하지 않습니다");
            assertThat(s.split("\\[명세와 답 노출의 경계\\]", -1)).hasSize(2);
        }
    }

    @Test
    @DisplayName("4장: Q2 현실적, Q3 피할 수 있는 결함, Q4 검증이 결과로 이어짐, Q5 업계 지식 불필요가 양쪽에 있다")
    void qualityRules() {
        var dev = systems().get(0);
        var biz = systems().get(1);
        assertThat(dev).contains("일부러 꼰 코드", "자료에 없는 지식이 필요한 결함은 쓰지 않습니다", "필요한 용어와 규칙은 설명과 NOTES.md 안에 씁니다");
        assertThat(biz).contains("일부러 만든 티가 나는 오답", "속임수나 자료 밖 지식이 필요한 함정은 쓰지 않습니다", "하나씩 대조해 보면 틀린 곳이 드러나게 합니다",
                "업계 지식이 없어도 풀 수 있게 필요한 용어와 규칙은 자료 안에 씁니다");
    }

    @Test
    @DisplayName("5장 BIZ: E2 트레이드오프(정답 하나 아님)와 E3 함정(정답 있음)을 둘 다 시키고, 용어 정의는 뜻만, 문항 문구는 계산법을 정하지 않는다")
    void bizRules() {
        var biz = systems().get(1);
        assertThat(biz).contains("판단 지점(judgmentPoints, 트레이드오프)", "정답이 하나가 아니고 자료의 사실을 근거로 설명하면 인정되는 선택",
                "숨은 결함(trap, hiddenFlaws, 정답 있음)", "자료를 서로 대조해야만 보이는 함정", "caughtBy",
                "뜻만 씁니다", "어떻게 구하는지(계산 절차, 단계별 계산식", "문항 문구도 어떤 방식으로 계산하라고 정하지 않고", "근거 문서는 2개 이상", "다시 할 수 있어야 합니다")
                .doesNotContain("지표의 정의만 줍니다");
    }

    @Test
    @DisplayName("BIZ 문항 규칙: 함정 단답형과 결론 객관식(보기=결론 문구)을 넣게 하고, 3번째 후속 질문은 E5(왜 정했나/조건이 바뀌면/내보내겠나)")
    void bizTrapQuestions() {
        assertThat(systems().get(1)).contains("함정에 빠지면 답이 달라지는 단답형을 1개 이상", "결론(추천안, 통과 여부)을 묻는 객관식을 1개 이상",
                "trap.correctConclusion 과", "trap.wrongConclusion 과 같은 문구", "왜 그렇게 정했나 / 조건이 바뀌면 / 이대로 내보내겠나");
    }

    @Test
    @DisplayName("DEV: 기능 추가 기본·단계형, E2 판단 지점·E3 숨은 결함 필드, 숨은 결함을 놓친 풀이(wrongFixes), E5 후속 질문")
    void devRules() {
        assertThat(systems().get(0)).contains("기존 Java 코드를 읽고 기능을 추가합니다", "버그 수정 → 기능 추가 → 확장 단계",
                "판단 지점(judgmentPoints)", "basis", "어느 선택지를 골라도 통과", "숨은 결함(hiddenFlaws)", "경계값, 시간대·날짜, 반올림·단위, 동시성, 빠진 예외 처리", "caughtBy",
                "입력 → 기대 출력 예와 항상 성립해야 하는 불변식만", "현상과 재현 값", "숨은 결함을 놓친 풀이", "설명과 파일을 그대로 보고",
                "왜 그렇게 정했나 / 조건이 바뀌면 / 이대로 내보내겠나")
                .doesNotContain("함정으로 주어진 버그");
    }

    @Test
    @DisplayName("프롬프트에 '%' 가 남아 formatted 가 깨지지 않고, 6장 깊이 표가 그대로 들어간다")
    void formatsAndDepth() {
        assertThat(DevTaskGeneratorService.depth(20)).contains("파일 2~3개", "기능 1개", "판단 지점 1개", "숨은 결함 1개");
        assertThat(DevTaskGeneratorService.depth(40)).contains("파일 3~5개", "2단계", "판단 지점 1~2개", "숨은 결함 1~2개");
        assertThat(DevTaskGeneratorService.depth(60)).contains("파일 4~6개", "3단계", "판단 지점 2개", "숨은 결함 2개", "교과서");
        assertThat(BizTaskGeneratorService.depth(20)).contains("CSV 1개 + 문서 2개(각 1~2쪽)", "문항 3개", "판단 지점 1개", "함정 1개", "1,500~4,000자");
        assertThat(BizTaskGeneratorService.depth(40)).contains("CSV 1~2개 + 문서 2개", "문항 4개", "판단 지점 1~2개", "함정 1~2개");
        assertThat(BizTaskGeneratorService.depth(60)).contains("CSV 1~2개 + 문서 2~3개", "문항 4~6개", "판단 지점 2개", "함정 2개");
        for (String s : systems()) assertThat(s).doesNotContain("%d", "%s");
    }
}
