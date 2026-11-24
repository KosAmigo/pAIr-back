package com.pair.domain.task;

import com.pair.global.config.ExamIntegrationTest;

import com.pair.domain.task.entity.QuestionChoice;
import com.pair.domain.task.entity.QuestionSource;
import com.pair.domain.task.entity.ShortAnswerKey;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.DocumentPageRepository;
import com.pair.domain.task.repository.QuestionChoiceRepository;
import com.pair.domain.task.repository.QuestionSourceRepository;
import com.pair.domain.task.repository.ShortAnswerKeyRepository;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.infra.storage.TaskFileStorage;
import com.pair.global.security.HeaderCurrentUserResolver;

/** 관리자 검수용 파일·문항 API: ADMIN 만, 숨긴(HIDDEN) 과제도 보이고 숨은 파일·정답까지 나간다 */
class TaskReviewConsoleTest extends ExamIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TaskRepository tasks;
    @Autowired TaskFileRepository files;
    @Autowired DocumentPageRepository pages;
    @Autowired TaskFileStorage storage;
    @Autowired TaskQuestionRepository questions;
    @Autowired QuestionChoiceRepository choices;
    @Autowired ShortAnswerKeyRepository keys;
    @Autowired QuestionSourceRepository sources;

    @BeforeEach
    void setUp() {
        MaterialFixtures.seed(tasks, files, pages, storage);
        files.save(new TaskFile(2L, "answer.md", "정답 설명", "text/markdown", 9, TaskFile.HIDDEN, true));
        files.save(new TaskFile(2L, "README.md", "안내", "text/markdown", 6, TaskFile.VISIBLE, true));
        Task t = tasks.findByCode(TestTasks.SELF_BIZ).orElseThrow();
        t.changeStatus(Task.Status.HIDDEN, Instant.now());
        tasks.save(t);

        for (long task : new long[] {1L, 2L}) {
            for (String stage : new String[] {"TASK", "FOLLOWUP"}) {
                questions.findByTaskIdAndStageOrderBySeqAsc(task, stage).forEach(q -> {
                    sources.deleteAll(sources.findByQuestionId(q.getId()));
                    choices.deleteAll(choices.findByQuestionIdOrderByIdAsc(q.getId()));
                    keys.deleteAll(keys.findByQuestionIdOrderByIdAsc(q.getId()));
                    questions.delete(q);
                });
            }
        }
        TaskQuestion single = question("TASK", "SINGLE", 1, 5);
        choices.save(new QuestionChoice(single.getId(), 1, "A안", false));
        choices.save(new QuestionChoice(single.getId(), 2, "B안", true));
        sources.save(new QuestionSource(single.getId(),
                files.findByTaskIdAndPath(2L, MaterialFixtures.PDF_PATH).orElseThrow().getId(), "1,2"));
        TaskQuestion shortQ = question("TASK", "SHORT", 2, 3);
        keys.save(new ShortAnswerKey(shortQ.getId(), "8430"));
        keys.save(new ShortAnswerKey(shortQ.getId(), "8,430"));
        question("FOLLOWUP", "ESSAY", 1, null);
        TaskQuestion off = question("TASK", "SHORT", 3, 3);
        off.setActive(false);
        questions.save(off);
    }

    private TaskQuestion question(String stage, String type, int seq, Integer points) {
        TaskQuestion q = new TaskQuestion(2L, stage, type, seq, type + " 문항 " + seq);
        q.setPoints(points);
        return questions.save(q);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role);
        if (role.equals("MANAGER")) b.header(HeaderCurrentUserResolver.COMPANY_ID, 601);
        return b;
    }

    @Test
    void 파일은_숨은_파일까지_보이는_것_먼저_경로_순으로_나온다() throws Exception {
        mvc.perform(as(get("/api/console/tasks/pricing-plan/files"), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].visibility", contains("VISIBLE", "VISIBLE", "VISIBLE", "VISIBLE", "HIDDEN", "HIDDEN")))
                .andExpect(jsonPath("$[0].path").value("README.md")).andExpect(jsonPath("$[0].content").value("안내"))
                .andExpect(jsonPath("$[0].readOnly").value(true))
                // PDF 는 content 가 null
                .andExpect(jsonPath("$[?(@.path == '" + MaterialFixtures.PDF_PATH + "')].content", contains((Object) null)))
                .andExpect(jsonPath("$[?(@.path == 'answer.md')].visibility", contains("HIDDEN")))
                .andExpect(jsonPath("$[?(@.path == 'answer.md')].content", contains("정답 설명")));
    }

    @Test
    void 문항은_정답과_근거까지_나오고_후속_질문과_꺼진_문항은_빠진다() throws Exception {
        mvc.perform(as(get("/api/console/tasks/pricing-plan/questions"), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].type").value("SINGLE")).andExpect(jsonPath("$[0].points").value(5))
                .andExpect(jsonPath("$[0].choices", hasSize(2))).andExpect(jsonPath("$[0].choices[0].correct").value(false))
                .andExpect(jsonPath("$[0].choices[1].correct").value(true)).andExpect(jsonPath("$[0].answers", hasSize(0)))
                .andExpect(jsonPath("$[0].sources[0].path").value(MaterialFixtures.PDF_PATH))
                .andExpect(jsonPath("$[0].sources[0].pages").value("1,2"))
                .andExpect(jsonPath("$[1].type").value("SHORT")).andExpect(jsonPath("$[1].choices", hasSize(0)))
                .andExpect(jsonPath("$[1].answers", contains("8430", "8,430"))).andExpect(jsonPath("$[1].sources", hasSize(0)));
    }

    @Test
    void 관리자_말고는_403_없는_과제는_404() throws Exception {
        for (String role : new String[] {"MANAGER", "USER"}) {
            mvc.perform(as(get("/api/console/tasks/pricing-plan/files"), role)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/console/tasks/pricing-plan/questions"), role)).andExpect(status().isForbidden());
        }
        mvc.perform(as(get("/api/console/tasks/nope/files"), "ADMIN")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/console/tasks/nope/questions"), "ADMIN")).andExpect(status().isNotFound());
    }
}
