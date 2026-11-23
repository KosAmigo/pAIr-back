package com.pair.infra.sandbox;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pair.global.config.PairProperties;

/**
 * 응시자 코드를 javac 로 컴파일하고 별도 JVM 에서 JUnit 콘솔 런처로 테스트를 돌린다.
 * 파일럿(혼자 검증) 전용: 격리 없이 이 컴퓨터에서 바로 실행한다.
 * 설정 {@code pair.run.mode=local}(기본)일 때 쓴다. 격리 컨테이너로 실행하려면 {@code docker} ({@link SandboxRunner}).
 */
@Component
@ConditionalOnProperty(prefix = "pair.run", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalJvmRunner implements CodeRunner {

    private final int timeoutSeconds;

    public LocalJvmRunner(PairProperties props) {
        this.timeoutSeconds = props.run().timeoutSeconds();
    }

    /**
     * @param sources 컴파일할 .java 파일들 (상대 경로 → 내용)
     */
    @Override
    public RunResult run(Map<String, String> sources) {
        long started = System.nanoTime();
        Path work = null;
        try {
            work = Files.createTempDirectory("pair-run-");
            Path src = work.resolve("src");
            Path out = work.resolve("classes");
            Path reports = work.resolve("reports");
            Files.createDirectories(out);
            List<Path> files = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                if (!e.getKey().endsWith(".java")) continue;
                Path p = src.resolve(e.getKey()).normalize();
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                files.add(p);
            }
            List<String> lines = new ArrayList<>();
            List<CompileDiagnostic> diags = new ArrayList<>();
            lines.add("$ javac (" + files.size() + " files)");
            String junitJar = junitConsoleJar();
            if (!compile(files, src, out, junitJar, lines, diags)) {
                return new RunResult(false, lines, 0, 0, List.of(), diags, elapsed(started));
            }
            lines.add("$ junit execute --scan-class-path");
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(javaBin,
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-jar", junitJar, "execute",
                    "--class-path", out.toString(),
                    "--scan-class-path",
                    "--disable-banner",
                    "--disable-ansi-colors",
                    "--details=tree",
                    "--details-theme=ascii",
                    "--reports-dir", reports.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var r = proc.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (lines) {
                            if (lines.size() < 400) lines.add(line);
                        }
                    }
                } catch (IOException ignored) {
                    // 프로세스가 끝나면 스트림이 닫힌다
                }
            });
            boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                reader.join(2000);
                lines.add("[TIMEOUT] " + timeoutSeconds + "초 안에 끝나지 않아 실행을 중단했습니다. 교착 상태일 수 있습니다.");
                List<TestCase> tests = JunitReports.parseDir(reports);
                long passed = tests.stream().filter(TestCase::passed).count();
                return new RunResult(true, lines, (int) passed, Math.max(1, tests.size() - (int) passed), tests, diags, elapsed(started));
            }
            reader.join(2000);
            List<TestCase> tests = JunitReports.parseDir(reports);
            int passed = (int) tests.stream().filter(TestCase::passed).count();
            int failed = tests.size() - passed;
            return new RunResult(true, lines, passed, failed, tests, diags, elapsed(started));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            deleteQuietly(work);
        }
    }

    @Override
    public BuildResult build(Map<String, String> sources) {
        long started = System.nanoTime();
        Path work = null;
        try {
            work = Files.createTempDirectory("pair-build-");
            Path src = work.resolve("src");
            Path out = work.resolve("classes");
            Files.createDirectories(out);
            List<Path> files = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                if (!e.getKey().endsWith(".java")) continue;
                Path p = src.resolve(e.getKey()).normalize();
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                files.add(p);
            }
            List<String> lines = new ArrayList<>();
            List<CompileDiagnostic> diags = new ArrayList<>();
            lines.add("$ javac (" + files.size() + " files)");
            boolean ok = compile(files, src, out, junitConsoleJar(), lines, diags);
            if (ok) lines.add("BUILD SUCCESSFUL" + (diags.isEmpty() ? "" : " (경고 " + diags.size() + "건)"));
            return new BuildResult(ok, lines, diags, elapsed(started));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(work);
        }
    }

    private boolean compile(List<Path> files, Path srcRoot, Path out, String classpath, List<String> lines,
                            List<CompileDiagnostic> diags) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            lines.add("[ERROR] JDK 컴파일러를 찾을 수 없습니다. JRE 가 아니라 JDK 로 백엔드를 실행하세요.");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diags_ = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags_, null, StandardCharsets.UTF_8)) {
            StringWriter w = new StringWriter();
            boolean ok = compiler.getTask(w, fm, diags_,
                    List.of("-encoding", "UTF-8", "-d", out.toString(), "-cp", classpath, "-proc:none"),
                    null, fm.getJavaFileObjectsFromPaths(files)).call();
            diagnosticsOf(diags_, srcRoot, lines, diags);
            if (!ok) lines.add("[ERROR] 컴파일에 실패했습니다.");
            return ok;
        }
    }

    private static void diagnosticsOf(DiagnosticCollector<JavaFileObject> collector, Path srcRoot,
                                      List<String> lines, List<CompileDiagnostic> out) {
        collector.getDiagnostics().forEach(d -> {
            String file = "";
            if (d.getSource() != null) {
                Path p = Path.of(d.getSource().toUri());
                file = p.startsWith(srcRoot) ? srcRoot.relativize(p).toString().replace(java.io.File.separatorChar, '/') : p.getFileName().toString();
            }
            String severity = d.getKind() == javax.tools.Diagnostic.Kind.ERROR ? "error" : "warning";
            String msg = d.getMessage(java.util.Locale.KOREAN);
            lines.add("[" + severity.toUpperCase() + "] " + file + ":" + d.getLineNumber() + ":" + d.getColumnNumber() + " " + msg);
            out.add(new CompileDiagnostic(file, d.getLineNumber(), d.getColumnNumber(), severity, msg));
        });
    }

    static String junitConsoleJar() {
        try {
            Class<?> launcher = Class.forName("org.junit.platform.console.ConsoleLauncher");
            return Path.of(launcher.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("junit-platform-console-standalone jar 를 찾지 못했습니다.", e);
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
