package com.mail.folder;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.folder.domain.Folder;
import com.mail.folder.domain.FolderRepository;
import com.mail.mail.domain.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件夹管理：CRUD、树形层级、未读数汇总。
 *
 * <p>目录体系分为两类：
 * <ul>
 *   <li><b>合并视图（accountId 为 null）</b>：默认的“全部账户”文件夹，聚合所有账户下同名文件夹的邮件。</li>
 *   <li><b>账户级文件夹（accountId 非空）</b>：每个账户各自的收件箱/草稿/已发送/已删除等文件夹。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FolderService {

    /** 默认文件夹名称与顺序（v1 去除垃圾箱，使用已删除）。 */
    private static final String[] DEFAULT_NAMES = {"收件箱", "草稿", "已发送", "已删除"};
    private static final int[] DEFAULT_SEQS = {0, 2, 3, 4};

    private final FolderRepository folderRepository;
    private final MessageRepository messageRepository;
    private final MailAccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<Folder> list(Long userId) {
        return folderRepository.findByUserIdOrderBySeqAsc(userId);
    }

    /**
     * 返回扁平节点列表（含未读数与账户信息）。
     *
     * <p>每个节点携带：
     * <ul>
     *   <li>{@code accountId}：账户文件夹为账户 ID，合并文件夹为 null；</li>
     *   <li>{@code accountEmail}：所属账户邮箱（合并视图为空字符串）；</li>
     *   <li>{@code isMerged}：是否合并视图；</li>
     *   <li>{@code total}/{@code unread}：邮件数（合并视图聚合所有同名账户文件夹）。</li>
     * </ul>
     */
    @Transactional
    public List<Map<String, Object>> tree() {
        Long userId = CurrentUser.requireId();
        // 为所有已绑定账户补齐各自独立的默认文件夹
        for (MailAccount a : accountRepository.findByUserId(userId)) {
            ensureAccountDefaults(userId, a.getId());
        }
        List<Folder> folders = folderRepository.findByUserIdOrderBySeqAsc(userId);
        if (folders.isEmpty()) {
            ensureDefaults(userId);
            folders = folderRepository.findByUserIdOrderBySeqAsc(userId);
        }

        List<Folder> accountFolders = new ArrayList<>();
        for (Folder f : folders) {
            if (f.getAccountId() != null) accountFolders.add(f);
        }
        Map<Long, String> accountEmail = loadAccountEmails(userId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Folder f : folders) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", f.getId());
            node.put("name", f.getName());
            node.put("parentId", f.getParentId());
            node.put("accountId", f.getAccountId());
            node.put("accountEmail",
                    f.getAccountId() != null ? accountEmail.getOrDefault(f.getAccountId(), "") : "");
            node.put("isMerged", f.getAccountId() == null);

            long total;
            if (f.getAccountId() == null) {
                // 合并视图：聚合所有账户同名文件夹
                total = 0;
                for (Folder af : accountFolders) {
                    if (af.getName() != null && af.getName().equals(f.getName())) {
                        total += messageRepository.countByFolderIdAndDeletedFalse(af.getId());
                    }
                }
            } else {
                total = messageRepository.countByFolderIdAndDeletedFalse(f.getId());
            }
            node.put("total", total);
            node.put("unread", total); // v1 简化未读统计
            result.add(node);
        }
        return result;
    }

    private Map<Long, String> loadAccountEmails(Long userId) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (MailAccount a : accountRepository.findByUserId(userId)) {
            map.put(a.getId(), a.getEmail());
        }
        return map;
    }

    @Transactional
    public Folder create(Folder folder) {
        Long userId = CurrentUser.requireId();
        if (folder.getName() == null || folder.getName().isBlank()) {
            throw new BizException("文件夹名称不能为空");
        }
        folder.setId(null);
        folder.setUserId(userId);
        return folderRepository.save(folder);
    }

    @Transactional
    public Folder update(Long id, Folder update) {
        Long userId = CurrentUser.requireId();
        Folder f = folderRepository.findById(id)
                .orElseThrow(() -> new BizException("文件夹不存在"));
        if (!f.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (update.getName() != null) {
            if (update.getName().isBlank()) throw new BizException("文件夹名称不能为空");
            f.setName(update.getName());
        }
        if (update.getParentId() != null) f.setParentId(update.getParentId());
        if (update.getSeq() != null) f.setSeq(update.getSeq());
        return folderRepository.save(f);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = CurrentUser.requireId();
        Folder f = folderRepository.findById(id)
                .orElseThrow(() -> new BizException("文件夹不存在"));
        if (!f.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        folderRepository.delete(f);
    }

    /**
     * 确保合并视图默认文件夹存在（收件箱/草稿/已发送/已删除），首次使用时调用。
     */
    @Transactional
    public void ensureDefaults(Long userId) {
        if (folderRepository.countByUserId(userId) > 0) return;
        createDefaults(userId, null);
    }

    /**
     * 确保某账户的默认文件夹存在（收件箱/草稿/已发送/已删除）。
     */
    @Transactional
    public void ensureAccountDefaults(Long userId, Long accountId) {
        List<Folder> existing = folderRepository.findByUserIdAndAccountIdOrderBySeqAsc(userId, accountId);
        if (existing.isEmpty()) {
            createDefaults(userId, accountId);
        } else {
            // 补齐可能缺失的默认文件夹
            for (int i = 0; i < DEFAULT_NAMES.length; i++) {
                boolean present = false;
                for (Folder f : existing) {
                    if (DEFAULT_NAMES[i].equals(f.getName())) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    Folder f = new Folder();
                    f.setUserId(userId);
                    f.setAccountId(accountId);
                    f.setName(DEFAULT_NAMES[i]);
                    f.setSeq(DEFAULT_SEQS[i]);
                    folderRepository.save(f);
                }
            }
        }
    }

    private void createDefaults(Long userId, Long accountId) {
        for (int i = 0; i < DEFAULT_NAMES.length; i++) {
            Folder f = new Folder();
            f.setUserId(userId);
            f.setAccountId(accountId);
            f.setName(DEFAULT_NAMES[i]);
            f.setSeq(DEFAULT_SEQS[i]);
            folderRepository.save(f);
        }
    }

    /**
     * 返回某账户下指定名称的文件夹（不存在则创建）。
     */
    @Transactional
    public Long resolveAccountFolderId(Long userId, Long accountId, String name) {
        List<Folder> folders = folderRepository.findByUserIdAndAccountIdOrderBySeqAsc(userId, accountId);
        for (Folder f : folders) {
            if (name.equals(f.getName())) return f.getId();
        }
        Folder f = new Folder();
        f.setUserId(userId);
        f.setAccountId(accountId);
        f.setName(name);
        f.setSeq(DEFAULT_SEQS.length);
        return folderRepository.save(f).getId();
    }

    /**
     * 返回某账户全部文件夹 ID。
     */
    @Transactional(readOnly = true)
    public List<Long> accountFolderIds(Long userId, Long accountId) {
        return folderRepository.findByUserIdAndAccountIdOrderBySeqAsc(userId, accountId)
                .stream().map(Folder::getId).toList();
    }
}
