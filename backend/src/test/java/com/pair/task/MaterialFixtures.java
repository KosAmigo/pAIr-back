package com.pair.task;

import java.nio.charset.StandardCharsets;

import com.pair.taskgen.TaskFileStorage;

/** PDF 자료 테스트용 시드: 과제 2(비개발 자가진단)에 PDF 2개·숨은 PDF·텍스트 자료, 과제 1에 다른 과제 PDF */
final class MaterialFixtures {

    static final String PDF_PATH = "자료/요금 안내.pdf";
    static final String PDF2_PATH = "자료/경쟁사 비교.pdf";
    static final String HIDDEN_PATH = "자료/정답근거.pdf";
    static final String OTHER_PATH = "자료/다른과제.pdf";
    static final byte[] BYTES = "%PDF-1.4 test".getBytes(StandardCharsets.ISO_8859_1);

    private MaterialFixtures() {}

    static void seed(TaskRecordRepository tasks, TaskFileRecordRepository files, DocumentPageRecordRepository pages, TaskFileStorage storage) {
        TestTasks.seed(tasks);
        pages.deleteAll(pages.findAll());
        files.deleteAll(files.findAll().stream().filter(f -> f.getTaskId() <= 2).toList());
        files.save(new TaskFileRecord(2L, "자료/표.csv", "a,b\n1,2\n", "text/csv", 8, TaskFileRecord.VISIBLE, true));
        pdf(files, storage, 2L, PDF_PATH, 1, TaskFileRecord.VISIBLE);
        pdf(files, storage, 2L, PDF2_PATH, 2, TaskFileRecord.VISIBLE);
        pdf(files, storage, 2L, HIDDEN_PATH, 3, TaskFileRecord.HIDDEN);
        pdf(files, storage, 1L, OTHER_PATH, 1, TaskFileRecord.VISIBLE);
        TaskFileRecord f = files.findByTaskIdAndPath(2L, PDF_PATH).orElseThrow();
        for (int i = 1; i <= 25; i++) pages.save(new DocumentPageRecord(f.getId(), i, i + "쪽 글"));
        pages.save(new DocumentPageRecord(files.findByTaskIdAndPath(2L, PDF2_PATH).orElseThrow().getId(), 1, "비교 1쪽"));
        pages.save(new DocumentPageRecord(files.findByTaskIdAndPath(2L, HIDDEN_PATH).orElseThrow().getId(), 1, "숨은 글"));
    }

    private static void pdf(TaskFileRecordRepository files, TaskFileStorage storage, long task, String path, int index, String visibility) {
        TaskFileRecord r = new TaskFileRecord(task, path, null, "application/pdf", BYTES.length, visibility, true);
        r.setStorageKey(TaskFileStorage.keyOf(task, index));
        files.save(r);
        storage.put(r.getStorageKey(), BYTES);
    }
}
