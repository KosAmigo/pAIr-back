package com.pair.domain.task.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.pair.domain.task.service.TaskMaterialService.PdfFile;

/** PDF 자료 응답 헤더(응시용·관리자용 공통) */
public final class MaterialResponses {

    private MaterialResponses() {}

    public static ResponseEntity<byte[]> pdf(PdfFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encode(f.fileName()))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(f.bytes().length)
                .body(f.bytes());
    }

    /** RFC 5987 퍼센트 인코딩 (공백은 %20) */
    private static String encode(String name) {
        return java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
