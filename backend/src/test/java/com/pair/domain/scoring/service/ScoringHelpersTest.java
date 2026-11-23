package com.pair.domain.scoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;



class ScoringHelpersTest {

    @Test
    void rubric_sixItemsTotal100() {
        assertEquals(6, RubricService.ITEMS.size());
        assertEquals(100, RubricService.total());
        assertTrue(RubricService.ITEMS.stream().noneMatch(i -> i.key().equals("literacy") || i.key().equals("reproducibility")));
    }

    @Test
    void rubric_zeroChecksMatchItemMax() {
        // "이 항목 0점" 체크는 항목 배점과 같아야 한다
        for (var code : List.of("U0", "C0", "K0", "J0", "D0")) {
            var c = RubricService.CHECKS.stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
            assertEquals(RubricService.item(c.item()).max(), c.points(), code);
        }
        assertTrue(RubricService.CHECKS.stream().noneMatch(c -> c.code().startsWith("R") || c.code().equals("U4")));
    }

    @Test
    void rubric_checksForTrack_haveUniqueCodes() {
        for (boolean dev : new boolean[] {true, false}) {
            var codes = RubricService.checksFor(dev).stream().map(RubricService.Check::code).toList();
            assertEquals(codes.size(), new java.util.HashSet<>(codes).size());
            assertTrue(codes.containsAll(List.of("U0", "U7", "C1", "K1", "K3", "J1", "J3", "D1", "D5", "P1")));
        }
        assertTrue(RubricService.checksFor(true).stream().anyMatch(c -> c.code().equals("D0")));
        assertTrue(RubricService.checksFor(false).stream().noneMatch(c -> c.code().equals("D0")));
    }

    @Test
    void countTests_onlyCountsTestFolder() {
        assertEquals(2, ScoringService.countTests(Map.of(
                "test/shop/A.java", "@Test void a(){} @Test void b(){}",
                "src/shop/B.java", "@Test")));
    }

    @Test
    void notesFilled_ignoresTemplateHeadings() {
        assertFalse(ScoringService.notesFilled("# 원인\n\n# 판단 근거\n"));
        assertTrue(ScoringService.notesFilled("# 원인\n잠금 순서\n"));
    }

    @Test
    void clock_formatsElapsed() {
        Instant start = Instant.parse("2026-11-11T00:00:00Z");
        assertEquals("01:02:03", ScoringService.clock(start, start.plusSeconds(3723)));
    }
}
