package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** companyId: 관리자만 넣는다. 기업 회원은 비워도 되고, 넣어도 로그인한 회사로 바뀐다 */
public record ExamSessionRequest(
        Long companyId,
        @NotBlank @Size(max = 100) String title,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        boolean proctoring,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 64) String> taskIds) {

    public ExamSessionRequest withCompany(Long company) {
        return new ExamSessionRequest(company, title, startsAt, endsAt, proctoring, taskIds);
    }
}
