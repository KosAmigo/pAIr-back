package com.pair.domain.task;

import com.pair.global.config.BearerIntegrationTest;

import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.task.repository.DocumentPageRepository;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskRepository;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.infra.storage.TaskFileStorage;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

/** 응시자 PDF 자료 API (목록·파일·쪽 글). 로컬 저장소(임시 폴더)를 쓰고 AI 호출은 없다 */
class TaskMaterialApiTest extends BearerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptRepository attemptRecords;
    @Autowired TaskRepository tasks;
    @Autowired TaskFileRepository files;
    @Autowired DocumentPageRepository pages;
    @Autowired TaskFileStorage storage;

    AuthResponse owner;
    AuthResponse other;
    String id;

    @BeforeEach
    void setUp() {
        MaterialFixtures.seed(tasks, files, pages, storage);
        owner = AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
        other = AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
        id = attempts.startSelf(TestTasks.SELF_BIZ, owner.user().id()).id();
    }

    private ResultActions call(String sub, AuthResponse who, String path, String pagesSpec) throws Exception {
        var req = get("/api/attempts/{id}/materials" + sub, id);
        if (who != null) req.header("Authorization", "Bearer " + who.token());
        if (path != null) req.param("path", path);
        if (pagesSpec != null) req.param("pages", pagesSpec);
        return mvc.perform(req);
    }

    @Test
    void 주인은_PDF_목록만_본다_텍스트와_숨은_파일은_없다() throws Exception {
        call("", owner, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].path").value(MaterialFixtures.PDF2_PATH))
                .andExpect(jsonPath("$[0].pages").value(1))
                .andExpect(jsonPath("$[1].path").value(MaterialFixtures.PDF_PATH))
                .andExpect(jsonPath("$[1].mimeType").value("application/pdf"))
                .andExpect(jsonPath("$[1].sizeBytes").value(MaterialFixtures.BYTES.length))
                .andExpect(jsonPath("$[1].pages").value(25));
    }

    @Test
    void 남의_응시는_403_로그인_없으면_401() throws Exception {
        call("", other, null, null).andExpect(status().isForbidden());
        call("/file", other, MaterialFixtures.PDF_PATH, null).andExpect(status().isForbidden());
        call("/pages", other, MaterialFixtures.PDF_PATH, null).andExpect(status().isForbidden());
        call("", null, null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void 실전_응시는_원본_과제의_자료를_쓴다() throws Exception {
        // 과제 4(실전 복사본, 원본 2). 실전 응시는 로그인 없이 들어온다
        Attempt live = attemptRecords.save(Attempt.live(null, 4L, "m", Instant.now()));
        mvc.perform(get("/api/attempts/{id}/materials", live.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/attempts/{id}/materials/pages", live.getId()).param("path", MaterialFixtures.PDF2_PATH))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("비교 1쪽"));
        mvc.perform(get("/api/attempts/{id}/materials/file", live.getId()).param("path", MaterialFixtures.PDF_PATH))
                .andExpect(status().isOk()).andExpect(content().bytes(MaterialFixtures.BYTES));
    }

    @Test
    void 파일은_PDF_헤더와_한글_이름을_준다() throws Exception {
        call("/file", owner, MaterialFixtures.PDF_PATH, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''%EC%9A%94%EA%B8%88%20%EC%95%88%EB%82%B4.pdf"))
                .andExpect(header().string("Cache-Control", "max-age=300, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(MaterialFixtures.BYTES));
    }

    @Test
    void 목록에_없는_경로는_모두_404() throws Exception {
        for (String path : new String[] {"../../x.pdf", "자료/../자료/요금 안내.pdf", MaterialFixtures.HIDDEN_PATH,
                MaterialFixtures.OTHER_PATH, "자료/표.csv", "없는.pdf"}) {
            call("/file", owner, path, null).andExpect(status().isNotFound());
            call("/pages", owner, path, null).andExpect(status().isNotFound());
        }
    }

    @Test
    void 저장소에_파일이_없으면_404() throws Exception {
        storage.delete(TaskFileStorage.keyOf(2L, 1));
        call("/file", owner, MaterialFixtures.PDF_PATH, null).andExpect(status().isNotFound());
    }

    @Test
    void 쪽_글은_범위로_고른다() throws Exception {
        call("/pages", owner, MaterialFixtures.PDF_PATH, "1,3-4").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].page").value(1)).andExpect(jsonPath("$[0].text").value("1쪽 글"))
                .andExpect(jsonPath("$[1].page").value(3)).andExpect(jsonPath("$[2].page").value(4));
        call("/pages", owner, MaterialFixtures.PDF_PATH, "4,3,3").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].page").value(3));
        call("/pages", owner, MaterialFixtures.PDF_PATH, "1-20").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(20)));
    }

    @Test
    void 쪽_글을_빼면_전체_다만_20쪽을_넘으면_400() throws Exception {
        call("/pages", owner, MaterialFixtures.PDF2_PATH, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        call("/pages", owner, MaterialFixtures.PDF_PATH, null).andExpect(status().isBadRequest());
    }

    @Test
    void 쪽_범위가_틀리면_400() throws Exception {
        for (String bad : new String[] {"26", "0", "abc", "3-1", "1,,2", "1-", "-3", "1-21", "1-10,11-25", "1;2"}) {
            call("/pages", owner, MaterialFixtures.PDF_PATH, bad).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 제출_뒤에도_읽을_수_있다() throws Exception {
        Attempt r = attemptRecords.findById(Long.parseLong(id)).orElseThrow();
        r.sync(AttemptStateDto.Status.FOLLOWUP, Instant.now(), null);
        attemptRecords.save(r);
        call("", owner, null, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }
}
