package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자동 생성에서 과제마다 다른 함정 유형(DEV R1~R6, BIZ B1~B11 중 8개)을 배정한다(설계 7장).
 * 유형 목록은 resources/taskgen/flaw-types-dev.md·-biz.md 의 "R1 이름 — 설명" 줄에서 읽는다.
 * 배정 규칙: 최근 과제의 _gen/report.json 에서 AI 단독 풀이가 놓친 비율이 높은 유형을 먼저 쓰고(기록 MIN_RECORDS 개 이상인 유형),
 * 기록이 그보다 적은 유형은 기록이 적은 것부터 고르게 그 뒤에 쓴다. count 가 유형 수보다 많으면 처음부터 다시 돈다.
 * 기록은 자동 생성이 trap 맨 앞에 붙이는 "[R4 적용 순서]" 표시로 찾는다.
 */
@Component
public class TrapTypeService {

    private static final Logger log = LoggerFactory.getLogger(TrapTypeService.class);
    /** 놓침 비율을 믿을 만큼의 기록 수(이보다 적으면 '기록 적음'으로 고르게 돌린다) */
    static final int MIN_RECORDS = 5;
    private static final Pattern LINE = Pattern.compile("^([RB]\\d+) (.+?) — (.*)$");
    private static final Pattern TAG = Pattern.compile("^\\s*\\[([RB]\\d+)[\\s\\]]");

    /** 함정 유형 하나. label 은 "R4 적용 순서" */
    public record TrapType(String id, String name, String description) {
        public String label() {
            return id + " " + name;
        }
    }

    /** 유형 하나의 AI 단독 풀이 기록: 놓친 수·잡은 수 */
    public record SoloRecord(int missed, int caught) {
        int total() {
            return missed + caught;
        }

        double missRate() {
            return total() == 0 ? 0 : (double) missed / total();
        }
    }

    private final TaskRepository tasks;
    private final GeneratedTaskRepository generated;
    private final ObjectMapper json;

    public TrapTypeService(TaskRepository tasks, GeneratedTaskRepository generated, ObjectMapper json) {
        this.tasks = tasks;
        this.generated = generated;
        this.json = json;
    }

    /** 직군(DEV/BIZ)의 유형 목록 */
    public static List<TrapType> types(String track) {
        String file = "BIZ".equals(track) ? "taskgen/flaw-types-biz.md" : "taskgen/flaw-types-dev.md";
        List<TrapType> out = new ArrayList<>();
        try (InputStream in = TrapTypeService.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) throw new IllegalStateException("함정 유형 파일이 없습니다: " + file);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                Matcher m = LINE.matcher(line.strip());
                if (m.matches()) out.add(new TrapType(m.group(1), m.group(2).strip(), m.group(3).strip()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        out.sort(Comparator.comparingInt(t -> Integer.parseInt(t.id().substring(1))));
        return out;
    }

    /** count 개의 유형을 최근 기록을 보고 배정한다 */
    public List<TrapType> assign(String track, int count) {
        return assign(types(track), history(), count);
    }

    /**
     * 배정 규칙만 담은 순수 함수. records 에 없는 유형은 기록 0.
     * 순서: (1) 기록이 MIN_RECORDS 개 이상인 유형을 놓침 비율 높은 순(같으면 번호 순), (2) 나머지를 기록 적은 순(같으면 번호 순). 모자라면 처음부터 다시 돈다
     */
    static List<TrapType> assign(List<TrapType> types, Map<String, SoloRecord> records, int count) {
        if (types.isEmpty() || count < 1) return List.of();
        List<TrapType> known = new ArrayList<>();
        List<TrapType> sparse = new ArrayList<>();
        for (TrapType t : types) (records.getOrDefault(t.id(), new SoloRecord(0, 0)).total() >= MIN_RECORDS ? known : sparse).add(t);
        known.sort(Comparator.comparingDouble((TrapType t) -> -records.get(t.id()).missRate()));
        sparse.sort(Comparator.comparingInt((TrapType t) -> records.getOrDefault(t.id(), new SoloRecord(0, 0)).total()));
        List<TrapType> order = new ArrayList<>(known);
        order.addAll(sparse);
        List<TrapType> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(order.get(i % order.size()));
        return out;
    }

    /** 최근 과제 50개의 report.json 에서 유형별 AI 단독 풀이 기록을 모은다. 읽을 수 없는 과제는 건너뛴다 */
    Map<String, SoloRecord> history() {
        Map<String, int[]> raw = new LinkedHashMap<>();
        for (Task t : tasks.findTop50ByOrderByIdDesc()) {
            try {
                JsonNode report = json.readTree(generated.report(t.getCode()));
                Matcher m = TAG.matcher(report.path("form").path("trap").asText(""));
                if (!m.find()) continue;
                String status = report.path("details").path("solo").path("status").asText("");
                int[] c = raw.computeIfAbsent(m.group(1), k -> new int[2]);
                if (status.equals("missed")) c[0]++;
                else if (status.equals("caught")) c[1]++;
            } catch (Exception e) {
                log.debug("report 를 읽지 못해 건너뜁니다: {} {}", t.getCode(), e.getMessage());
            }
        }
        Map<String, SoloRecord> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, new SoloRecord(v[0], v[1])));
        return out;
    }
}
