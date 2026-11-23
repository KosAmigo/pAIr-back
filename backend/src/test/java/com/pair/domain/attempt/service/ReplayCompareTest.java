package com.pair.domain.attempt.service;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

/** 두 풀이의 감점 근거 비교: 어느 체크가 감점에서 통과로 바뀌었는지 항목·체크별로 맞춘다 */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class ReplayCompareTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @MockitoBean JavaMailSender mail;
    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired ReplayCompareService compare;
    @Autowired TaskRepository taskRecords;
    @Autowired TaskFileRepository taskFiles;
    @Autowired TaskQuestionRepository taskQuestions;

    AuthResponse owner;
    String a;
    String b;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        owner = signup();
        a = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        b = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        // A: C1 감점(-3), C2 통과 / B: C1 통과, C2 통과, C3 는 B 에만 있다
        finish(a, report(12, check("C1", "AI 제안을 검증 없이 적용", "DEDUCTED", 3, "테스트 없이 적용", List.of("#T03")), check("C2", "원인 설명", "PASSED", 0, null, List.of())));
        finish(b, report(18, check("C1", "AI 제안을 검증 없이 적용", "PASSED", 0, null, List.of("테스트 실행")), check("C2", "원인 설명", "PASSED", 0, null, List.of()),
                check("C3", "추가 체크", "SKIPPED", 0, null, List.of())));
    }

    private AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
    }

    private static Map<String, Object> check(String code, String label, String status, int deducted, String reason, List<String> evidence) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("method", "JUDGE");
        m.put("points", 3);
        m.put("deducted", deducted);
        m.put("status", status);
        m.put("evidence", evidence);
        m.put("reason", reason);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> report(int score, Map<String, Object>... checks) {
        return Map.of("version", 2, "total", score, "max", 20,
                "items", List.of(Map.of("key", "control", "name", "AI 통제력", "max", 20, "score", score, "checks", List.of(checks))));
    }

    private void finish(String id, Map<String, Object> report) {
        Instant t = Instant.now();
        store.save(store.load(id).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(AttemptStateDto.Status.DONE));
        store.writeReport(id, report);
    }

    @Test
    void 점수가_달라진_이유를_체크별로_맞춰_준다() {
        var r = compare.compare(owner.user().id(), a, b);

        assertThat(r.items()).hasSize(1);
        var item = r.items().get(0);
        assertThat(item.name()).isEqualTo("AI 통제력");
        assertThat(item.a()).isEqualTo(12);
        assertThat(item.b()).isEqualTo(18);

        var c1 = item.checks().get(0);
        assertThat(c1.code()).isEqualTo("C1");
        assertThat(c1.changed()).isTrue();
        assertThat(c1.a().status()).isEqualTo("DEDUCTED");
        assertThat(c1.a().deducted()).isEqualTo(3);
        assertThat(c1.a().reason()).isEqualTo("테스트 없이 적용");
        assertThat(c1.a().evidence()).containsExactly("#T03");
        assertThat(c1.b().status()).isEqualTo("PASSED");

        // 같은 결과면 changed 가 아니다
        assertThat(item.checks().get(1).changed()).isFalse();
        // B 에만 있는 체크는 a 가 null
        var c3 = item.checks().get(2);
        assertThat(c3.code()).isEqualTo("C3");
        assertThat(c3.a()).isNull();
        assertThat(c3.b().status()).isEqualTo("SKIPPED");
    }

    @Test
    void 채점이_끝나지_않았거나_이전_형식이면_비교할_수_없다() {
        String solving = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        assertThatThrownBy(() -> compare.compare(owner.user().id(), a, solving)).isInstanceOf(IllegalStateException.class);

        store.writeReport(b, Map.of("version", 1, "total", 70));
        assertThatThrownBy(() -> compare.compare(owner.user().id(), a, b)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 남의_응시나_없는_응시는_404로_본다() {
        var other = signup();
        String othersAttempt = attempts.startSelf(TestTasks.SELF_DEV, other.user().id()).id();
        assertThatThrownBy(() -> compare.compare(owner.user().id(), a, othersAttempt)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> compare.compare(owner.user().id(), a, "999999")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> compare.compare(owner.user().id(), a, "abc")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void API_는_주인만_쓸_수_있고_남의_응시를_with_로_넣어도_막힌다() throws Exception {
        String token = "Bearer " + owner.token();
        mvc.perform(get("/api/attempts/{id}/compare", a).param("with", b).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].checks[0].a.status").value("DEDUCTED"))
                .andExpect(jsonPath("$.items[0].checks[0].b.status").value("PASSED"))
                .andExpect(jsonPath("$.items[0].checks[0].changed").value(true));

        mvc.perform(get("/api/attempts/{id}/compare", a).param("with", b)).andExpect(status().isUnauthorized());

        var other = signup();
        String othersAttempt = attempts.startSelf(TestTasks.SELF_DEV, other.user().id()).id();
        mvc.perform(get("/api/attempts/{id}/compare", a).param("with", othersAttempt).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/attempts/{id}/compare", othersAttempt).param("with", a).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}/compare", a).param("with", solvingOf()).header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test
    void 두_풀이에서_내용이_다른_파일만_돌려준다() {
        Map<String, String> files = store.readFiles(a);
        String javaPath = files.keySet().stream().filter(k -> k.endsWith(".java")).sorted().findFirst().orElseThrow();
        store.writeWorkspaceFile(b, javaPath, files.get(javaPath) + "\n// B 에서 고침");
        store.createWorkspaceFile(b, "src/shop/OnlyInB.java");
        store.writeWorkspaceFile(b, "src/shop/OnlyInB.java", "class OnlyInB {}");

        var diffs = compare.files(owner.user().id(), a, b);

        // 원본과 두 풀이가 모두 같은 파일은 빠지고, 고친 파일과 B 에만 있는 파일만 경로 순으로 나온다
        assertThat(diffs).extracting(ReplayCompareService.FileDiff::path).containsExactly(javaPath, "src/shop/OnlyInB.java");
        assertThat(diffs.get(0).base()).isEqualTo(files.get(javaPath));
        assertThat(diffs.get(0).a()).isEqualTo(files.get(javaPath));
        assertThat(diffs.get(0).b()).isEqualTo(files.get(javaPath) + "\n// B 에서 고침");
        // 풀이에서 새로 만든 파일은 원본(base)도 A 도 없다
        assertThat(diffs.get(1).base()).isNull();
        assertThat(diffs.get(1).a()).isNull();
        assertThat(diffs.get(1).b()).isEqualTo("class OnlyInB {}");
    }

    @Test
    void 두_풀이가_같은_코드를_똑같이_고쳐도_원본과_함께_돌려준다() {
        Map<String, String> files = store.readFiles(a);
        String javaPath = files.keySet().stream().filter(k -> k.endsWith(".java")).sorted().findFirst().orElseThrow();
        String edited = files.get(javaPath) + "\n// 둘 다 같게 고침";
        store.writeWorkspaceFile(a, javaPath, edited);
        store.writeWorkspaceFile(b, javaPath, edited);

        var diffs = compare.files(owner.user().id(), a, b);

        // A 와 B 가 같아도 원본과 다르면 나온다: 각 풀이가 원본에서 무엇을 바꿨는지 보여 주기 위해서
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo(javaPath);
        assertThat(diffs.get(0).base()).isEqualTo(files.get(javaPath));
        assertThat(diffs.get(0).a()).isEqualTo(edited);
        assertThat(diffs.get(0).b()).isEqualTo(edited);
    }

    @Test
    void 다른_과제의_풀이끼리는_비교할_수_없다() {
        String biz = attempts.startSelf(TestTasks.SELF_BIZ, owner.user().id()).id();
        assertThatThrownBy(() -> compare.files(owner.user().id(), a, biz)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 파일이_모두_같으면_빈_목록이고_채점이_안_끝나도_비교할_수_있다() {
        String solving = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        assertThat(compare.files(owner.user().id(), a, b)).isEmpty();
        assertThat(compare.files(owner.user().id(), a, solving)).isEmpty();
    }

    @Test
    void 파일_비교_API_는_주인만_쓸_수_있다() throws Exception {
        String token = "Bearer " + owner.token();
        String path = store.readFiles(b).keySet().stream().filter(k -> k.endsWith(".java")).sorted().findFirst().orElseThrow();
        store.writeWorkspaceFile(b, path, "// 고침");
        mvc.perform(get("/api/attempts/{id}/compare/files", a).param("with", b).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value(path))
                .andExpect(jsonPath("$[0].base").exists())
                .andExpect(jsonPath("$[0].b").value("// 고침"));

        mvc.perform(get("/api/attempts/{id}/compare/files", a).param("with", b)).andExpect(status().isUnauthorized());
        var other = signup();
        String othersAttempt = attempts.startSelf(TestTasks.SELF_DEV, other.user().id()).id();
        mvc.perform(get("/api/attempts/{id}/compare/files", a).param("with", othersAttempt).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/attempts/{id}/compare/files", othersAttempt).param("with", a).header("Authorization", token)).andExpect(status().isForbidden());
    }

    private String solvingOf() {
        return attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
    }
}
