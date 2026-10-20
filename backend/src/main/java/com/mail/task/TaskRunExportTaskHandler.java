package com.mail.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mail.common.BizException;
import com.mail.task.domain.Task;
import com.mail.task.domain.TaskAttachment;
import com.mail.task.domain.TaskAttachmentRepository;
import com.mail.task.domain.TaskRepository;
import com.mail.task.domain.TaskRun;
import com.mail.task.domain.TaskRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 执行记录导出：把选中的（或某任务全部的）执行记录打包为 zip。
 *
 * <p>产物结构：manifest.json（清单）+ records/run-{id}.json（每条记录，含所属任务与产物附件信息）。
 * 记录里引用的导出产物 zip 若仍在磁盘上，会一并拷入 runs/ 目录，实现「连文件一起导出」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRunExportTaskHandler implements TaskHandler {

    public static final String TYPE = "task_run_export";

    private final TaskRepository taskRepository;
    private final TaskRunRepository runRepository;
    private final TaskAttachmentRepository attachmentRepository;
    private final AttachmentFiles attachmentFiles;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String name() {
        return "记录导出";
    }

    @Override
    public void run(Task task, Progress progress) throws Exception {
        if (task.getParams() == null || task.getParams().isBlank()) {
            throw new BizException("导出任务缺少参数");
        }
        Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
        List<Long> runIds = ((List<?>) params.getOrDefault("runIds", List.of())).stream()
                .map(o -> ((Number) o).longValue()).toList();
        if (runIds.isEmpty()) throw new BizException("没有要导出的执行记录");

        List<TaskRun> runs = runRepository.findByIdInOrderByIdAsc(runIds);
        progress.report(10, "准备导出 " + runs.size() + " 条执行记录…");

        Map<Long, Task> taskById = new LinkedHashMap<>();
        for (Task t : taskRepository.findAllById(runs.stream().map(TaskRun::getTaskId).distinct().toList())) {
            taskById.put(t.getId(), t);
        }
        // 归属过滤：任务不属于当前用户的记录直接剔除，剔除后为空则失败
        runs = runs.stream().filter(r -> {
            Task t = taskById.get(r.getTaskId());
            return t != null && t.getUserId().equals(task.getUserId());
        }).toList();
        if (runs.isEmpty()) throw new BizException("没有可导出的执行记录");
        Map<Long, List<TaskAttachment>> attsByRun = new LinkedHashMap<>();
        for (TaskAttachment a : attachmentRepository.findByRunIdInOrderByIdAsc(
                runs.stream().map(TaskRun::getId).toList())) {
            attsByRun.computeIfAbsent(a.getRunId(), k -> new ArrayList<>()).add(a);
        }

        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String fileName = "task-runs-" + stamp + ".zip";
        Path tmp = Files.createTempFile("task-runs-", ".zip");
        int copiedFiles = 0;
        try {
            try (OutputStream fos = Files.newOutputStream(tmp);
                 ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
                List<Map<String, Object>> manifest = new ArrayList<>();
                int idx = 0;
                for (TaskRun r : runs) {
                    progress.report(10 + (int) (85.0 * ++idx / runs.size()), "已写入 " + idx + "/" + runs.size());
                    Task t = taskById.get(r.getTaskId());
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("run", runToMap(r));
                    entry.put("task", Map.of(
                            "id", t.getId(),
                            "name", t.getName(),
                            "type", t.getType(),
                            "periodic", t.isPeriodic()));
                    List<Map<String, Object>> attList = new ArrayList<>();
                    for (TaskAttachment a : attsByRun.getOrDefault(r.getId(), List.of())) {
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("id", a.getId());
                        am.put("name", a.getName());
                        am.put("sizeBytes", a.getSizeBytes());
                        am.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
                        Path src = safeResolve(a.getPath());
                        if (src != null) {
                            String inZip = "runs/" + r.getId() + "/" + a.getName();
                            zos.putNextEntry(new ZipEntry(inZip));
                            Files.copy(src, zos);
                            zos.closeEntry();
                            am.put("includedInZip", inZip);
                            copiedFiles++;
                        }
                        attList.add(am);
                    }
                    entry.put("attachments", attList);
                    manifest.add(entry);
                    zos.putNextEntry(new ZipEntry("records/run-" + r.getId() + ".json"));
                    zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));
                    zos.closeEntry();
                }
                zos.putNextEntry(new ZipEntry("manifest.json"));
                zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                        "exportedAt", new Date().toString(),
                        "taskExportId", task.getId(),
                        "runCount", runs.size(),
                        "includedProductFiles", copiedFiles,
                        "records", manifest)));
                zos.closeEntry();
            }
            long size = Files.size(tmp);
            Path dir = attachmentFiles.ensureDir("user-" + task.getUserId());
            Path target = dir.resolve(fileName);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            progress.attach(fileName, "user-" + task.getUserId() + "/" + fileName, size, "application/zip");
            progress.result("导出 " + runs.size() + " 条执行记录，附带 " + copiedFiles + " 个产物文件");
            progress.report(100, "导出完成");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Map<String, Object> runToMap(TaskRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("taskId", r.getTaskId());
        m.put("status", r.getStatus());
        m.put("progress", r.getProgress());
        m.put("message", r.getMessage());
        m.put("error", r.getError());
        m.put("result", r.getResult());
        m.put("triggerType", r.getTriggerType());
        m.put("startedAt", r.getStartedAt() == null ? null : r.getStartedAt().toString());
        m.put("finishedAt", r.getFinishedAt() == null ? null : r.getFinishedAt().toString());
        m.put("durationMs", r.getDurationMs());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }

    /** 产物可能已被清理：解析失败或文件不存在时跳过，不让整个导出失败。 */
    private Path safeResolve(String relativePath) {
        try {
            Path p = attachmentFiles.resolve(relativePath);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}
