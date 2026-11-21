package com.pair.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.attempt.AttemptRecordRepository;
import com.pair.taskgen.TaskFileStorage;

/**
 * 응시자·관리자가 보는 과제 PDF 자료. 응시 파일로 복사되지 않는 PDF(storage_key)만 다룬다.
 * 과제 내용은 원본 과제에 있으므로 실전 복사본은 원본 과제의 자료를 쓴다.
 * 숨은 파일(HIDDEN)과 목록에 없는 경로는 "없는 자료"(404)다.
 */
@Service
public class TaskMaterialService {

    private static final Logger log = LoggerFactory.getLogger(TaskMaterialService.class);
    static final String PDF = "application/pdf";
    /** 쪽 글을 한 번에 줄 수 있는 최대 쪽수 */
    public static final int MAX_PAGES = 20;
    private static final Pattern PART = Pattern.compile("(\\d{1,6})(?:-(\\d{1,6}))?");

    public record Material(String path, String mimeType, long sizeBytes, int pages) {}

    public record PageText(int page, String text) {}

    public record PdfFile(String fileName, byte[] bytes) {}

    private final AttemptRecordRepository attempts;
    private final TaskCatalog catalog;
    private final TaskFileRecordRepository files;
    private final DocumentPageRecordRepository pages;
    private final TaskFileStorage storage;

    public TaskMaterialService(AttemptRecordRepository attempts, TaskCatalog catalog, TaskFileRecordRepository files,
            DocumentPageRecordRepository pages, TaskFileStorage storage) {
        this.attempts = attempts;
        this.catalog = catalog;
        this.files = files;
        this.pages = pages;
        this.storage = storage;
    }

    /** 이 응시 과제의 PDF 자료 목록 (경로 순) */
    @Transactional(readOnly = true)
    public List<Material> listForAttempt(long attemptId) {
        return list(contentTaskId(attemptTask(attemptId)));
    }

    @Transactional(readOnly = true)
    public PdfFile fileForAttempt(long attemptId, String path) {
        return file(contentTaskId(attemptTask(attemptId)), path);
    }

    @Transactional(readOnly = true)
    public List<PageText> pagesForAttempt(long attemptId, String path, String pagesParam) {
        return pageTexts(contentTaskId(attemptTask(attemptId)), path, pagesParam);
    }

    /** 채팅에 붙일 PDF 쪽 글. pages 는 쪽 번호를 "1-3,5" 처럼 꼭 지정해야 한다(문서 전체를 몰래 보내지 않기 위해) */
    public record AttachText(long taskFileId, String fileName, String pages, String text) {}

    @Transactional(readOnly = true)
    public AttachText attachForAttempt(long attemptId, String path, String pagesParam) {
        if (pagesParam == null || pagesParam.isBlank()) throw new IllegalArgumentException("PDF 는 첨부할 쪽을 지정해 주세요. 예: 1-2");
        long taskId = contentTaskId(attemptTask(attemptId));
        TaskFileRecord f = require(taskId, path);
        List<PageText> texts = pageTexts(taskId, path, pagesParam);
        StringBuilder text = new StringBuilder();
        for (PageText p : texts) text.append("--- ").append(p.page()).append("쪽 ---\n").append(p.text()).append('\n');
        String name = f.getPath().substring(f.getPath().lastIndexOf('/') + 1);
        return new AttachText(f.getId(), name, compactPages(texts.stream().map(PageText::page).toList()), text.toString().strip());
    }

    /** [1,2,3,5] → "1-3,5" */
    static String compactPages(List<Integer> sorted) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); ) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(sorted.get(i));
            if (j > i) sb.append('-').append(sorted.get(j));
            i = j + 1;
        }
        return sb.toString();
    }

    /** 관리자 검수용: 과제 code (공개 전 과제도 된다) */
    @Transactional(readOnly = true)
    public List<Material> listForTask(String code) {
        return list(contentTaskId(catalog.require(code)));
    }

    @Transactional(readOnly = true)
    public PdfFile fileForTask(String code, String path) {
        return file(contentTaskId(catalog.require(code)), path);
    }

    @Transactional(readOnly = true)
    public List<PageText> pagesForTask(String code, String path, String pagesParam) {
        return pageTexts(contentTaskId(catalog.require(code)), path, pagesParam);
    }

    /** 응시의 작업 파일이 과제에서 읽기 전용(task_files.read_only)으로 정해졌는지. 과제에 없는 경로(새로 만든 파일)는 아니다 */
    @Transactional(readOnly = true)
    public boolean readOnlyForAttempt(long attemptId, String path) {
        return files.findByTaskIdAndPath(contentTaskId(attemptTask(attemptId)), path).map(TaskFileRecord::isReadOnly).orElse(false);
    }

    private TaskRecord attemptTask(long attemptId) {
        var a = attempts.findById(attemptId).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + attemptId));
        return catalog.require(a.getTaskId());
    }

    /** 자료가 들어 있는 과제(실전 복사본이면 원본)의 번호 */
    private long contentTaskId(TaskRecord t) {
        return catalog.require(catalog.definitionCode(t)).getId();
    }

    private List<Material> list(long taskId) {
        List<TaskFileRecord> pdfs = files.findByTaskIdAndVisibilityOrderByPathAsc(taskId, TaskFileRecord.VISIBLE).stream()
                .filter(TaskMaterialService::isPdf).toList();
        if (pdfs.isEmpty()) return List.of();
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : pages.countByFiles(pdfs.stream().map(TaskFileRecord::getId).toList())) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        List<Material> out = new ArrayList<>();
        for (TaskFileRecord f : pdfs) out.add(new Material(f.getPath(), f.getMimeType(), f.getSizeBytes(), counts.getOrDefault(f.getId(), 0)));
        return out;
    }

    private PdfFile file(long taskId, String path) {
        TaskFileRecord f = require(taskId, path);
        byte[] bytes = storage.get(f.getStorageKey()).orElseThrow(() -> {
            log.warn("PDF 자료가 저장소에 없습니다: task={} path={} key={}", taskId, f.getPath(), f.getStorageKey());
            return new NoSuchElementException("자료 파일을 찾을 수 없습니다.");
        });
        String name = f.getPath().substring(f.getPath().lastIndexOf('/') + 1);
        return new PdfFile(name, bytes);
    }

    private List<PageText> pageTexts(long taskId, String path, String spec) {
        TaskFileRecord f = require(taskId, path);
        List<DocumentPageRecord> all = pages.findByTaskFileIdOrderByPageNoAsc(f.getId());
        if (spec == null || spec.isBlank()) {
            if (all.size() > MAX_PAGES) throw new IllegalArgumentException("쪽이 " + MAX_PAGES + "쪽을 넘습니다. pages 로 쪽을 골라 주세요.");
            return all.stream().map(p -> new PageText(p.getPageNo(), p.getText())).toList();
        }
        Map<Integer, String> byNo = new HashMap<>();
        all.forEach(p -> byNo.put(p.getPageNo(), p.getText()));
        List<PageText> out = new ArrayList<>();
        for (int no : parsePages(spec)) {
            String text = byNo.get(no);
            if (text == null) throw new IllegalArgumentException(no + "쪽이 없습니다. 이 문서는 " + all.size() + "쪽입니다.");
            out.add(new PageText(no, text));
        }
        return out;
    }

    /** "1,3-4" → [1,3,4] (중복 제거, 쪽 순). 형식이 틀리거나 20쪽을 넘으면 IllegalArgumentException */
    static List<Integer> parsePages(String spec) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : spec.split(",", -1)) {
            Matcher m = PART.matcher(part.trim());
            if (!m.matches()) throw new IllegalArgumentException("pages 형식이 올바르지 않습니다. 예: 1,3-4");
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
            if (from < 1 || to < from) throw new IllegalArgumentException("쪽 범위가 올바르지 않습니다: " + part.trim());
            if (to - from + 1 > MAX_PAGES) throw new IllegalArgumentException("한 번에 최대 " + MAX_PAGES + "쪽까지 볼 수 있습니다.");
            for (int i = from; i <= to; i++) set.add(i);
            if (set.size() > MAX_PAGES) throw new IllegalArgumentException("한 번에 최대 " + MAX_PAGES + "쪽까지 볼 수 있습니다.");
        }
        return List.copyOf(set);
    }

    /** 목록에 나오는 파일(보이는 PDF)만 허용. 경로 조작·숨은 파일·다른 과제 파일은 모두 여기서 404 */
    private TaskFileRecord require(long taskId, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path 가 필요합니다.");
        return files.findByTaskIdAndPath(taskId, path).filter(f -> TaskFileRecord.VISIBLE.equals(f.getVisibility()))
                .filter(TaskMaterialService::isPdf).orElseThrow(() -> new NoSuchElementException("없는 자료입니다: " + path));
    }

    private static boolean isPdf(TaskFileRecord f) {
        return f.getStorageKey() != null && PDF.equals(f.getMimeType());
    }
}
