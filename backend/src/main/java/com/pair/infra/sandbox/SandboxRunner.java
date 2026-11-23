package com.pair.infra.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pair.global.config.PairProperties;

/**
 * 응시자 코드를 일회용 Docker 컨테이너 안에서 컴파일·실행한다 (실행 1회 = 컨테이너 1개).
 * 인터넷 차단, 메모리 512MB, CPU 1개, 프로세스 128개, 루트 읽기 전용, 일반 사용자, 권한 제거.
 * 설정 {@code pair.run.mode=docker} 일 때 쓴다. 이미지는 {@code docker/sandbox/build.sh} 로 만든다.
 * <p>
 * 한계: 테스트 결과(JUnit XML)는 응시자 코드와 같은 컨테이너에서 나온다. 표식에 실행마다 다른 난수를 써서 흉내내기를
 * 어렵게 했지만, 채점을 완전히 믿으려면 숨은 테스트는 응시자 코드와 다른 컨테이너에서 돌려야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "pair.run", name = "mode", havingValue = "docker")
public class SandboxRunner implements CodeRunner {

    /** 컨테이너 시작·컴파일에 드는 시간. 테스트 제한시간({@code timeoutSeconds})과 별도로 더 기다려 준다. */
    private static final int STARTUP_GRACE_SECONDS = 15;
    private static final int MAX_LINES = 400;
    private static final int MAX_REPORT_CHARS = 2_000_000;
    private static final Pattern DIAGNOSTIC = Pattern.compile("^/workspace/src/(.+?\\.java):(\\d+): (error|warning): (.*)$");

    private final int timeoutSeconds;
    private final String dockerBin;
    private final String image;
    /** 동시 실행 상한. 공정(fair)이라 먼저 온 순서대로 자리가 난다. */
    private final Semaphore slots;
    private final int queueWaitSeconds;

    public SandboxRunner(PairProperties props) {
        this.timeoutSeconds = props.run().timeoutSeconds();
        this.dockerBin = props.run().dockerBin();
        this.image = props.run().dockerImage();
        this.slots = new Semaphore(props.run().maxConcurrent(), true);
        this.queueWaitSeconds = props.run().queueWaitSeconds();
    }

    /** 실행 대기열이 너무 길어 자리를 못 얻었다. 잠시 뒤 다시 시도하면 된다. */
    public static class BusyException extends RuntimeException {
        BusyException(String message) {
            super(message);
        }
    }

    @Override
    public RunResult run(Map<String, String> sources) {
        Exec e = exec("run", sources);
        List<String> lines = new ArrayList<>(e.compileLines);
        List<CompileDiagnostic> diags = diagnosticsOf(e.compileLines);
        if (!e.compiled) {
            lines.add("[ERROR] 컴파일에 실패했습니다.");
            return new RunResult(false, lines, 0, 0, List.of(), diags, e.millis);
        }
        lines.addAll(e.runLines);
        List<TestCase> tests = new ArrayList<>();
        e.reports.forEach(xml -> tests.addAll(JunitReports.parseXml(xml)));
        int passed = (int) tests.stream().filter(TestCase::passed).count();
        int failed = tests.size() - passed;
        if (e.timedOut) {
            lines.add("[TIMEOUT] " + timeoutSeconds + "초 안에 끝나지 않아 실행을 중단했습니다. 교착 상태일 수 있습니다.");
            failed = Math.max(1, failed);
        } else if (tests.isEmpty() && (e.exitCode != 0 || e.junitExit == null || e.junitExit != 0)) {
            // 리포트 없이 끝남: 테스트 실행기가 죽었다 (메모리 초과, 디스크 가득 참 등)
            lines.add("[ERROR] 테스트 실행이 비정상 종료했습니다 (exit " + (e.junitExit != null ? e.junitExit : e.exitCode) + "). 메모리 초과나 저장공간 초과일 수 있습니다.");
            failed = 1;
        }
        return new RunResult(true, lines, passed, failed, tests, diags, e.millis);
    }

    @Override
    public BuildResult build(Map<String, String> sources) {
        Exec e = exec("build", sources);
        List<String> lines = new ArrayList<>(e.compileLines);
        List<CompileDiagnostic> diags = diagnosticsOf(e.compileLines);
        if (e.compiled) lines.add("BUILD SUCCESSFUL" + (diags.isEmpty() ? "" : " (경고 " + diags.size() + "건)"));
        else lines.add("[ERROR] 컴파일에 실패했습니다.");
        return new BuildResult(e.compiled, lines, diags, e.millis);
    }

    private record Exec(boolean compiled, boolean timedOut, int exitCode, Integer junitExit, List<String> compileLines,
                        List<String> runLines, List<String> reports, long millis) {}

    private Exec exec(String mode, Map<String, String> sources) {
        acquireSlot();
        try {
            return execInContainer(mode, sources);
        } finally {
            slots.release();
        }
    }

    private void acquireSlot() {
        try {
            if (!slots.tryAcquire(queueWaitSeconds, TimeUnit.SECONDS)) {
                throw new BusyException("실행 요청이 많아 " + queueWaitSeconds + "초 안에 자리를 얻지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Exec execInContainer(String mode, Map<String, String> sources) {
        long started = System.nanoTime(); // 대기 시간은 제외하고 실제 실행 시간만 잰다
        Path work = null;
        String name = "pair-run-" + UUID.randomUUID();
        try {
            work = Files.createTempDirectory("pair-sbx-");
            Path src = work.resolve("src");
            writeSources(src, sources);
            String mark = "@@PAIR-" + UUID.randomUUID() + "@@";
            List<String> cmd = List.of(dockerBin, "run", "--rm", "--init", "--name", name,
                    "--network", "none", "--memory", "512m", "--memory-swap", "512m", "--cpus", "1", "--pids-limit", "128",
                    "--read-only", "--tmpfs", "/tmp:size=64m,exec,uid=10001",
                    "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
                    "-e", "PAIR_MARK=" + mark,
                    "-v", src.toRealPath() + ":/workspace/src:ro",
                    image, "/opt/run.sh", mode);
            Process proc;
            try {
                proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException ex) {
                throw new IllegalStateException("docker 를 실행하지 못했습니다 (" + dockerBin + "): " + ex.getMessage(), ex);
            }
            Collected c = new Collected(mark);
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var r = proc.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) c.accept(line);
                } catch (IOException ignored) {
                    // 프로세스가 끝나면 스트림이 닫힌다
                }
            });
            boolean done = proc.waitFor(timeoutSeconds + STARTUP_GRACE_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                killContainer(name);
                proc.destroyForcibly();
            }
            reader.join(2000);
            int exit = done ? proc.exitValue() : -1;
            if (c.compileResult == null && !done) {
                // 컴파일도 끝내지 못했다
                return new Exec(false, true, exit, c.junitExit, c.compileLines, c.runLines, c.reports, elapsed(started));
            }
            if (c.compileResult == null) {
                throw new IllegalStateException("샌드박스 컨테이너가 실행되지 않았습니다 (exit " + exit + "): "
                        + String.join(" | ", c.compileLines.stream().limit(5).toList()));
            }
            return new Exec(c.compileResult, !done, exit, c.junitExit, c.compileLines, c.runLines, c.reports, elapsed(started));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            killContainer(name);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** 컨테이너가 읽을 수 있게 폴더 755, 파일 644 로 쓴다 (리눅스에서는 임시 폴더 기본 권한 700 이라 uid 10001 이 못 읽는다). */
    private static void writeSources(Path src, Map<String, String> sources) throws IOException {
        var dirPerm = PosixFilePermissions.fromString("rwxr-xr-x");
        var filePerm = PosixFilePermissions.fromString("rw-r--r--");
        Files.createDirectories(src);
        Files.setPosixFilePermissions(src, dirPerm);
        Files.setPosixFilePermissions(src.getParent(), dirPerm);
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            Path p = src.resolve(e.getKey()).normalize();
            if (!p.startsWith(src)) continue; // ../ 로 작업 폴더 밖에 쓰지 못하게
            for (Path d = p.getParent(); !d.equals(src); d = d.getParent()) {
                Files.createDirectories(d);
                Files.setPosixFilePermissions(d, dirPerm);
            }
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(p, filePerm);
        }
    }

    private void killContainer(String name) {
        try {
            new ProcessBuilder(dockerBin, "rm", "-f", name).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // 컨테이너가 이미 없으면 무시한다
        }
    }

    /** javac 출력에서 오류/경고 위치를 뽑는다. 다음다음 줄의 ^ 위치가 열 번호. */
    private static List<CompileDiagnostic> diagnosticsOf(List<String> compileLines) {
        List<CompileDiagnostic> out = new ArrayList<>();
        for (int i = 0; i < compileLines.size(); i++) {
            Matcher m = DIAGNOSTIC.matcher(compileLines.get(i));
            if (!m.matches()) continue;
            long column = 0;
            if (i + 2 < compileLines.size()) {
                String caretLine = compileLines.get(i + 2);
                if (caretLine.trim().equals("^")) column = caretLine.indexOf('^') + 1;
            }
            out.add(new CompileDiagnostic(m.group(1), Long.parseLong(m.group(2)), column, m.group(3), m.group(4)));
        }
        return out;
    }

    /** 컨테이너 출력 한 줄씩 받아 컴파일 출력 / 테스트 출력 / 리포트 XML 로 나눈다. */
    private static final class Collected {
        private final String compiledMark, failedMark, fileMark, endMark, exitMark;
        final List<String> compileLines = new ArrayList<>();
        final List<String> runLines = new ArrayList<>();
        final List<String> reports = new ArrayList<>();
        volatile Boolean compileResult;
        volatile Integer junitExit;
        private StringBuilder current;
        private int reportChars;

        Collected(String mark) {
            compiledMark = mark + " COMPILED";
            failedMark = mark + " COMPILE-FAILED";
            fileMark = mark + " FILE";
            endMark = mark + " END";
            exitMark = mark + " JUNIT-EXIT ";
        }

        synchronized void accept(String line) {
            if (compileResult == null) {
                if (line.equals(compiledMark)) compileResult = true;
                else if (line.equals(failedMark)) compileResult = false;
                else if (compileLines.size() < MAX_LINES) compileLines.add(line);
            } else if (current != null) {
                if (line.equals(endMark)) {
                    reports.add(current.toString());
                    current = null;
                } else if (reportChars < MAX_REPORT_CHARS) {
                    current.append(line).append('\n');
                    reportChars += line.length() + 1;
                }
            } else if (line.equals(fileMark)) {
                current = new StringBuilder();
            } else if (line.startsWith(exitMark)) {
                try { junitExit = Integer.parseInt(line.substring(exitMark.length()).trim()); } catch (NumberFormatException ignored) { }
            } else if (runLines.size() < MAX_LINES) {
                runLines.add(line);
            }
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더 정리 실패는 무시한다
        }
    }
}
