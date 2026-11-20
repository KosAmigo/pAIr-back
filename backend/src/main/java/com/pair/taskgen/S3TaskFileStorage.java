package com.pair.taskgen;

import java.util.Optional;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pair.exam.service.S3Clients;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * 과제 PDF 를 S3 에 저장한다. pair.taskgen.file-storage=s3 로 켠다. 접속 값(bucket, region, prefix, endpoint)은
 * 회차 저장소와 같은 pair.exam.s3.* 를 쓰고, 자격 증명은 AWS 기본 체인을 쓴다. 객체는 SSE-S3 로 암호화한다.
 */
@Component
@ConditionalOnProperty(name = "pair.taskgen.file-storage", havingValue = "s3")
public class S3TaskFileStorage implements TaskFileStorage, DisposableBean {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3TaskFileStorage(
            @Value("${pair.exam.s3.bucket}") String bucket,
            @Value("${pair.exam.s3.region:ap-northeast-2}") String region,
            @Value("${pair.exam.s3.prefix:}") String prefix,
            @Value("${pair.exam.s3.endpoint:}") String endpoint,
            // 로컬 개발용. 둘 다 비우면 AWS 기본 방식을 쓴다
            @Value("${pair.exam.s3.access-key:}") String accessKey,
            @Value("${pair.exam.s3.secret-key:}") String secretKey) {
        this(S3Clients.build(region, endpoint, accessKey, secretKey), bucket, prefix);
    }

    S3TaskFileStorage(S3Client s3, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("S3 버킷 이름이 비어 있습니다. PAIR_S3_BUCKET(pair.exam.s3.bucket)을 설정하세요.");
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isEmpty() || prefix.endsWith("/") ? (prefix == null ? "" : prefix) : prefix + "/";
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey(key)).contentType("application/pdf")
                .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
    }

    String objectKey(String key) {
        TaskFileStorage.requireValid(key);
        return prefix + key;
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
