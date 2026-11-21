package com.pair.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TaskFileRecordRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.AuthService;
import com.pair.web.auth.AuthTestSupport;

/** 오류 밑줄 API: 저장 전 내용을 덮어 분석하고, 저장·기록은 건드리지 않으며, 남의 응시는 막는다 */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class DiagnosticsApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired AttemptService attempts;
    @Autowired AttemptStore store;
    @Autowired AuthService auth;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JavaMailSender mail;

    String token;
    String devId;
    String javaFile;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        var signup = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test");
        token = signup.token();
        devId = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
        javaFile = store.readFiles(devId).keySet().stream().filter(p -> p.startsWith("src/") && p.endsWith(".java")).findFirst().orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions diagnose(String id, String bearer, java.util.Map<String, String> files) throws Exception {
        return mvc.perform(post("/api/attempts/{id}/diagnostics", id).header("Authorization", "Bearer " + bearer)
                .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("files", files))));
    }

    @Test
    void 저장_전_내용을_덮어_분석하고_저장은_하지_않는다() throws Exception {
        String saved = store.readFiles(devId).get(javaFile);
        diagnose(devId, token, java.util.Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.diagnostics").isArray());

        String broken = saved + "\nclass Broken { int x = \"문자\"; }\n";
        diagnose(devId, token, java.util.Map.of(javaFile, broken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics[0].file").value(javaFile))
                .andExpect(jsonPath("$.diagnostics[0].severity").value("error"));

        assertThat(store.readFiles(devId).get(javaFile)).isEqualTo(saved);
    }

    @Test
    void 없는_파일이나_작업_폴더_밖_경로의_덮어쓰기는_무시한다() throws Exception {
        diagnose(devId, token, java.util.Map.of("src/New.java", "class New { int x = \"s\"; }", "../evil/E.java", "class E { int x = \"s\"; }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics[?(@.file=='src/New.java')]").isEmpty())
                .andExpect(jsonPath("$.diagnostics[?(@.file=='../evil/E.java')]").isEmpty());
    }

    @Test
    void 남의_응시는_막는다() throws Exception {
        String other = AuthTestSupport.signup(auth, mail, "남", UUID.randomUUID() + "@pair.test").token();
        diagnose(devId, other, java.util.Map.of()).andExpect(status().is4xxClientError());
    }

    private org.springframework.test.web.servlet.ResultActions cursor(String endpoint, String id, String bearer, String path, String content, int offset) throws Exception {
        return mvc.perform(post("/api/attempts/{id}/" + endpoint, id).header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("files", java.util.Map.of(path, content), "path", path, "offset", offset))));
    }

    private static final String SAMPLE = "package shop;\npublic class Sample {\n    int total(int a) { return a; }\n    void run(Sample s) {\n        s.\n        int v = s.total(1);\n    }\n}\n";

    @Test
    void 자동_완성은_저장_전_내용_기준으로_멤버를_돌려준다() throws Exception {
        cursor("complete", devId, token, javaFile, SAMPLE, SAMPLE.indexOf("s.\n") + 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='total')].kind").value(org.hamcrest.Matchers.hasItem("method")))
                .andExpect(jsonPath("$.items[?(@.name=='total')].hasParams").value(org.hamcrest.Matchers.hasItem(true)));
        // 식 뒤의 점이 아니면 빈 목록
        cursor("complete", devId, token, javaFile, SAMPLE, 3).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 정의로_이동은_작업_파일_안의_선언_위치를_돌려주고_없으면_null() throws Exception {
        cursor("definition", devId, token, javaFile, SAMPLE, SAMPLE.indexOf("s.total") + 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.file").value(javaFile))
                .andExpect(jsonPath("$.definition.line").value(3))
                .andExpect(jsonPath("$.definition.name").value("total"));
        cursor("definition", devId, token, javaFile, SAMPLE, 2).andExpect(status().isOk()).andExpect(jsonPath("$.definition").doesNotExist());
    }

    @Test
    void 자동_완성과_정의는_남의_응시를_막는다() throws Exception {
        String other = AuthTestSupport.signup(auth, mail, "남", UUID.randomUUID() + "@pair.test").token();
        cursor("complete", devId, other, javaFile, SAMPLE, 5).andExpect(status().is4xxClientError());
        cursor("definition", devId, other, javaFile, SAMPLE, 5).andExpect(status().is4xxClientError());
    }
}
