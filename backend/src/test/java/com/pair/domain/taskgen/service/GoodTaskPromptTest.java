package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 생성 프롬프트에 기준 문서(좋은_과제_기준.md)의 핵심 정의·경계·품질 규칙이 들어갔는지 확인한다 */
class GoodTaskPromptTest {

    private static TaskGenRequest form(String track, int minutes) {
        return new TaskGenRequest(track, "주제", "함정", "LIVE", minutes);
    }

    private static List<String> systems() {
        return List.of(new DevTaskGeneratorService(null, null, null, null, null).system(form("DEV", 60)),
                new BizTaskGeneratorService(null, null, null, null, null, null).system(form("BIZ", 60)));
    }

    @Test
    @DisplayName("1장: 맨 앞에 핵심 정의(AI 답을 그대로 받으면 함정에 걸리고, 검증하는 사람이 이김)와 '꼬아서 어렵게 만들지 않음'이 있다")
    void purposeFirst() {
        for (String s : systems()) {
            assertThat(s).startsWith("[과제의 목적]");
            assertThat(s).contains("AI 답을 그대로 받아들이면 함정에 걸려서", "더 높은 점수를 받는 과제",
                    "나온 답을 손대지 않고 내면 함정에 걸려야 합니다", "꼬아서 어렵게 만들면 안 됩니다", "꼼꼼히 확인하면 함정을 피할 수 있어야 합니다");
        }
    }

    @Test
    @DisplayName("2장: 명세와 답 노출의 경계(줘도 되는 것 / 주면 안 되는 것 / 꼭 필요한 기대 결과는 여러 예 중 하나)가 한 번만 있다")
    void boundaryOnce() {
        for (String s : systems()) {
            assertThat(s).contains("[명세와 답 노출의 경계]", "줘도 되는 것은 무엇이 맞는 결과인지입니다", "주면 안 되는 것은 왜 틀렸는지와 어디를 어떻게 고칠지입니다",
                    "계산 순서나 절차", "여러 예 가운데 하나로 두고, 따로 떼어 규칙 문장으로 강조하지 않습니다");
            assertThat(s.split("\\[명세와 답 노출의 경계\\]", -1)).hasSize(2);
        }
    }

    @Test
    @DisplayName("3장: Q2 현실적, Q3 피할 수 있는 함정, Q4 검증이 결과로 이어짐, Q5 업계 지식 불필요가 양쪽에 있다")
    void qualityRules() {
        var dev = systems().get(0);
        var biz = systems().get(1);
        assertThat(dev).contains("일부러 읽기 어렵게 꼰 코드", "속임수나 자료에 없는 사실이 필요한 함정은 쓰지 않습니다", "나눠서 확인할 거리", "필요한 용어와 규칙은 설명과 NOTES.md 안에 씁니다");
        assertThat(biz).contains("일부러 만든 티가 나는 오답", "속임수나 자료 밖 지식이 필요한 함정은 쓰지 않습니다", "하나씩 대조해 보면 틀린 곳이 드러나야 합니다",
                "업계 지식이 없어도 풀 수 있게 필요한 용어와 규칙은 자료 안에 씁니다");
    }

    @Test
    @DisplayName("4장 BIZ: 용어 정의는 뜻만, 계산 절차는 쓰지 않는다 (옛 '지표의 정의만 줍니다' 문장은 없다)")
    void bizTermDefinition() {
        var biz = systems().get(1);
        assertThat(biz).contains("그 말이 무엇을 뜻하는지만 씁니다", "어떻게 구하는지(계산 절차, 단계별 계산식", "근거 문서는 2개 이상", "다시 할 수 있어야 합니다")
                .doesNotContain("지표의 정의만 줍니다");
    }

    @Test
    @DisplayName("BIZ 문항 규칙: 함정 단답형과 결론 객관식(보기=결론 문구)을 넣게 한다 (M5 검사가 이 문항들을 본다)")
    void bizTrapQuestions() {
        assertThat(systems().get(1)).contains("함정에 빠지면 답이 달라지는 단답형을 1개 이상", "결론(추천안, 통과 여부)을 묻는 객관식을 1개 이상",
                "trap.correctConclusion 과", "trap.wrongConclusion 과 같은 문구");
    }

    @Test
    @DisplayName("DEV: 요구사항·NOTES.md 는 입출력 예·재현 값만, wrongFixes 는 설명과 파일을 그대로 본 AI 가 낼 법한 수정")
    void devRules() {
        assertThat(systems().get(0)).contains("입력 → 기대 출력 예와 항상 성립해야 하는 불변식만", "현상과 재현 값", "설명과 파일을 그대로 보고");
    }

    @Test
    @DisplayName("프롬프트에 '%' 가 남아 formatted 가 깨지지 않고, 5장 깊이 값이 그대로 들어간다")
    void formatsAndDepth() {
        assertThat(DevTaskGeneratorService.depth(20)).contains("파일 2~3개", "버그 1개", "숨은 테스트 1~2개");
        assertThat(DevTaskGeneratorService.depth(40)).contains("파일 3~5개", "두 곳 이상");
        assertThat(DevTaskGeneratorService.depth(60)).contains("파일 4~6개", "교과서");
        assertThat(BizTaskGeneratorService.depth(20)).contains("CSV 1개 + 문서 2개(각 1~2쪽)", "문항 3개", "1,500~4,000자");
        assertThat(BizTaskGeneratorService.depth(40)).contains("CSV 1~2개 + 문서 2개", "문항 4개");
        assertThat(BizTaskGeneratorService.depth(60)).contains("CSV 1~2개 + 문서 2~3개", "문항 4~6개");
        for (String s : systems()) assertThat(s).doesNotContain("%d", "%s");
    }
}
