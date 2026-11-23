package com.pair.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    @RestController
    static class SampleController {
        @GetMapping("/sample")
        String get() {
            return "ok";
        }

        @PostMapping(value = "/sample", consumes = MediaType.APPLICATION_JSON_VALUE)
        String post(@RequestBody Map<String, String> body) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SampleController()).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    @DisplayName("없는 메서드로 부르면 500 이 아니라 405 와 Allow 헤더")
    void methodNotAllowed() throws Exception {
        mvc.perform(delete("/sample"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.message").value("지원하지 않는 요청 방식입니다: DELETE"));
    }

    @Test
    @DisplayName("본문 형식이 다르면 415")
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/sample").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
    }
}
