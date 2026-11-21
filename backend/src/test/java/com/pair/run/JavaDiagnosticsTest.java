package com.pair.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pair.run.CodeRunner.CompileDiagnostic;

class JavaDiagnosticsTest {

    private final JavaDiagnostics diagnostics = new JavaDiagnostics();

    private static final String OK = "package app;\npublic class A {\n    public int one() { return 1; }\n}\n";

    @Test
    void validCodeHasNoDiagnostics() {
        assertThat(diagnostics.check(Map.of("src/app/A.java", OK))).isEmpty();
    }

    @Test
    void typeErrorPointsAtTheRightLineAndColumn() {
        String bad = "package app;\npublic class A {\n    public int one() { return \"x\"; }\n}\n";
        List<CompileDiagnostic> d = diagnostics.check(Map.of("src/app/A.java", bad));
        assertThat(d).hasSize(1);
        assertThat(d.get(0).file()).isEqualTo("src/app/A.java");
        assertThat(d.get(0).severity()).isEqualTo("error");
        assertThat(d.get(0).line()).isEqualTo(3);
        assertThat(d.get(0).column()).isEqualTo(31); // return 뒤 "x" 의 따옴표 위치
    }

    @Test
    void columnCountsCharactersNotTabStops() {
        String bad = "package app;\npublic class A {\n\t\tint x = \"s\";\n}\n";
        List<CompileDiagnostic> d = diagnostics.check(Map.of("src/app/A.java", bad));
        assertThat(d).hasSize(1);
        // 탭 두 개 뒤 "int x = " 8글자 다음이 "s" 이다: 글자 기준 11열 (javac 의 탭 8칸 열이 아니다)
        assertThat(d.get(0).column()).isEqualTo(11);
    }

    @Test
    void brokenSyntaxStillReportsErrorsInOtherFilesToo() {
        Map<String, String> files = new HashMap<>();
        files.put("src/app/A.java", "package app;\npublic class A {\n    int x = ;\n}\n");
        files.put("src/app/B.java", "package app;\npublic class B {\n    A a = new A();\n    int y = a.missing;\n}\n");
        List<CompileDiagnostic> d = diagnostics.check(files);
        assertThat(d).extracting(CompileDiagnostic::file).contains("src/app/A.java");
        assertThat(d).allMatch(x -> x.severity().equals("error"));
    }

    @Test
    void filesCanReferenceEachOtherAndJunit() {
        Map<String, String> files = new HashMap<>();
        files.put("src/app/A.java", OK);
        files.put("test/app/ATest.java",
                "package app;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
                        + "class ATest {\n    @Test void one() { assertEquals(1, new A().one()); }\n}\n");
        assertThat(diagnostics.check(files)).isEmpty();
    }

    @Test
    void pathsWithSpacesAndKoreanAreReportedBack() {
        String bad = "public class 가 {\n    int x = \"s\";\n}\n";
        List<CompileDiagnostic> d = diagnostics.check(Map.of("src/내 폴더/가.java", bad));
        assertThat(d).extracting(CompileDiagnostic::file).containsOnly("src/내 폴더/가.java");
    }

    @Test
    void tooManyFilesOrTooMuchCodeIsRejected() {
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i <= JavaDiagnostics.MAX_FILES; i++) many.put("src/F" + i + ".java", "class F" + i + " {}");
        assertThatThrownBy(() -> diagnostics.check(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> diagnostics.check(Map.of("src/A.java", "x".repeat(JavaDiagnostics.MAX_TOTAL_CHARS + 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRunAnnotationProcessorsOrCode() {
        // 분석만 한다: static 초기화 블록이 있어도 실행되지 않는다
        String src = "public class A {\n    static { System.exit(7); }\n}\n";
        assertThat(diagnostics.check(Map.of("src/A.java", src))).isEmpty();
    }

    @Test
    void runsConcurrentlyWithoutMixingResults() throws Exception {
        String bad = "public class A {\n    int x = \"s\";\n}\n";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final boolean broken = i % 2 == 0;
                fs.add(pool.submit(() -> diagnostics.check(Map.of("src/A.java", broken ? bad : "public class A {}\n")).size()));
            }
            for (int i = 0; i < fs.size(); i++) assertThat(fs.get(i).get()).isEqualTo(i % 2 == 0 ? 1 : 0);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 자동 완성 ──

    private static final String SHOP = "package app;\nimport java.util.List;\npublic class A {\n"
            + "    String name;\n    public int count(String key, int times) { return 0; }\n    private int secret() { return 1; }\n"
            + "    void run(A other, List<String> names) {\n        %s\n    }\n}\n";

    private static int at(String text, String marker) {
        return text.indexOf(marker) + marker.length();
    }

    @Test
    void completesMembersOfTheExpressionBeforeTheDot() {
        String src = SHOP.formatted("other.");
        List<JavaDiagnostics.Completion> items = diagnostics.complete(Map.of("src/app/A.java", src), "src/app/A.java", at(src, "other."));
        assertThat(items).extracting(JavaDiagnostics.Completion::name).contains("count", "name", "run", "secret");
        var count = items.stream().filter(c -> c.name().equals("count")).findFirst().orElseThrow();
        assertThat(count.kind()).isEqualTo("method");
        assertThat(count.hasParams()).isTrue();
        assertThat(count.signature()).isEqualTo("(java.lang.String key, int times) : int");
        // Object 의 getClass, notify 같은 잡음은 내지 않는다
        assertThat(items).extracting(JavaDiagnostics.Completion::name).doesNotContain("getClass", "notify", "wait");
    }

    @Test
    void filtersByTheTypedPrefixAndKnowsLibraryTypes() {
        String src = SHOP.formatted("names.si");
        List<JavaDiagnostics.Completion> items = diagnostics.complete(Map.of("src/app/A.java", src), "src/app/A.java", at(src, "names.si"));
        assertThat(items).extracting(JavaDiagnostics.Completion::name).contains("size").allMatch(n -> n.startsWith("si"));
    }

    @Test
    void completionWorksAcrossFilesAndWithBrokenCodeAround() {
        Map<String, String> files = new HashMap<>();
        files.put("src/app/B.java", "package app;\npublic class B {\n    public String title() { return \"t\"; }\n}\n");
        String src = "package app;\npublic class A {\n    int x = ;\n    void run(B b) {\n        b.t\n    }\n}\n";
        files.put("src/app/A.java", src);
        List<JavaDiagnostics.Completion> items = diagnostics.complete(files, "src/app/A.java", at(src, "b.t"));
        assertThat(items).extracting(JavaDiagnostics.Completion::name).containsExactly("title");
    }

    @Test
    void noCompletionOutsideAMemberAccess() {
        String src = SHOP.formatted("int y = 1;");
        assertThat(diagnostics.complete(Map.of("src/app/A.java", src), "src/app/A.java", at(src, "int y"))).isEmpty();
        assertThat(diagnostics.complete(Map.of("src/app/A.java", src), "src/missing.java", 0)).isEmpty();
        assertThat(diagnostics.complete(Map.of("src/app/A.java", src), "src/app/A.java", 99999)).isEmpty();
    }

    // ── 정의로 이동 ──

    @Test
    void jumpsToTheDeclarationInTheSameOrAnotherFile() {
        Map<String, String> files = new HashMap<>();
        files.put("src/app/B.java", "package app;\npublic class B {\n    public String title() { return \"t\"; }\n}\n");
        String src = "package app;\npublic class A {\n    void run(B b) {\n        String s = b.title();\n    }\n}\n";
        files.put("src/app/A.java", src);

        var method = diagnostics.definition(files, "src/app/A.java", at(src, "b.ti"));
        assertThat(method).isNotNull();
        assertThat(method.file()).isEqualTo("src/app/B.java");
        assertThat(method.line()).isEqualTo(3);
        assertThat(method.kind()).isEqualTo("method");
        assertThat(method.name()).isEqualTo("title");

        var type = diagnostics.definition(files, "src/app/A.java", at(src, "void run(B"));
        assertThat(type).isNotNull();
        assertThat(type.file()).isEqualTo("src/app/B.java");
        assertThat(type.kind()).isEqualTo("class");

        var local = diagnostics.definition(files, "src/app/A.java", src.indexOf("b.title"));
        assertThat(local).isNotNull();
        assertThat(local.file()).isEqualTo("src/app/A.java");
        assertThat(local.line()).isEqualTo(3);
        assertThat(local.kind()).isEqualTo("parameter");
    }

    @Test
    void libraryDeclarationsAndNonNamesGiveNothing() {
        String src = "package app;\nimport java.util.List;\npublic class A {\n    void run(List<String> names) {\n        int n = names.size();\n    }\n}\n";
        assertThat(diagnostics.definition(Map.of("src/app/A.java", src), "src/app/A.java", at(src, "names.si"))).isNull();
        assertThat(diagnostics.definition(Map.of("src/app/A.java", src), "src/app/A.java", 0)).isNull();
        assertThat(diagnostics.definition(Map.of("src/app/A.java", src), "src/x.java", 5)).isNull();
    }
}
