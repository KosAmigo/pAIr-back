package com.pair.domain.question.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.question.service.QuestionService;

@RestController
@RequestMapping("/api")
public class QuestionController {

    private final QuestionService questions;

    public QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    /** 풀이 화면의 문항(객관식·단답형)과 저장한 답. 정답 정보는 내보내지 않는다 */
    @GetMapping("/attempts/{id}/questions")
    public List<com.pair.domain.question.service.QuestionService.QuestionView> questions(@PathVariable String id) {
        return questions.list(Long.parseLong(id));
    }

    /** 문항 답 저장(덮어쓰기). 빈 답은 답 지우기. 풀이 중에만 */
    @PutMapping("/attempts/{id}/questions/{questionId}/answer")
    public org.springframework.http.ResponseEntity<String> answer(@PathVariable String id, @PathVariable Long questionId,
            @RequestBody com.pair.domain.question.service.QuestionService.AnswerRequest req) throws com.fasterxml.jackson.core.JsonProcessingException {
        // 답 지우기(빈 답)는 JSON null 을 돌려준다
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(questions.answer(Long.parseLong(id), questionId, req));
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }
}
