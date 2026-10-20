package com.mail.transfer;

import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.folder.domain.FolderRepository;
import com.mail.task.AttachmentFiles;
import com.mail.task.TaskService;
import com.mail.task.domain.Task;
import com.mail.task.domain.TaskRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PST 导入导出入口。
 *
 * <p>每次导出新建一条一次性任务并立即异步执行，产物作为该次执行的附件登记，
 * 状态查询与下载都走通用的后台任务接口（{@code /api/tasks}、{@code /api/task-attachments}）。
 * 导入：先上传 PST（分两步：上传落盘 → 提交异步解析任务），进度与结果同样走通用任务状态接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TaskService taskService;
    private final FolderRepository folderRepository;
    private final MailAccountRepository accountRepository;
    private final AttachmentFiles attachmentFiles;

    /** 上传的 PST 落盘根目录（相对产物根），导入任务从这里读取。 */
    private static final String IMPORT_SUBDIR = "import";
    /** uploadId 必须是服务端生成的 UUID，杜绝客户端传入路径。 */
    private static final java.util.regex.Pattern UPLOAD_ID =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");

    /** 创建异步导出任务，返回任务 id 及初始状态。format=zip(EML 打包，默认) 或 mbox。 */
    public Map<String, Object> startExport(Long accountId, List<Long> folderIds, String format) {
        Long userId = CurrentUser.requireId();
        if (folderIds == null || folderIds.isEmpty()) throw new BizException("请至少选择一个文件夹");
        String fmt = (format == null || format.isBlank()) ? "zip" : format.trim().toLowerCase();
        if (!fmt.equals("zip") && !fmt.equals("mbox")) {
            throw new BizException("不支持的导出格式，仅支持 zip 或 mbox");
        }
        for (Long fid : folderIds) {
            var f = folderRepository.findById(fid).orElse(null);
            if (f == null || !userId.equals(f.getUserId())) {
                throw new BizException(40300, "无权访问文件夹 " + fid);
            }
        }
        if (accountId != null && accountId != 0L) {
            var a = accountRepository.findById(accountId).orElse(null);
            if (a == null || !userId.equals(a.getUserId())) {
                throw new BizException(40300, "无权操作该账户");
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountId", accountId);
        params.put("folderIds", folderIds);
        params.put("format", fmt);
        String paramsJson;
        try {
            paramsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException("导出参数序列化失败");
        }
        Task task = taskService.submitOneOff(userId, "邮件导出", PstExportTaskHandler.TYPE, accountId, paramsJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("status", TaskRun.PENDING);
        out.put("progress", 0);
        return out;
    }

    /** 查询本次导出的最近一轮执行状态，完成时附带产物附件与下载地址。 */
    public Map<String, Object> status(Long userId, Long taskId) {
        return taskService.status(userId, taskId);
    }

    /**
     * 上传 PST/OST 文件落盘：OST 直接拒绝（java-libpst 不支持），返回服务端生成的 uploadId。
     */
    public Map<String, Object> uploadImport(MultipartFile file) {
        Long userId = CurrentUser.requireId();
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的文件");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = original.toLowerCase();
        if (lower.endsWith(".ost")) {
            throw new BizException("OST 格式无法直接解析，请在 Outlook 中「文件 → 打开 → 导出为 Outlook 数据文件(.pst)」后再导入");
        }
        if (!lower.endsWith(".pst")) {
            throw new BizException("仅支持导入 .pst 文件");
        }
        String uploadId = UUID.randomUUID().toString();
        String relativeDir = IMPORT_SUBDIR + "/user-" + userId;
        Path dir = attachmentFiles.ensureDir(relativeDir);
        Path target = dir.resolve(uploadId + ".pst");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        } catch (Exception e) {
            log.error("PST 上传落盘失败", e);
            throw new BizException("文件保存失败：" + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", uploadId);
        out.put("fileName", original);
        out.put("size", file.getSize());
        return out;
    }

    /** 提交 PST 导入一次性任务：按 uploadId 读取已上传文件，导入到指定账户文件夹。 */
    public Map<String, Object> startImport(String uploadId, Long accountId, String folderName) {
        Long userId = CurrentUser.requireId();
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            throw new BizException("无效的上传标识，请重新上传文件");
        }
        if (accountId == null) throw new BizException("请选择导入的目标账户");
        var account = accountRepository.findById(accountId).orElse(null);
        if (account == null || !userId.equals(account.getUserId())) {
            throw new BizException(40300, "无权操作该账户");
        }
        String relative = IMPORT_SUBDIR + "/user-" + userId + "/" + uploadId + ".pst";
        if (attachmentFiles.sizeOf(relative) < 0) {
            throw new BizException("上传文件不存在或已过期，请重新上传");
        }
        String name = (folderName == null || folderName.isBlank()) ? "PST 导入" : folderName.trim();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uploadId", uploadId);
        params.put("folderName", name);
        String paramsJson;
        try {
            paramsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException("导入参数序列化失败");
        }
        Task task = taskService.submitOneOff(userId, "PST 导入", PstImportTaskHandler.TYPE, accountId, paramsJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("status", TaskRun.PENDING);
        out.put("progress", 0);
        return out;
    }
}
