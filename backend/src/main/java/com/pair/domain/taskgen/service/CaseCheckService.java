package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 정답 쪽이 설계도의 사례 목록(cases)만 구현했는지(M2). 설계도가 숨은 테스트·문항이 확인할 사례와 그 근거(자료의 사실·예)를 정해 두므로,
 * 목록에 없는 테스트 메서드나 문항은 자료에 근거가 없는 동작을 요구하는 것이다(예: 자료에 없는 upcoming(0)==[]).
 * 한 메서드 안의 단정이 근거가 있는지는 관문의 "테스트 근거" 점검이 본다. BIZ 는 문항 글 단계의 BizQuestionService.check 가 같은 일을 한다.
 */
final class CaseCheckService {

    private CaseCheckService() {}

    /** DEV: 숨은 테스트 @Test 메서드 이름들이 사례 name 들과 같아야 한다 */
    static GenCheckResultDto dev(BlueprintDto b, List<DevDraftDto.FileDraft> hiddenTests) {
        Set<String> want = new LinkedHashSet<>(b.cases().stream().map(BlueprintDto.Case::name).toList());
        Set<String> have = new LinkedHashSet<>(GenTextService.testNames(hiddenTests));
        List<String> missing = want.stream().filter(n -> !have.contains(n)).toList();
        List<String> extra = have.stream().filter(n -> !want.contains(n)).toList();
        return result(missing, extra, "숨은 테스트 메서드");
    }

    private static final java.util.regex.Pattern ADDED = java.util.regex.Pattern.compile("^\\s*추가\\s*:\\s*(?:[A-Za-z_][A-Za-z0-9_<>\\[\\]]*\\.)?([A-Za-z_]\\w*)\\s*\\(");

    /** DEV: 설계도 api 에서 "추가:" 로 적은 메서드를 숨은 테스트가 한 번 이상 호출해야 한다(안 부르면 추가 기능을 구현했는지 검증되지 않는다) */
    static GenCheckResultDto addedCalled(BlueprintDto b, List<DevDraftDto.FileDraft> hiddenTests) {
        StringBuilder src = new StringBuilder();
        for (var f : hiddenTests == null ? List.<DevDraftDto.FileDraft>of() : hiddenTests) if (f != null && f.content() != null) src.append(f.content()).append('\n');
        List<String> notCalled = new ArrayList<>();
        for (String api : b.api()) {
            var m = ADDED.matcher(api);
            if (!m.find()) continue;
            String name = m.group(1);
            if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\s*\\(").matcher(src).find()) notCalled.add(name + "()");
        }
        if (notCalled.isEmpty()) return new GenCheckResultDto(TaskPipelineService.ADDED_CALLED_CHECK, true, "추가하는 메서드를 숨은 테스트가 모두 호출합니다.");
        return new GenCheckResultDto(TaskPipelineService.ADDED_CALLED_CHECK, false, "숨은 테스트가 호출하지 않는 추가 메서드: " + notCalled
                + ". 응시자가 새로 만드는 메서드를 숨은 테스트가 직접 호출해 단정해야 구현 여부를 가릴 수 있습니다(설계도 사례의 기대 결과로).");
    }

    /** DEV: 설계도 "추가:" 메서드가 모두 시작 코드에 설계도 시그니처 그대로 틀(UnsupportedOperationException)로 있어야 한다(없으면 숨은 테스트가 컴파일되지 않는다) */
    static GenCheckResultDto stubs(BlueprintDto b, List<DevDraftDto.FileDraft> code) {
        List<String> bad = new ArrayList<>();
        for (String api : b.api()) {
            String p = FeatureCheckService.stubProblem(api, code);
            if (p != null) bad.add(p);
        }
        if (bad.isEmpty()) return new GenCheckResultDto(TaskPipelineService.STUB_CHECK, true, "추가하는 메서드가 모두 시작 코드에 틀로 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.STUB_CHECK, false, "시작 코드의 '추가:' 메서드 문제: " + String.join(" / ", bad)
                + ". 설계도 시그니처 그대로 선언하고 본문은 throw new UnsupportedOperationException(\"TODO\"); 한 줄로 둡니다.");
    }

    private static GenCheckResultDto result(List<String> missing, List<String> extra, String what) {
        if (missing.isEmpty() && extra.isEmpty()) return new GenCheckResultDto(TaskPipelineService.CASES_CHECK, true, what + "이 설계도 사례와 같습니다.");
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) parts.add("설계도 사례에 있는데 없음: " + missing);
        if (!extra.isEmpty()) parts.add("설계도 사례에 없는데 있음(자료에 근거 없는 동작): " + extra);
        return new GenCheckResultDto(TaskPipelineService.CASES_CHECK, false, what + " 확인: " + String.join(" / ", parts) + ". 설계도의 사례(cases)만 구현하세요.");
    }
}
