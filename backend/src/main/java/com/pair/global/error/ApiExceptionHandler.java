package com.pair.global.error;

import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pair.infra.ai.AiUnavailableException;

/**
 * 오류를 {"message": "..."} 형태로 돌려준다. 프론트는 이 메시지를 그대로 보여 준다.
 * 응답 형식을 JSON 으로 못박는다: 채팅 스트림처럼 Accept: text/event-stream 만 보낸 요청에서도
 * 오류 본문을 쓸 수 있게(지정하지 않으면 형식 협상에 실패해 본문 없는 500 이 나간다).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> noResource(Exception e) {
        return body(HttpStatus.NOT_FOUND, "없는 경로입니다. 화면은 https://localhost:5173 에서 여세요.");
    }

    @ExceptionHandler(com.pair.global.error.UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(RuntimeException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(com.pair.global.error.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** 이미 등록된 이메일로 가입을 요청한 경우 409를 반환한다. */
    @ExceptionHandler(com.pair.global.error.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(com.pair.domain.credit.exception.InsufficientCreditException.class)
    ResponseEntity<Map<String, String>> insufficientCredit(RuntimeException e) {
        return body(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(com.pair.infra.payment.PaymentUnavailableException.class)
    ResponseEntity<Map<String, String>> paymentUnavailable(RuntimeException e) {
        log.warn("결제 상태 확인 실패: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(com.pair.infra.payment.PaymentRejectedException.class)
    ResponseEntity<Map<String, String>> paymentRejected(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 동시 실행 상한 초과 (AI 과제 생성) */
    @ExceptionHandler(com.pair.domain.taskgen.exception.TooManyJobsException.class)
    ResponseEntity<Map<String, String>> tooManyJobs(RuntimeException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /** 한 IP 에서 너무 많이 요청함 */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<Map<String, String>> tooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("message", e.getMessage()));
    }

    /** 로그인 실패가 너무 많음 */
    @ExceptionHandler(com.pair.domain.auth.exception.TooManyLoginAttemptsException.class)
    ResponseEntity<Map<String, String>> tooManyLogins(com.pair.domain.auth.exception.TooManyLoginAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("message", e.getMessage()));
    }

    /** 같은 응시를 너무 많이 채점함 */
    @ExceptionHandler(com.pair.domain.attempt.exception.TooManyRescoresException.class)
    ResponseEntity<Map<String, String>> tooManyRescores(RuntimeException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /** AI 채팅을 1분에 너무 많이 보냄(1인 또는 서비스 전체) */
    @ExceptionHandler(com.pair.domain.attempt.exception.TooManyChatsException.class)
    ResponseEntity<Map<String, String>> tooManyChats(com.pair.domain.attempt.exception.TooManyChatsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("message", e.getMessage()));
    }

    /** 빌드·실행을 너무 자주 누름 */
    @ExceptionHandler(com.pair.domain.attempt.exception.TooManyRunsException.class)
    ResponseEntity<Map<String, String>> tooManyRuns(RuntimeException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** @Valid 요청 검증 실패, 잘못된 JSON·enum 값, 필수 파라미터 누락 */
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        if (e instanceof org.springframework.web.bind.MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null) {
            var f = m.getBindingResult().getFieldError();
            return body(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다: " + f.getField() + " (" + f.getDefaultMessage() + ")");
        }
        return body(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
    }

    /** 경로는 있는데 메서드가 다름 (예: 취소로 바뀐 옛 DELETE). Allow 헤더로 쓸 수 있는 메서드를 알려 준다 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        var res = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON);
        if (e.getSupportedHttpMethods() != null) res.allow(e.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new));
        return res.body(Map.of("message", "지원하지 않는 요청 방식입니다: " + e.getMethod()));
    }

    /** 요청 본문 형식이 다름 (예: JSON 자리에 form, 파일 업로드 자리에 JSON) */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> unsupportedMediaType(Exception e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(Exception e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다 (최대 2MB).");
    }

    /** 코드 실행 대기열이 꽉 차 자리를 못 얻었다. 잠시 뒤 다시 시도하면 된다 */
    @ExceptionHandler(com.pair.infra.sandbox.SandboxRunner.BusyException.class)
    ResponseEntity<Map<String, String>> runnerBusy(RuntimeException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(AiUnavailableException.class)
    ResponseEntity<Map<String, String>> ai(AiUnavailableException e) {
        log.warn("AI 호출 실패: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(com.pair.infra.mail.MailDeliveryException.class)
    ResponseEntity<Map<String, String>> mailDelivery(com.pair.infra.mail.MailDeliveryException e) {
        log.warn("이메일 발송 실패: {}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "이메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("처리하지 못한 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류: " + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("message", message == null ? status.getReasonPhrase() : message));
    }
}
