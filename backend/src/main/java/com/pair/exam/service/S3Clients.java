package com.pair.exam.service;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * S3 클라이언트를 만드는 공통 도우미(감독 사진·과제 PDF 저장소가 같이 쓴다).
 * access-key 와 secret-key 가 둘 다 있으면 그 키를 쓰고, 둘 다 비면 AWS 기본 방식(환경 변수, ~/.aws, IAM 역할)을 쓴다.
 * 키 값은 로그·오류 메시지에 절대 남기지 않는다.
 */
public final class S3Clients {

    private static final Logger log = LoggerFactory.getLogger(S3Clients.class);

    private S3Clients() {}

    public static S3Client build(String region, String endpoint, String accessKey, String secretKey) {
        S3ClientBuilder b = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials(accessKey, secretKey));
        if (endpoint != null && !endpoint.isBlank()) b = b.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        return b.build();
    }

    public static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        boolean hasAccess = accessKey != null && !accessKey.isBlank();
        boolean hasSecret = secretKey != null && !secretKey.isBlank();
        if (hasAccess != hasSecret) {
            throw new IllegalStateException("S3 access-key와 secret-key를 둘 다 넣거나 둘 다 비우세요.");
        }
        if (hasAccess) {
            log.info("S3 접속: 설정 파일의 정적 키를 사용합니다.");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.trim(), secretKey.trim()));
        }
        log.info("S3 접속: AWS 기본 자격 증명 체인을 사용합니다.");
        return DefaultCredentialsProvider.builder().build();
    }
}
