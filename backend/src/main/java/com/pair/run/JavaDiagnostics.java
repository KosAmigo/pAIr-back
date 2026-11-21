package com.pair.run;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.springframework.stereotype.Component;

import com.pair.run.CodeRunner.CompileDiagnostic;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * 입력 중인 코드의 오류 밑줄, 자동 완성, 정의로 이동. 실행 모드(local/docker)와 상관없이 이 서버의 JDK 컴파일러 API 로
 * 메모리에서 분석만 한다. 파일을 쓰지도, 응시자 코드를 실행하지도 않는다(어노테이션 처리도 끈다).
 * 빌드 버튼의 컴파일과 달리 문법이 깨진 중간 상태에서도 타입 분석까지 진행해 오류를 최대한 낸다.
 * 기술검증: poc/javac-api/JavacAssist.java (요청당 평균 0.1초, 응시자 1명당 28MB).
 */
@Component
public class JavaDiagnostics {

    /** 한 번에 분석하는 요청 수. 파일 관리자(JDK 클래스 목록 캐시)도 이 수만큼만 만든다 */
    static final int MAX_CONCURRENT = 4;
    static final int MAX_FILES = 40;
    static final int MAX_TOTAL_CHARS = 400_000;
    static final int MAX_DIAGNOSTICS = 50;
    static final int MAX_COMPLETIONS = 100;
    private static final long WAIT_MILLIS = 2000;
    // 문법 오류가 있어도 타입 분석까지 진행한다 (입력 중인 코드는 대부분 문법이 깨져 있음)
    private static final List<String> OPTIONS =
            List.of("-proc:none", "-Xlint:none", "-XDshould-stop.ifError=FLOW", "-XDshould-stop.ifNoError=FLOW");

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT, true);
    private final BlockingQueue<StandardJavaFileManager> managers = new ArrayBlockingQueue<>(MAX_CONCURRENT);

    private static final class Source extends SimpleJavaFileObject {
        final String path;
        final String text;

        Source(String path, String text) {
            super(URI.create("mem:///" + path.replace(" ", "%20")), Kind.SOURCE);
            this.path = path;
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    /** 분석 결과(문법 트리, 타입 정보). 이 안에서만 쓰고 밖으로 내보내지 않는다 */
    private record Analysis(JavacTask task, Trees trees, List<CompilationUnitTree> units,
                            List<Diagnostic<? extends JavaFileObject>> diagnostics, Map<URI, Source> sources) {
        /** javac 는 파일 객체를 감싸서 돌려줄 수 있어 객체 비교 대신 주소(URI)로 우리 파일을 찾는다 */
        Source sourceOf(JavaFileObject f) {
            return f == null ? null : sources.get(f.toUri());
        }

        CompilationUnitTree unit(String path) {
            return units.stream().filter(u -> {
                Source s = sourceOf(u.getSourceFile());
                return s != null && s.path.equals(path);
            }).findFirst().orElse(null);
        }
    }

    /** 자동 완성 후보 하나. kind 는 method 또는 field, signature 는 화면에 보일 설명 */
    public record Completion(String name, String kind, String signature, boolean hasParams) {}

    /** 정의 위치. 라이브러리(JDK, JUnit)에 있으면 찾지 못한 것으로 본다 */
    public record Definition(String file, long line, long column, String kind, String name) {}

    private <T> T analyzeWith(Map<String, String> sources, Function<Analysis, T> use) {
        if (sources.size() > MAX_FILES) {
            throw new IllegalArgumentException("한 번에 분석할 수 있는 파일은 " + MAX_FILES + "개까지입니다.");
        }
        long total = sources.values().stream().mapToLong(String::length).sum();
        if (total > MAX_TOTAL_CHARS) {
            throw new IllegalArgumentException("분석할 코드가 너무 큽니다.");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK 컴파일러를 찾을 수 없습니다. JRE 가 아니라 JDK 로 백엔드를 실행하세요.");
        }
        acquire();
        StandardJavaFileManager fm = null;
        try {
            fm = managers.poll();
            if (fm == null) fm = newManager(compiler);
            return use.apply(analyze(compiler, fm, sources));
        } catch (Exception e) {
            // 오류가 난 관리자는 다시 쓰지 않고 버린다
            closeQuietly(fm);
            fm = null;
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
        } finally {
            if (fm != null && !managers.offer(fm)) closeQuietly(fm);
            slots.release();
        }
    }

    /**
     * @param sources 상대 경로 → 내용. 서로 참조하는 파일은 함께 넘겨야 한다.
     * @return 오류 목록(경로별로 구분된다). 자리가 없으면 {@link IllegalStateException}
     */
    public List<CompileDiagnostic> check(Map<String, String> sources) {
        if (sources.isEmpty()) return List.of();
        return analyzeWith(sources, a -> {
            List<CompileDiagnostic> out = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : a.diagnostics()) {
                if (out.size() >= MAX_DIAGNOSTICS) break;
                if (!(d.getSource() instanceof Source s)) continue;
                long[] at = lineAndColumn(s.text, d.getPosition(), d.getLineNumber(), d.getColumnNumber());
                String severity = d.getKind() == Diagnostic.Kind.ERROR ? "error" : "warning";
                out.add(new CompileDiagnostic(s.path, at[0], at[1], severity, d.getMessage(Locale.KOREAN)));
            }
            return out;
        });
    }

    /**
     * 자동 완성 후보. 커서 앞이 "식." 또는 "식.앞글자" 면 그 식의 타입 멤버(메서드·필드)를,
     * 점이 없으면 그 자리에서 쓸 수 있는 이름(지역 변수·매개변수·멤버·작업 파일의 클래스)을 낸다.
     * 앞글자(prefix)로 시작하는 것만 대소문자 구분 없이 낸다.
     *
     * @param offset path 파일 안의 글자 위치(0부터)
     */
    public List<Completion> complete(Map<String, String> sources, String path, int offset) {
        String text = sources.get(path);
        if (text == null || offset < 0 || offset > text.length()) return List.of();
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, offset);
        if (!prefix.isEmpty() && !Character.isJavaIdentifierStart(prefix.charAt(0))) return List.of();
        boolean member = start > 0 && text.charAt(start - 1) == '.';
        String marker = prefix + "__pairCursor";
        // 끊긴 식("s." 또는 "inv")은 문법 트리에서 빠지므로, 커서 자리를 메서드 호출 "표시()" 로 바꿔 올바른 식으로 만든다.
        // 점 뒤에서 문장이 끝나는 자리(뒤가 } 나 줄바꿈)면 ; 도 붙인다.
        String rest = text.substring(offset);
        String next = rest.stripLeading();
        int newline = rest.indexOf('\n');
        boolean statementEnd = next.isEmpty() || next.charAt(0) == '}' || (newline >= 0 && newline < rest.length() - next.length());
        String fill = next.startsWith("(") ? "" : member && statementEnd ? "();" : "()";
        Map<String, String> patched = new java.util.HashMap<>(sources);
        patched.put(path, text.substring(0, start) + marker + fill + rest);
        return analyzeWith(patched, a -> {
            CompilationUnitTree unit = a.unit(path);
            if (unit == null) return List.<Completion>of();
            TreePath[] found = new TreePath[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMemberSelect(MemberSelectTree t, Void v) {
                    if (member && t.getIdentifier().contentEquals(marker)) found[0] = getCurrentPath();
                    return super.visitMemberSelect(t, v);
                }

                @Override
                public Void visitIdentifier(IdentifierTree t, Void v) {
                    if (!member && t.getName().contentEquals(marker)) found[0] = getCurrentPath();
                    return super.visitIdentifier(t, v);
                }
            }.scan(unit, null);
            if (found[0] == null) return List.<Completion>of();
            var seen = new LinkedHashSet<Completion>();
            if (member) addMembers(a, found[0], prefix, seen);
            else addVisibleNames(a, found[0], prefix, seen);
            return seen.stream().sorted(Comparator.comparingInt((Completion c) -> rank(c.kind())).thenComparing(Completion::name)
                    .thenComparing(Completion::signature)).limit(MAX_COMPLETIONS).toList();
        });
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "variable" -> 0;
            case "class" -> 2;
            default -> 1;
        };
    }

    private static boolean startsWithIgnoreCase(String name, String prefix) {
        return name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isObjectMember(Element e) {
        return e.getEnclosingElement() instanceof TypeElement t && t.getQualifiedName().contentEquals("java.lang.Object");
    }

    private static Completion member(Element e) {
        if (e instanceof ExecutableElement m) {
            String params = String.join(", ", m.getParameters().stream().map(p -> p.asType() + " " + p.getSimpleName()).toList());
            return new Completion(m.getSimpleName().toString(), "method", "(" + params + ") : " + m.getReturnType(), !m.getParameters().isEmpty());
        }
        return new Completion(e.getSimpleName().toString(), "field", ": " + e.asType(), false);
    }

    /** "식." 의 식이 가진 멤버 */
    private static void addMembers(Analysis a, TreePath select, String prefix, java.util.Set<Completion> out) {
        MemberSelectTree tree = (MemberSelectTree) select.getLeaf();
        TypeMirror type = a.trees().getTypeMirror(new TreePath(select, tree.getExpression()));
        if (!(type instanceof DeclaredType declared)) return;
        var scope = a.trees().getScope(select);
        TypeElement owner = (TypeElement) declared.asElement();
        for (Element e : a.task().getElements().getAllMembers(owner)) {
            if (e.getKind() != ElementKind.METHOD && e.getKind() != ElementKind.FIELD) continue;
            if (!startsWithIgnoreCase(e.getSimpleName().toString(), prefix) || isObjectMember(e)) continue;
            if (!a.trees().isAccessible(scope, e, declared)) continue;
            out.add(member(e));
        }
    }

    /** 점이 없는 자리에서 쓸 수 있는 이름: 지역 변수·매개변수, 둘러싼 클래스의 멤버(상속 포함), 작업 파일의 클래스 */
    private static void addVisibleNames(Analysis a, TreePath at, String prefix, java.util.Set<Completion> out) {
        var scope = a.trees().getScope(at);
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (Element e : s.getLocalElements()) {
                ElementKind k = e.getKind();
                boolean local = k == ElementKind.LOCAL_VARIABLE || k == ElementKind.PARAMETER || k == ElementKind.EXCEPTION_PARAMETER
                        || k == ElementKind.RESOURCE_VARIABLE;
                if (local && startsWithIgnoreCase(e.getSimpleName().toString(), prefix)) {
                    out.add(new Completion(e.getSimpleName().toString(), "variable", ": " + e.asType(), false));
                }
            }
        }
        for (TypeElement cls = scope.getEnclosingClass(); cls != null;
             cls = cls.getEnclosingElement() instanceof TypeElement outer ? outer : null) {
            for (Element e : a.task().getElements().getAllMembers(cls)) {
                if (e.getKind() != ElementKind.METHOD && e.getKind() != ElementKind.FIELD) continue;
                if (!startsWithIgnoreCase(e.getSimpleName().toString(), prefix) || isObjectMember(e)) continue;
                if (!a.trees().isAccessible(scope, e, (DeclaredType) cls.asType())) continue;
                out.add(member(e));
            }
        }
        for (CompilationUnitTree unit : a.units()) {
            for (Tree decl : unit.getTypeDecls()) {
                Element e = a.trees().getElement(new TreePath(new TreePath(unit), decl));
                if (e instanceof TypeElement t) addType(t, prefix, out);
            }
        }
        // 라이브러리 클래스: 따로 import 하지 않아도 쓰는 java.lang 과, 이 파일이 import 한 클래스
        var elements = a.task().getElements();
        addPackageTypes(elements.getPackageElement("java.lang"), prefix, out);
        for (ImportTree imp : at.getCompilationUnit().getImports()) {
            if (imp.isStatic()) continue;
            String name = imp.getQualifiedIdentifier().toString();
            if (name.endsWith(".*")) {
                String owner = name.substring(0, name.length() - 2);
                // "java.util.*" 은 패키지, "java.util.Map.*" 은 클래스 안의 중첩 타입
                if (elements.getPackageElement(owner) != null) addPackageTypes(elements.getPackageElement(owner), prefix, out);
                else if (elements.getTypeElement(owner) instanceof TypeElement outer) addMemberTypes(outer, prefix, out);
            } else if (elements.getTypeElement(name) instanceof TypeElement t) {
                addType(t, prefix, out);
            }
        }
    }

    private static void addType(TypeElement t, String prefix, java.util.Set<Completion> out) {
        if (startsWithIgnoreCase(t.getSimpleName().toString(), prefix)) {
            out.add(new Completion(t.getSimpleName().toString(), "class", ": " + t.getQualifiedName(), false));
        }
    }

    private static void addMemberTypes(TypeElement outer, String prefix, java.util.Set<Completion> out) {
        for (Element e : outer.getEnclosedElements()) {
            if (e instanceof TypeElement t && t.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) addType(t, prefix, out);
        }
    }

    /** 패키지의 공개 타입(클래스·인터페이스·열거형·레코드)들 */
    private static void addPackageTypes(javax.lang.model.element.PackageElement pkg, String prefix, java.util.Set<Completion> out) {
        if (pkg == null) return;
        for (Element e : pkg.getEnclosedElements()) {
            if (e instanceof TypeElement t && t.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) addType(t, prefix, out);
        }
    }

    /**
     * 커서 위치의 이름이 선언된 곳. 우리가 넘긴 파일 안에서만 찾고, 라이브러리 선언이거나 이름이 아니면 null.
     *
     * @param offset path 파일 안의 글자 위치(0부터)
     */
    public Definition definition(Map<String, String> sources, String path, int offset) {
        String text = sources.get(path);
        if (text == null || offset < 0 || offset > text.length()) return null;
        return analyzeWith(sources, a -> {
            CompilationUnitTree unit = a.unit(path);
            if (unit == null) return null;
            SourcePositions pos = a.trees().getSourcePositions();
            TreePath[] best = new TreePath[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree t, Void v) {
                    if (t == null) return null;
                    long s = pos.getStartPosition(unit, t);
                    long e = pos.getEndPosition(unit, t);
                    if (s <= offset && offset <= e && (t instanceof IdentifierTree || t instanceof MemberSelectTree)) {
                        best[0] = new TreePath(getCurrentPath() == null ? new TreePath(unit) : getCurrentPath(), t);
                    }
                    return super.scan(t, v);
                }
            }.scan(unit, null);
            if (best[0] == null) return null;
            Element el = a.trees().getElement(best[0]);
            if (el == null) return null;
            TreePath decl = a.trees().getPath(el);
            if (decl == null) return null;
            Source s = a.sourceOf(decl.getCompilationUnit().getSourceFile());
            if (s == null) return null;
            long p = pos.getStartPosition(decl.getCompilationUnit(), decl.getLeaf());
            if (p < 0) return null;
            long[] at = lineAndColumn(s.text, p, 1, 1);
            return new Definition(s.path, at[0], at[1], el.getKind().toString().toLowerCase(Locale.ROOT), el.getSimpleName().toString());
        });
    }

    private void acquire() {
        try {
            if (!slots.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("코드 분석 요청이 많습니다. 잠시 뒤 다시 시도됩니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static StandardJavaFileManager newManager(JavaCompiler compiler) throws java.io.IOException {
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        fm.setLocation(StandardLocation.CLASS_PATH, List.of(new File(LocalJvmRunner.junitConsoleJar())));
        return fm;
    }

    private static Analysis analyze(JavaCompiler compiler, StandardJavaFileManager fm, Map<String, String> sources) {
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        List<Source> units = sources.entrySet().stream().map(e -> new Source(e.getKey(), e.getValue())).toList();
        JavacTask task = (JavacTask) compiler.getTask(null, fm, collected, OPTIONS, null, units);
        try {
            List<CompilationUnitTree> trees = new ArrayList<>();
            task.parse().forEach(trees::add);
            task.analyze();
            return new Analysis(task, Trees.instance(task), trees, collected.getDiagnostics(),
                    units.stream().collect(java.util.stream.Collectors.toMap(Source::toUri, u -> u, (x, y) -> x)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 위치를 줄·열(1부터, 글자 기준)로 바꾼다. javac 의 열은 탭을 8칸으로 세어 편집기와 어긋나므로
     * 글자 위치에서 직접 계산한다. 위치를 모르면 javac 가 준 값을 쓴다.
     */
    static long[] lineAndColumn(String text, long position, long fallbackLine, long fallbackColumn) {
        if (position < 0 || position > text.length()) return new long[] {Math.max(1, fallbackLine), Math.max(1, fallbackColumn)};
        long line = 1;
        int lineStart = 0;
        for (int i = 0; i < position; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new long[] {line, position - lineStart + 1};
    }

    private static void closeQuietly(StandardJavaFileManager fm) {
        if (fm == null) return;
        try {
            fm.close();
        } catch (Exception ignored) {
            // 버리는 관리자라 무시한다
        }
    }
}
