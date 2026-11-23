package com.pair.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.infra.storage.S3SnapshotStorage;
import com.pair.infra.storage.SnapshotStorage;
import com.pair.infra.storage.S3TaskFileStorage;
import com.pair.infra.storage.TaskFileStorage;

/**
 * 저장소를 s3 로 켜도 서버(스프링 컨텍스트)가 뜨는지 본다.
 * 다른 테스트는 모두 local 저장소라, S3 저장소 생성자 문제(생성자가 둘이라 스프링이 고르지 못함)를 잡지 못했다.
 * S3 클라이언트는 만들 때 접속하지 않으므로 실제 S3 호출은 없다.
 */
@SpringBootTest(properties = {
        "pair.exam.snapshot-storage=s3",
        "pair.taskgen.file-storage=s3",
        "pair.exam.s3.bucket=test-bucket",
        "pair.exam.s3.region=ap-northeast-2",
        "pair.exam.s3.access-key=",
        "pair.exam.s3.secret-key="
})
@ActiveProfiles("examtest")
class S3StorageContextTest {

    @MockitoBean JavaMailSender mail;

    @Autowired SnapshotStorage snapshots;
    @Autowired TaskFileStorage taskFiles;

    @Test
    void s3로_켜도_서버가_뜨고_S3_저장소가_쓰인다() {
        assertThat(snapshots).isInstanceOf(S3SnapshotStorage.class);
        assertThat(taskFiles).isInstanceOf(S3TaskFileStorage.class);
    }
}
