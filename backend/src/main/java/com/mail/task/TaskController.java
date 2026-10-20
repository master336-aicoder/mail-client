package com.mail.task;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.common.PageResult;
import com.mail.config.AppProperties;
import com.mail.task.domain.Task;
import com.mail.task.domain.TaskRun;
import com.mail.task.dto.TaskBatchRequest;
import com.mail.task.dto.TaskListItem;
import com.mail.task.dto.TaskRunBatchRequest;
import com.mail.task.dto.TaskRunItem;
import com.mail.task.dto.TaskRunPolicyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台任务：任务定义列表、立即执行/中止/启停、执行记录与产物附件下载。
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskWatchdog watchdog;
    private final AppProperties appProperties;

    /** 运行时信息：定时调度是否开启（启动参数）+ 后台任务守护进程状态，供界面顶部展示。 */
    @GetMapping("/runtime")
    public ApiResponse<Map<String, Object>> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedulingEnabled", appProperties.isSchedulingEnabled());
        Map<String, Object> wd = new LinkedHashMap<>();
        wd.put("active", watchdog.isActive());
        wd.put("intervalSec", TaskWatchdog.SWEEP_INTERVAL_SEC);
        wd.put("lastSweepAt", watchdog.getLastSweepAt());
        wd.put("totalRecovered", watchdog.getTotalRecovered());
        out.put("watchdog", wd);
        return ApiResponse.ok(out);
    }

    /** 立即触发一次守护巡检，回收孤儿执行；返回本轮回收条数。 */
    @PostMapping("/watchdog-sweep")
    public ApiResponse<Map<String, Object>> watchdogSweep() {
        return ApiResponse.ok(Map.of("recovered", watchdog.sweepNow()));
    }

    @GetMapping
    public ApiResponse<PageResult<TaskListItem>> list(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String type) {
        return ApiResponse.ok(taskService.page(CurrentUser.requireId(), page, size, type));
    }

    /** 某任务的执行记录，新的在前。 */
    @GetMapping("/{id}/runs")
    public ApiResponse<PageResult<TaskRunItem>> runs(@PathVariable Long id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.runs(CurrentUser.requireId(), id, page, size));
    }

    /** 任务最近一轮执行状态（含产物下载地址），供导出等前端流程轮询。 */
    @GetMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long id) {
        return ApiResponse.ok(taskService.status(CurrentUser.requireId(), id));
    }

    /** 批量删除执行记录：ids=选中，taskId=该任务全部。进行中的记录不受影响。 */
    @PostMapping("/runs/batch-delete")
    public ApiResponse<Map<String, Object>> batchDeleteRuns(@RequestBody TaskRunBatchRequest req) {
        return ApiResponse.ok(Map.of("deleted",
                taskService.batchDeleteRuns(CurrentUser.requireId(), req.getIds(), req.getTaskId())));
    }

    /** 批量导出执行记录：创建一个一次性导出任务，产物 zip 在其执行记录中下载。 */
    @PostMapping("/runs/batch-export")
    public ApiResponse<Map<String, Object>> batchExportRuns(@RequestBody TaskRunBatchRequest req) {
        Task task = taskService.submitRunsExport(CurrentUser.requireId(), req.getIds(), req.getTaskId());
        return ApiResponse.ok(Map.of("taskId", task.getId()));
    }

    @PostMapping("/{id}/run")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long id) {
        TaskRun run = taskService.runNow(CurrentUser.requireId(), id);
        return ApiResponse.ok(Map.of("taskId", id, "runId", run.getId()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        taskService.cancel(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 启用周期任务：立即纳入调度。 */
    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        taskService.setEnabled(CurrentUser.requireId(), id, true);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        taskService.setEnabled(CurrentUser.requireId(), id, false);
        return ApiResponse.ok();
    }

    @PostMapping("/batch-run")
    public ApiResponse<Map<String, Object>> batchRun(@Valid @RequestBody TaskBatchRequest req) {
        return ApiResponse.ok(Map.of("started", taskService.batchRun(CurrentUser.requireId(), req.getIds())));
    }

    @PostMapping("/batch-cancel")
    public ApiResponse<Map<String, Object>> batchCancel(@Valid @RequestBody TaskBatchRequest req) {
        return ApiResponse.ok(Map.of("cancelled", taskService.batchCancel(CurrentUser.requireId(), req.getIds())));
    }

    @PostMapping("/batch-enable")
    public ApiResponse<Map<String, Object>> batchEnable(@Valid @RequestBody TaskBatchRequest req) {
        return ApiResponse.ok(Map.of("updated",
                taskService.batchSetEnabled(CurrentUser.requireId(), req.getIds(), true)));
    }

    @PostMapping("/batch-disable")
    public ApiResponse<Map<String, Object>> batchDisable(@Valid @RequestBody TaskBatchRequest req) {
        return ApiResponse.ok(Map.of("updated",
                taskService.batchSetEnabled(CurrentUser.requireId(), req.getIds(), false)));
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody TaskBatchRequest req) {
        return ApiResponse.ok(Map.of("deleted", taskService.batchDelete(CurrentUser.requireId(), req.getIds())));
    }

    /** 任务级记录保存策略；字段传 null 表示跟随全局策略。 */
    @PostMapping("/{id}/run-policy")
    public ApiResponse<Void> runPolicy(@PathVariable Long id, @RequestBody TaskRunPolicyRequest req) {
        taskService.setRunPolicy(CurrentUser.requireId(), id,
                req.getRunRetentionScope(), req.getRunRetentionDays());
        return ApiResponse.ok();
    }
}
