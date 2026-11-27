package com.pair.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.global.config.BearerIntegrationTest;
import com.pair.global.config.IpLimitProperties;

/** 실제 경로에 IP별 제한이 걸려 있는지. 틀린 요청(400대)으로 한도를 채워 메일·계정을 만들지 않는다 */
class IpLimitIntegrationTest extends BearerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IpLimitProperties limits;

    @ParameterizedTest
    @CsvSource({
            "/api/auth/email-verifications,mail,198.51.100.31",
            "/api/company-invites/none/email-verifications,mail,198.51.100.32",
            "/api/auth/signup,signup,198.51.100.33",
            "/api/company-invites/accept,signup,198.51.100.34" })
    void publicWritesAreLimitedPerIp(String path, String kind, String ip) throws Exception {
        int limit = kind.equals("mail") ? limits.mail() : limits.signup();
        for (int i = 0; i < limit; i++) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}").header("X-Forwarded-For", ip))
                    .andExpect(status().is(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.greaterThanOrEqualTo(400),
                            org.hamcrest.Matchers.lessThan(429))));
        }
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}").header("X-Forwarded-For", ip))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void companyInquiriesAreLimitedButTemplateDownloadIsNot() throws Exception {
        String ip = "198.51.100.35";
        for (int i = 0; i < limits.inquiry(); i++) {
            mvc.perform(multipart("/api/company-inquiries").header("X-Forwarded-For", ip)).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/company-inquiries").header("X-Forwarded-For", ip)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/company-inquiries/members-template.csv").header("X-Forwarded-For", ip)).andExpect(status().isOk());
    }
}
