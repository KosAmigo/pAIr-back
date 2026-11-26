package com.pair.domain.exam.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.ChatTurnRepository;
import com.pair.domain.exam.dto.SessionAiCostResponse;
import com.pair.domain.exam.dto.SessionAiCostResponse.ModelLine;
import com.pair.domain.exam.dto.SessionAiCostResponse.Part;
import com.pair.domain.scoring.repository.JudgeCallRepository;
import com.pair.global.config.PairProperties;

/**
 * 회차 AI 비용: 회차 응시자들의 AI 채팅(chat_turns)과 채점 모델 호출(judge_calls) 토큰을 모델별로 더하고 설정 단가를 곱한다.
 * 회사·회차별 값은 Prometheus 라벨로 내면 종류가 끝없이 늘어나므로(AiMetrics 참고) 지표가 아니라 DB 에서 계산한다.
 */
@Service
public class SessionAiCostService {

    private static final double PER_TOKEN = 1_000_000.0;

    private final ChatTurnRepository chatTurns;
    private final JudgeCallRepository judgeCalls;
    private final AttemptRepository attempts;
    private final Map<String, PairProperties.Price> pricing;

    public SessionAiCostService(ChatTurnRepository chatTurns, JudgeCallRepository judgeCalls, AttemptRepository attempts,
            PairProperties props) {
        this.chatTurns = chatTurns;
        this.judgeCalls = judgeCalls;
        this.attempts = attempts;
        this.pricing = props.pricing() == null ? Map.of() : props.pricing();
    }

    @Transactional(readOnly = true)
    public SessionAiCostResponse cost(long sessionId) {
        TreeSet<String> unpriced = new TreeSet<>();
        List<ModelLine> chatLines = new ArrayList<>();
        for (ChatTurnRepository.ModelUsage u : chatTurns.sumBySession(sessionId)) {
            chatLines.add(line(u.getModel(), u.getCalls(), u.getInputTokens(), u.getOutputTokens(), 0, unpriced));
        }
        List<ModelLine> scoringLines = new ArrayList<>();
        for (JudgeCallRepository.ModelUsage u : judgeCalls.sumBySession(sessionId)) {
            scoringLines.add(line(u.getModel(), u.getCalls(), u.getInputTokens(), u.getOutputTokens(), u.getCachedTokens(), unpriced));
        }
        Part chat = part(chatLines);
        Part scoring = part(scoringLines);
        long n = attempts.countBySession(sessionId);
        double total = round(chat.usd() + scoring.usd());
        return new SessionAiCostResponse(sessionId, n, total, n == 0 ? null : round(total / n), chat, scoring, List.copyOf(unpriced));
    }

    private ModelLine line(String model, long calls, long in, long out, long cached, TreeSet<String> unpriced) {
        String name = model == null || model.isBlank() ? "(모름)" : model;
        PairProperties.Price p = pricing.get(name);
        if (p == null) unpriced.add(name);
        Double usd = p == null ? null : round((in * p.input() + out * p.output()) / PER_TOKEN);
        return new ModelLine(name, calls, in, out, cached, usd);
    }

    private static Part part(List<ModelLine> lines) {
        lines.sort(Comparator.comparing((ModelLine l) -> l.usd() == null ? -1 : l.usd()).reversed());
        long calls = 0, in = 0, out = 0, cached = 0;
        double usd = 0;
        for (ModelLine l : lines) {
            calls += l.calls();
            in += l.inputTokens();
            out += l.outputTokens();
            cached += l.cachedTokens();
            if (l.usd() != null) usd += l.usd();
        }
        return new Part(calls, in, out, cached, round(usd), List.copyOf(lines));
    }

    /** 소수점 넷째 자리(0.0001달러)까지 */
    static double round(double usd) {
        return Math.round(usd * 10_000) / 10_000.0;
    }
}
