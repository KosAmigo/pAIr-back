package com.pair.exam.service;

import java.util.Optional;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * 감독 사진과 대상자 CSV 원본을 S3 에 저장한다. pair.exam.snapshot-storage=s3 로 켠다.
 * 자격 증명은 코드·설정 파일에 두지 않는다. AWS 기본 체인(환경 변수 AWS_ACCESS_KEY_ID 등, EC2/ECS 역할)을 쓴다.
 * 버킷은 비공개로 두고, 사진은 콘솔 API(/snapshot)를 거쳐서만 보여 준다.
 * 키에 폴더(exam-snapshots/, participant-csv/)가 들어 있어 prefix 는 보통 비워 둔다.
 * 객체는 서버 측 암호화(SSE-S3)로 저장한다.
 */
@Component
@ConditionalOnProperty(name = "pair.exam.snapshot-storage", havingValue = "s3")
public class S3SnapshotStorage implements SnapshotStorage, DisposableBean {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    @Autowired // 테스트용 생성자가 하나 더 있어 Spring 이 쓸 생성자를 정해 준다
    public S3SnapshotStorage(
            @Value("${pair.exam.s3.bucket}") String bucket,
            @Value("${pair.exam.s3.region:ap-northeast-2}") String region,
            @Value("${pair.exam.s3.prefix:}") String prefix,
            // MinIO·LocalStack 같은 S3 호환 서버로 테스트할 때만 넣는다 (비우면 AWS)
            @Value("${pair.exam.s3.endpoint:}") String endpoint,
            // 로컬 개발용. 둘 다 비우면 AWS 기본 방식을 쓴다
            @Value("${pair.exam.s3.access-key:}") String accessKey,
            @Value("${pair.exam.s3.secret-key:}") String secretKey) {
        this(S3Clients.build(region, endpoint, accessKey, secretKey), bucket, prefix);
    }

    S3SnapshotStorage(S3Client s3, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("pair.exam.s3.bucket 을 설정하세요.");
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isEmpty() || prefix.endsWith("/") ? (prefix == null ? "" : prefix) : prefix + "/";
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(key))
                .contentType(SnapshotStorage.contentType(key))
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** S3 삭제는 없는 키여도 성공한다 */
    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
    }

    String objectKey(String key) {
        SnapshotStorage.requireValid(key);
        return prefix + key;
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
