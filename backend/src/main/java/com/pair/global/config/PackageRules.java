package com.pair.global.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;

/**
 * 백엔드 패키지 구조·클래스 이름 규칙 (pAIr-docs 설계/백엔드_패키지_구조.md).
 * 규칙은 여기 한 곳에만 적는다. 서버 시작(PackageRulesConfig), 테스트(ArchitectureTest),
 * Gradle 빌드·실행(archTest)이 모두 이 클래스를 부른다.
 * main 코드(PairApplication 이 있는 곳)만 본다. 테스트 클래스는 검사하지 않는다.
 */
public final class PackageRules {

    private static final String BASE = "com.pair";
    private static final Set<String> TOP = Set.of("global", "infra", "domain");
    private static final Set<String> GLOBAL = Set.of("config", "error", "security");
    private static final Set<String> INFRA = Set.of("ai", "sandbox", "storage", "payment", "mail");
    private static final Set<String> LAYERS = Set.of("controller", "service", "dto", "repository", "entity", "exception");

    /**
     * Gradle packageRulesCheck 태스크가 컴파일 직후 부른다(classes 가 이 검사를 거친다).
     * IntelliJ 의 "Build and run using: Gradle"(기본값)에서는 PairApplication 실행 전에 여기서 멈춘다.
     */
    public static void main(String[] args) {
        List<String> violations = new PackageRules().violations();
        if (violations.isEmpty()) return;
        System.err.println("패키지 구조 규칙을 어긴 클래스가 있습니다 (pAIr-docs 설계/백엔드_패키지_구조.md):");
        violations.forEach(v -> System.err.println("  - " + v));
        System.exit(1);
    }

    /** 규칙을 어긴 클래스마다 한 줄. 비어 있으면 통과 */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        for (String name : mainClassNames()) {
            Class<?> c;
            try {
                c = Class.forName(name, false, PackageRules.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                out.add(name + ": 클래스를 읽지 못했습니다 (" + e + ")");
                continue;
            }
            check(c, out);
        }
        return out;
    }

    private static void check(Class<?> c, List<String> out) {
        String simple = c.getSimpleName();
        String pkg = c.getPackageName();
        if (pkg.equals(BASE)) {
            if (!simple.equals("PairApplication")) out.add(c.getName() + ": com.pair 바로 아래에는 PairApplication 만 둡니다");
            return;
        }
        String[] p = pkg.substring(BASE.length() + 1).split("\\.");
        String top = p[0];
        if (!TOP.contains(top)) {
            out.add(c.getName() + ": com.pair 아래 첫 폴더는 global, infra, domain 중 하나여야 합니다");
            return;
        }
        boolean throwable = Throwable.class.isAssignableFrom(c);
        boolean restController = c.isAnnotationPresent(RestController.class) || c.isAnnotationPresent(Controller.class);
        boolean service = c.isAnnotationPresent(Service.class);
        boolean entity = c.isAnnotationPresent(Entity.class);
        boolean repository = Repository.class.isAssignableFrom(c);
        String layer = null;

        if (top.equals("domain")) {
            if (p.length != 3 || !LAYERS.contains(p[2])) {
                out.add(c.getName() + ": domain/<기능>/ 아래 controller, service, dto, repository, entity, exception 폴더 안에만 둡니다");
                return;
            }
            layer = p[2];
        } else {
            Set<String> allowed = top.equals("global") ? GLOBAL : INFRA;
            if (p.length != 2 || !allowed.contains(p[1])) {
                out.add(c.getName() + ": " + top + "/ 아래 폴더는 " + new TreeSet<>(allowed) + " 중 하나여야 합니다");
                return;
            }
        }

        // 애너테이션·타입별 위치
        if (restController && !"controller".equals(layer))
            out.add(c.getName() + ": @RestController 는 domain/<기능>/controller/ 에 둡니다");
        if (entity && !"entity".equals(layer))
            out.add(c.getName() + ": @Entity 는 domain/<기능>/entity/ 에 둡니다");
        if (repository && !"repository".equals(layer))
            out.add(c.getName() + ": Spring Data 리포지토리는 domain/<기능>/repository/ 에 둡니다");
        if (service && layer != null && !"service".equals(layer))
            out.add(c.getName() + ": @Service 는 domain/<기능>/service/ (또는 global/, infra/) 에 둡니다");
        if (throwable && layer != null && !"exception".equals(layer))
            out.add(c.getName() + ": 기능 전용 예외는 domain/<기능>/exception/ 에 둡니다 (여러 기능이 쓰면 global/error/)");

        // 이름 규칙
        if (throwable && !simple.endsWith("Exception"))
            out.add(c.getName() + ": 예외 이름은 Exception 으로 끝나야 합니다");
        if (service && !simple.endsWith("Service"))
            out.add(c.getName() + ": @Service 이름은 Service 로 끝나야 합니다");
        if (layer == null) {
            if (top.equals("global") && p[1].equals("config") && !c.equals(PackageRules.class)
                    && !simple.endsWith("Config") && !simple.endsWith("Properties"))
                out.add(c.getName() + ": global/config/ 클래스 이름은 Config 또는 Properties 로 끝나야 합니다");
            return;
        }
        switch (layer) {
            case "controller" -> {
                if (!simple.endsWith("Controller")) out.add(c.getName() + ": controller/ 에는 XxxController 만 둡니다");
            }
            case "service" -> {
                // 인터페이스·추상 클래스·정적 도우미까지 예외 없이. 데이터만 담는 클래스는 dto/ 의 XxxDto 로 옮긴다
                if (!simple.endsWith("Service"))
                    out.add(c.getName() + ": service/ 의 모든 클래스 이름은 Service 로 끝나야 합니다 (데이터만 담는 클래스는 dto/ 에 XxxDto 로)");
            }
            case "dto" -> {
                if (!(simple.endsWith("Request") || simple.endsWith("Response") || simple.endsWith("Dto")))
                    out.add(c.getName() + ": dto/ 클래스 이름은 Request, Response, Dto 중 하나로 끝나야 합니다 (묶음 XxxDtos 금지)");
            }
            case "repository" -> {
                if (!simple.endsWith("Repository")) out.add(c.getName() + ": repository/ 클래스 이름은 Repository 로 끝나야 합니다");
            }
            case "entity" -> {
                if (!(entity || c.isAnnotationPresent(Embeddable.class) || c.isEnum()))
                    out.add(c.getName() + ": entity/ 에는 @Entity(와 그 enum, @Embeddable)만 둡니다");
                if (simple.endsWith("Record") || simple.endsWith("Entity"))
                    out.add(c.getName() + ": 엔티티 이름에 Record, Entity 끝말을 붙이지 않습니다");
            }
            case "exception" -> {
                if (!throwable) out.add(c.getName() + ": exception/ 에는 예외만 둡니다");
            }
            default -> { }
        }
    }

    /** PairApplication 과 같은 곳(build/classes/java/main 또는 jar 안)에 있는 클래스만. 테스트 클래스는 뺀다 */
    static List<String> mainClassNames() {
        URL app = PackageRules.class.getResource("/com/pair/PairApplication.class");
        if (app == null) throw new IllegalStateException("PairApplication.class 를 찾지 못했습니다");
        String root = app.toString().substring(0, app.toString().length() - "com/pair/PairApplication.class".length());
        List<String> names = new ArrayList<>();
        try {
            Resource[] all = new PathMatchingResourcePatternResolver(PackageRules.class.getClassLoader())
                    .getResources("classpath*:com/pair/**/*.class");
            for (Resource r : all) {
                String url = r.getURL().toString();
                if (!url.startsWith(root)) continue;
                String path = url.substring(root.length());
                if (path.contains("$") || path.endsWith("package-info.class") || path.endsWith("module-info.class")) continue;
                names.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        names.sort(null);
        return names;
    }
}
