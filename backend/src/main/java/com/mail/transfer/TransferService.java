package com.mail.transfer;

import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.task.TaskService;
import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OST / PST 相关工作区：生成导出文件（供 Outlook 导入）。
 *
 * <p>导出通过 {@link PstExportTaskHandler} 在后台线程池中异步执行，
 * 任务完成后结果（文件相对路径）写入 Task.result，前端据此轮询状态并获取下载地址。
 * 导入（PST 读取）为预留接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TaskService taskService;

    @Value("${mail.app.pst-export-dir:./data/exports}")
    private String pstDir;

    /**
     * 创建异步导出任务，返回任务 id 及初始状态。
     */
    public Map<String, Object> startExport(Long accountId, List<Long> folderIds) {
        Long userId = CurrentUser.requireId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountId", accountId);
        params.put("folderIds", folderIds);
        String paramsJson;
        try {
            paramsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException("导出参数序列化失败");
        }
        Task task = taskService.submit(userId, "PST 导出", PstExportTaskHandler.TYPE, accountId, paramsJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", String.valueOf(task.getId()));
        out.put("status", task.getStatus());
        out.put("progress", task.getProgress());
        return out;
    }

    /**
     * 查询导出任务状态，任务完成后附带下载地址。
     */
    public Map<String, Object> status(Long userId, String taskId) {
        Long id = parseTaskId(taskId);
        return taskService.status(userId, id);
    }

    /**
     * 校验并返回导出产物的下载资源。
     */
    public ResponseEntity<Resource> download(Long userId, String taskId) {
        Long id = parseTaskId(taskId);
        Task task = taskService.getForDownload(userId, id);
        return serveFile(userId, task.getResult());
    }

    private Long parseTaskId(String taskId) {
        try {
            // 仅接受数字任务 id（旧的 export-<ts> 字符串不再使用）
            return Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            throw new BizException("无效的任务 id");
        }
    }

    private ResponseEntity<Resource> serveFile(Long userId, String result) {
        if (result == null || result.isBlank()) {
            throw new BizException("导出任务尚无产物");
        }
        Path base = Paths.get(pstDir).toAbsolutePath().normalize();
        Path file = base.resolve(result).normalize();
        // 防止路径穿越：仅允许在导出根目录内
        if (!file.startsWith(base)) {
            throw new BizException("非法的导出文件");
        }
        if (!Files.exists(file)) {
            throw new BizException("导出文件不存在或已清理");
        }
        FileSystemResource res = new FileSystemResource(file.toFile());
        String filename = file.getFileName().toString();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(filename.endsWith(".zip")
                        ? MediaType.parseMediaType("application/zip")
                        : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(res);
    }
}