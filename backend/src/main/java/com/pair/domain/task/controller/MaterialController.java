package com.pair.domain.task.controller;

import com.pair.domain.task.service.MaterialPdfHeaderService;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.task.service.TaskMaterialService;
import com.pair.domain.task.service.TaskMaterialService.Material;
import com.pair.domain.task.service.TaskMaterialService.PageText;

/**
 * 응시자용 PDF 자료 API. 응시 주인 검사는 AttemptOwnerInterceptor(/api/attempts/{id}/**)가 한다.
 * 응시 상태와 상관없이 읽을 수 있다(제출 후 복기·리포트에서도).
 */
@RestController
@RequestMapping("/api/attempts/{id}/materials")
public class MaterialController {

    private final TaskMaterialService materials;

    public MaterialController(TaskMaterialService materials) {
        this.materials = materials;
    }

    @GetMapping
    public List<Material> list(@PathVariable long id) {
        return materials.listForAttempt(id);
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(@PathVariable long id, @RequestParam String path) {
        return MaterialPdfHeaderService.pdf(materials.fileForAttempt(id, path));
    }

    @GetMapping("/pages")
    public List<PageText> pages(@PathVariable long id, @RequestParam String path, @RequestParam(required = false) String pages) {
        return materials.pagesForAttempt(id, path, pages);
    }
}
