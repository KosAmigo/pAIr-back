package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.infra.sandbox.CodeRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEV: 응시자가 추가할 메서드(시작 코드에 틀로 있는 메서드)를 정답 위에 "상수를 돌려주는 가짜 구현"으로 바꿔 숨은 테스트를 돌린다.
 * 가짜로 바꿔도 숨은 테스트가 모두 통과하면 그 메서드의 구현을 검증하지 못하는 것이다(예: isExpired 를 return true; 로 바꿔도 통과).
 * 가짜를 만들기 어려운 반환 형(void, 복잡한 객체)은 건너뛴다.
 */
final class FakeImplService {

    private static final Pattern DECL = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized)\\s+)*([\\w<>\\[\\],.? ]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{;]*)?\\{");

    private FakeImplService() {}

    /** 가짜 하나: 어느 메서드를 어떤 값으로 바꿨는지, 바꾼 파일 경로와 내용 */
    record Fake(String method, String constant, String path, String content) {}

    /** 반환 형에 맞는 상수 식들(만들 수 없으면 빈 목록). boolean 은 true, false 둘 다 */
    static List<String> constants(String returnType) {
        String t = returnType.strip().replaceAll("\\s+", "");
        return switch (t) {
            case "boolean", "Boolean" -> List.of("true", "false");
            case "int", "short", "byte", "Integer", "Short", "Byte" -> List.of("0");
            case "long", "Long" -> List.of("0L");
            case "double", "Double" -> List.of("0.0");
            case "float", "Float" -> List.of("0f");
            case "String" -> List.of("\"\"");
            case "BigDecimal", "java.math.BigDecimal" -> List.of("java.math.BigDecimal.ZERO");
            default -> {
                if (t.matches("(java\\.util\\.)?List<.*>")) yield List.of("java.util.List.of()");
                if (t.matches("(java\\.util\\.)?Set<.*>")) yield List.of("java.util.Set.of()");
                if (t.matches("(java\\.util\\.)?Map<.*>")) yield List.of("java.util.Map.of()");
                if (t.matches("(java\\.util\\.)?(Collection|Iterable)<.*>")) yield List.of("java.util.List.of()");
                if (t.matches("(java\\.util\\.)?Optional<.*>")) yield List.of("java.util.Optional.empty()");
                yield List.of();
            }
        };
    }

    /** 시작 코드에서 틀(본문이 예외만 던짐)인 메서드마다, 정답 파일의 같은 메서드 본문을 상수 반환으로 바꾼 가짜들 */
    static List<Fake> fakes(DevDraftDto d) {
        List<Fake> out = new ArrayList<>();
        Map<String, String> solution = DevRunService.toMap(d.solution());
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f == null || f.path() == null || f.content() == null || !f.path().startsWith("src/") || !solution.containsKey(f.path())) continue;
            Matcher m = DECL.matcher(f.content());
            while (m.find()) {
                String body = FeatureCheckService.bodyOf(f.content(), m.end());
                if (body == null || !FeatureCheckService.isStub(body)) continue;
                String name = m.group(2);
                String params = FeatureCheckService.paramTypes(m.group(3)).toString();
                for (String constant : constants(m.group(1))) {
                    String replaced = replaceBody(solution.get(f.path()), name, params, constant);
                    if (replaced != null) out.add(new Fake(name, constant, f.path(), replaced));
                }
            }
        }
        return out;
    }

    /** 정답 파일에서 같은 이름·매개변수 타입의 메서드 본문을 "return 상수;" 로 바꾼 내용(못 찾으면 null) */
    static String replaceBody(String source, String name, String paramTypes, String constant) {
        Matcher m = DECL.matcher(source);
        while (m.find()) {
            if (!m.group(2).equals(name) || !FeatureCheckService.paramTypes(m.group(3)).toString().equals(paramTypes)) continue;
            String body = FeatureCheckService.bodyOf(source, m.end());
            if (body == null) return null;
            return source.substring(0, m.end()) + " return " + constant + "; " + source.substring(m.end() + body.length());
        }
        return null;
    }

    /**
     * 가짜마다 숨은 테스트를 돌려 모두 통과하면 문제로 모은다. 컴파일이 안 되는 가짜(이 검사로는 알 수 없음)는 건너뛴다.
     * 반환: 문제 설명 목록(없으면 빈 목록)
     */
    static List<String> problems(CodeRunner runner, DevDraftDto d) {
        List<String> bad = new ArrayList<>();
        for (Fake fake : fakes(d)) {
            Map<String, String> sources = new LinkedHashMap<>(DevRunService.toMap(d.files()));
            sources.putAll(DevRunService.toMap(d.solution()));
            sources.put(fake.path(), fake.content());
            sources.putAll(DevRunService.toMap(d.hiddenTests()));
            CodeRunner.RunResult r = runner.run(sources);
            if (!r.compiled()) continue;
            if (r.failed() == 0) bad.add(fake.method() + "() 를 `return " + fake.constant() + ";` 로 바꿔도 숨은 테스트가 모두 통과합니다");
        }
        return bad;
    }
}
