package com.pair.global.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pair")
public record PairProperties(
        String dataDir,
        Assistant assistant,
        Judge judge,
        String claudeAssistantModel,
        Run run,
        Grader grader,
        java.util.Map<String, Price> pricing) {

    /**
     * dataDir 와 grader.dir 같은 상대 경로는 저장소 최상위 폴더(pAIr-back) 기준으로 바꾼다.
     * IntelliJ 처럼 backend 폴더에서 실행해도 backend/grader, backend/pilot-data 가 새로 생기지 않게 한다.
     */
    public PairProperties {
        dataDir = fromRepoRoot(dataDir);
        if (grader != null) grader = new Grader(fromRepoRoot(grader.dir()), grader.parallel());
    }

    /** 실행 폴더에서 위로 올라가며 backend 와 grader 폴더가 함께 있는 곳(저장소 최상위)을 찾아 그 기준 경로를 돌려준다. */
    static String fromRepoRoot(String dir) {
        if (dir == null || java.nio.file.Path.of(dir).isAbsolute()) return dir;
        for (java.nio.file.Path base = java.nio.file.Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            if (java.nio.file.Files.isDirectory(base.resolve("backend")) && java.nio.file.Files.isDirectory(base.resolve("grader"))) {
                return base.resolve(dir).toString();
            }
        }
        return dir;
    }

    public record Assistant(String provider, String model, long maxOutputTokens, String reasoningEffort, int maxTurns, int maxInputChars, String systemPrompt) {}

    /**
     * 이중 채점: 판정 체크는 두 모델의 감점을 평균한다.
     * referenceDocs: 비개발 채점에 제공 자료를 넣는 방식.
     *   auto(기본): 자료 전체 글자 수가 referenceDocsFullMaxChars 이하면 full, 넘으면 rag
     *   full: 자료를 통째로(프롬프트 캐시 앞부분에 넣음), rag: 제출물 문장으로 검색한 조각만(숫자 표는 통째로)
     * referenceDocsFullMaxChars: auto 에서 full 로 갈 자료 글자 수 상한(기본 12000)
     */
    public record Judge(Model claude, Model openai, String referenceDocs, Integer referenceDocsFullMaxChars) {
        public static final int DEFAULT_FULL_MAX_CHARS = 12000;

        /** auto | full | rag. 비었거나 모르는 값이면 auto */
        public String referenceMode() {
            if ("full".equalsIgnoreCase(referenceDocs)) return "full";
            if ("rag".equalsIgnoreCase(referenceDocs)) return "rag";
            return "auto";
        }

        public int fullMaxChars() {
            return referenceDocsFullMaxChars == null || referenceDocsFullMaxChars < 0 ? DEFAULT_FULL_MAX_CHARS : referenceDocsFullMaxChars;
        }
    }

    /**
     * effort: OpenAI 는 reasoning_effort(모델마다 다름. gpt-6 계열은 none/low/medium/high/xhigh, minimal 없음). Claude 는 output_config.effort(low/medium/high/xhigh/max).
     * calls: 채점 1건에 이 모델을 부르는 횟수(기본 1). 모든 호출의 판정을 합쳐 다수결로 정한다.
     */
    public record Model(String model, String effort, long maxTokens, int calls) {
        public int callsOrOne() {
            return Math.max(1, calls);
        }
    }

    /** docker 로 시작하는 값은 pair.run.mode=docker 일 때만 쓴다. maxConcurrent 는 동시에 돌 컨테이너 수 상한, 넘으면 queueWaitSeconds 까지 줄을 선다. */
    public record Run(int timeoutSeconds, String dockerBin, String dockerImage, int maxConcurrent, int queueWaitSeconds) {
        @ConstructorBinding
        public Run {
            if (dockerBin == null || dockerBin.isBlank()) dockerBin = "docker";
            if (dockerImage == null || dockerImage.isBlank()) dockerImage = "pair-sandbox";
            if (maxConcurrent <= 0) maxConcurrent = 4;
            if (queueWaitSeconds <= 0) queueWaitSeconds = 60;
        }

        public Run(int timeoutSeconds) {
            this(timeoutSeconds, null, null, 0, 0);
        }
    }

    /** 채점 툴. dir: 프롬프트 버전과 샘플 응시 폴더(git 공유). parallel: 동시에 채점할 개수. */
    public record Grader(String dir, int parallel) {}

    /** 모델 가격 ($/1M 토큰). 채점 예상 비용 계산에 쓴다. */
    public record Price(double input, double output) {}
}
