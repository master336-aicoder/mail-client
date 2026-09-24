package com.mail.task;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public ApiResponse<List<Task>> list() {
        return ApiResponse.ok(taskService.list(CurrentUser.requireId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable Long id) {
        Task t = taskService.retry(CurrentUser.requireId(), id);
        return ApiResponse.ok(Map.of("taskId", t.getId()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        taskService.cancel(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }
}