package com.pair.domain.task.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.task.service.TaskCatalogService;
import com.pair.domain.task.dto.TaskDefDto;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskCatalogService catalog;

    public TaskController(TaskCatalogService catalog) {
        this.catalog = catalog;
    }

    /** 자가진단 과제 목록 (응시자용). track=dev|biz 로 직군을 거를 수 있다 */
    @GetMapping("/tasks")
    public List<TaskCatalogService.Summary> taskList(@RequestParam(required = false) String track) {
        return catalog.selfTasks(track);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskDefDto task(@PathVariable String taskId) {
        return catalog.publicSelfDefinition(taskId);
    }
}
