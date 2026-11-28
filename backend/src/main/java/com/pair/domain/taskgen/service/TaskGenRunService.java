package com.pair.domain.taskgen.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenRunProperties;
import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.LocalJvmRunner;

/**
 * 과제 생성기 전용 코드 실행 장치. 응시용 CodeRunner(pair.run.mode)와 따로 있어서, 생성기가 아무리 바빠도 응시자 실행을 막지 않는다.
 * 같은 방식(서버의 별도 JVM)으로 돌되 동시 실행 수는 pair.taskgen.run.max-concurrent(기본 CPU 수)로 막고, 넘으면 공정한 줄에서 기다린다.
 * 실행 JVM 마다 -Xmx(기본 256m). 줄 선 시간과 실행 시간은 생성 작업의 report timing 에 남긴다.
 * 스프링 빈 CodeRunner 가 아니므로 응시 쪽 주입과 섞이지 않는다. 생성기 서비스는 {@link #runner()} 로 CodeRunner 모양을 받아 쓴다.
 */
@Service
public class TaskGenRunService {

    private final CodeRunner delegate;
    private final Semaphore slots;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskGenRunService(TaskGenRunProperties cfg, PairProperties pair) {
        this(new LocalJvmRunner(cfg.timeoutOr(pair.run().timeoutSeconds()), List.of("-Xmx" + cfg.heapMb() + "m")), cfg.maxConcurrent());
    }

    /** 테스트용: 실행기와 동시 실행 수를 직접 넘긴다 */
    TaskGenRunService(CodeRunner delegate, int maxConcurrent) {
        this.delegate = delegate;
        this.slots = new Semaphore(maxConcurrent, true);
    }

    /** 컴파일하고 테스트를 돌린다. 자리가 날 때까지 줄을 선다 */
    public CodeRunner.RunResult run(Map<String, String> sources) {
        long queued = System.nanoTime();
        slots.acquireUninterruptibly();
        long start = System.nanoTime();
        try {
            return delegate.run(sources);
        } finally {
            slots.release();
            TaskGeneratorService.recordRun(TimeUnit.NANOSECONDS.toMillis(start - queued), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    /** 생성기 서비스들이 기존 CodeRunner 모양으로 받아 쓰는 보기. 스프링 빈이 아니다 */
    public CodeRunner runner() {
        return new CodeRunner() {
            @Override
            public RunResult run(Map<String, String> sources) {
                return TaskGenRunService.this.run(sources);
            }

            @Override
            public BuildResult build(Map<String, String> sources) {
                return delegate.build(sources);
            }
        };
    }

    /** 지금 줄을 서서 기다리는 실행 수(확인용) */
    public int waiting() {
        return slots.getQueueLength();
    }
}
