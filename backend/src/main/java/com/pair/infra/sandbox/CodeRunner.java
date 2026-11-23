package com.pair.infra.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 응시자 코드를 컴파일하고 테스트를 돌리는 방법. 구현은 설정으로 바꿀 수 있다.
 * 지금은 서버의 별도 JVM 에서 실행하는 {@link LocalJvmRunner} 하나다.
 * 응시 서비스와 채점은 이 인터페이스만 알고, 코드가 어디서 도는지는 모른다.
 */
public interface CodeRunner {

    record TestCase(String name, boolean passed, String message) {}

    /** 컴파일 오류/경고 한 건. file 은 응시 작업 폴더 기준 상대 경로. */
    record CompileDiagnostic(String file, long line, long column, String severity, String message) {}

    record BuildResult(boolean compiled, List<String> lines, List<CompileDiagnostic> diagnostics, long millis) {}

    record RunResult(boolean compiled, List<String> lines, int passed, int failed,
                     List<TestCase> tests, List<CompileDiagnostic> diagnostics, long millis) {
        public boolean allPassed() {
            return compiled && failed == 0 && passed > 0;
        }
    }

    /**
     * 컴파일한 뒤 테스트를 돌린다.
     *
     * @param sources 컴파일할 .java 파일들 (상대 경로 → 내용)
     */
    RunResult run(Map<String, String> sources);

    /** 컴파일만 한다 (빌드 버튼). 테스트는 돌리지 않는다. */
    BuildResult build(Map<String, String> sources);

    /** 결과를 화면/로그용으로 요약. */
    static Map<String, Object> summary(RunResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compiled", r.compiled());
        m.put("passed", r.passed());
        m.put("failed", r.failed());
        m.put("millis", r.millis());
        return m;
    }
}
