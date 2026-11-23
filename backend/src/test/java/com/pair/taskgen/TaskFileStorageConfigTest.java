package com.pair.taskgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 과제 PDF 저장소 설정 해석, 공용 DB 안전장치, S3 저장소(가짜 클라이언트) 확인. 실제 AI·S3 호출 없음 */
class TaskFileStorageConfigTest {

    /** 진짜 application.yml 을 읽고, 환경 변수 자리에 env 를 넣어 file-storage 값을 구한다 */
    private static String resolve(Map<String, Object> env) throws Exception {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", env));
        yml.forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getProperty("pair.taskgen.file-storage");
    }

    @Test
    @DisplayName("설정: 아무것도 없으면 local, 감독 사진 설정만 있으면 따라가고, PAIR_TASKFILE_STORAGE 가 우선")
    void configResolution() throws Exception {
        assertThat(resolve(Map.of())).isEqualTo("local");
        assertThat(resolve(Map.of("PAIR_SNAPSHOT_STORAGE", "s3"))).isEqualTo("s3");
        assertThat(resolve(Map.of("PAIR_TASKFILE_STORAGE", "local", "PAIR_SNAPSHOT_STORAGE", "s3"))).isEqualTo("local");
        assertThat(resolve(Map.of("PAIR_TASKFILE_STORAGE", "s3"))).isEqualTo("s3");
    }

    private static final String REMOTE = "jdbc:postgresql://pair-db.xxxx.ap-northeast-2.rds.amazonaws.com:5432/pair";

    private static TaskGenJobs jobs(String storage, String url, BizTaskGenerator biz, DevTaskGenerator dev) {
        return new TaskGenJobs(dev, biz, new TaskGenProperties(null, null, null, null, null), new TaskFileStorageGuard(storage, url));
    }

    private static TaskGenForm form(String track) {
        return new TaskGenForm(track, "주제", "함정", "사용", null);
    }

    @Test
    @DisplayName("안전장치: local + 원격 DB 면 BIZ 는 AI 호출 없이 거절, DEV 는 통과")
    void blocksBizOnRemoteDb() throws Exception {
        BizTaskGenerator biz = mock(BizTaskGenerator.class);
        DevTaskGenerator dev = mock(DevTaskGenerator.class);
        when(dev.generate(any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(new TaskGenerator.Outcome("T-1", true, 1, List.of()));
        TaskGenJobs j = jobs("local", REMOTE, biz, dev);

        assertThatThrownBy(() -> j.start(form("BIZ"), 1L))
                .isInstanceOf(IllegalStateException.class) // ApiExceptionHandler 가 400 으로 바꾼다
                .hasMessageContaining("PAIR_TASKFILE_STORAGE=s3");
        verifyNoInteractions(biz);

        assertThat(j.start(form("DEV"), 1L)).isNotBlank();
    }

    @Test
    @DisplayName("안전장치: local 이라도 H2·localhost 는 통과, s3 + 원격은 통과")
    void allowsSafeCombos() {
        for (String[] c : new String[][] {
                {"local", "jdbc:h2:mem:x"}, {"local", "jdbc:postgresql://localhost:5432/pair"},
                {"local", "jdbc:postgresql://127.0.0.1:5432/pair"}, {"s3", REMOTE}}) {
            assertThat(new TaskFileStorageGuard(c[0], c[1]).isRisky()).as(c[0] + " " + c[1]).isFalse();
        }
        assertThat(new TaskFileStorageGuard("local", REMOTE).isRisky()).isTrue();
        new TaskFileStorageGuard("s3", REMOTE).requireSafe("BIZ"); // 예외 없음
    }

    @Test
    @DisplayName("S3 저장소: 버킷 이름이 없으면 알기 쉬운 오류, 키는 task-files/ 로 시작")
    void s3Storage() {
        assertThatThrownBy(() -> new S3TaskFileStorage(mock(S3Client.class), " ", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAIR_S3_BUCKET");

        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        S3TaskFileStorage s = new S3TaskFileStorage(client, "bucket", "");
        String key = TaskFileStorage.keyOf(5, 1);

        s.put(key, new byte[] {1});
        s.get(key);
        s.delete(key);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture(), any(RequestBody.class));
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(get.capture());
        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(del.capture());
        assertThat(put.getValue().key()).startsWith("task-files/");
        assertThat(get.getValue().key()).startsWith("task-files/");
        assertThat(del.getValue().key()).startsWith("task-files/");
    }

    @Test
    @DisplayName("S3 접속 키: 둘 다 있으면 정적 키, 둘 다 없으면 기본 체인, 하나만 있으면 오류(키 값은 메시지에 없음)")
    void s3Credentials() {
        var st = com.pair.exam.service.S3Clients.credentials("AKIATESTKEY123", "SECRETVALUE456");
        assertThat(st).isInstanceOf(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.class);
        assertThat(st.resolveCredentials().accessKeyId()).isEqualTo("AKIATESTKEY123");
        assertThat(com.pair.exam.service.S3Clients.credentials("", " "))
                .isInstanceOf(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.class);
        assertThat(com.pair.exam.service.S3Clients.credentials(null, null))
                .isInstanceOf(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.class);

        assertThatThrownBy(() -> com.pair.exam.service.S3Clients.credentials("AKIATESTKEY123", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("둘 다")
                .hasMessageNotContaining("AKIATESTKEY123");
        assertThatThrownBy(() -> com.pair.exam.service.S3Clients.credentials(null, "SECRETVALUE456"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("SECRETVALUE456");
    }
}
