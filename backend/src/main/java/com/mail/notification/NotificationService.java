package com.mail.notification;

import com.mail.common.BizException;
import com.mail.common.PageResult;
import com.mail.notification.domain.Notification;
import com.mail.notification.domain.NotificationRepository;
import com.mail.notification.dto.NotificationItem;
import com.mail.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息通知（站内信）：落库 + WebSocket 实时推送，供新邮件、收发异常、文件夹清空等事件使用。
 *
 * <p>所有 {@code notify*} 方法都不会抛异常：通知是附属能力，不能把收信或发信主流程带崩。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 已读通知保留天数，超期由定时任务清理；未读的始终保留。 */
    private static final int READ_RETENTION_DAYS = 30;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int TITLE_MAX = 128;
    private static final int CONTENT_MAX = 1000;
    /** 单次公告扇出的收件人上限：超过它一次请求要写上万行，不如让管理员分批发。 */
    private static final int MAX_RECIPIENTS = 500;

    private final NotificationRepository repository;
    private final PushService pushService;

    /** 同类事件冷却：key = userId|type|title，避免周期任务每几分钟重复轰炸。 */
    private final Map<String, LocalDateTime> cooldown = new ConcurrentHashMap<>();

    /** 立即通知并推送。 */
    public void notify(Long userId, String type, String title, String content, String link) {
        insert(userId, type, title, content, link, Duration.ZERO);
    }

    /**
     * 冷却期内重复的同类通知只保留第一条。
     * 收信失败一类会随周期任务反复出现，不去重会在几分钟内塞满通知列表。
     */
    public void notifyThrottled(Long userId, String type, String title, String content,
                                String link, Duration cooldownWindow) {
        insert(userId, type, title, content, link, cooldownWindow);
    }

    /**
     * 调用方所在事务即将回滚时使用（如发信失败）：独立事务落库，通知不会被一起回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyDetached(Long userId, String type, String title, String content,
                               String link, Duration cooldownWindow) {
        insert(userId, type, title, content, link, cooldownWindow);
    }

    private void insert(Long userId, String type, String title, String content,
                        String link, Duration cooldownWindow) {
        if (userId == null) return;
        try {
            String key = userId + "|" + type + "|" + title;
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime last = cooldown.get(key);
            if (last != null && last.plus(cooldownWindow).isAfter(now)) return;
            cooldown.put(key, now);
            if (cooldown.size() > 2000) cooldown.entrySet().removeIf(e -> e.getValue().isBefore(now.minusDays(1)));

            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(cut(title, TITLE_MAX));
            n.setContent(cut(content, CONTENT_MAX));
            n.setLink(cut(link, 255));
            repository.saveAndFlush(n);
            pushAfterCommit(userId, toPushPayload(n));
        } catch (Exception e) {
            log.warn("通知写入失败 user={} type={}: {}", userId, type, e.getMessage());
        }
    }

    /**
     * 推送时机放在事务提交之后：提前推送会让前端立刻重查列表，
     * 读到尚未提交的数据（业务回滚时更会推出根本不存在的通知）。
     */
    private void pushAfterCommit(Long userId, Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pushService.publish(userId, "NOTIFICATION", payload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushService.publish(userId, "NOTIFICATION", payload);
            }
        });
    }

    private static Map<String, Object> toPushPayload(Notification n) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", n.getId());
        data.put("type", n.getType());
        data.put("title", n.getTitle());
        data.put("content", n.getContent());
        data.put("link", n.getLink());
        data.put("read", n.isRead());
        data.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
        return data;
    }

    /**
     * 消息中心检索：类型与关键字可选，空值表示不限制。
     */
    @Transactional(readOnly = true)
    public PageResult<NotificationItem> page(Long userId, boolean unreadOnly, String type, String keyword,
                                             int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pr = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Order.desc("id")));
        Page<Notification> result = repository.search(userId, unreadOnly,
                type == null ? "" : type.trim(), keyword == null ? "" : keyword.trim(), pr);
        return new PageResult<>(result.getTotalElements(), safePage, safeSize,
                result.getContent().stream().map(NotificationItem::of).toList());
    }

    /**
     * 发布站内公告：一条公告按收件人扇出成多行，共用同一批次号。
     *
     * <p>刻意不走 {@link #insert} 的冷却表：同名公告若被去重，第二个人就收不到了。
     *
     * @return 实际送达人数
     */
    @Transactional
    public int publishAnnouncement(Long senderId, List<Long> recipientIds, String title,
                                  String content, String link) {
        List<Long> targets = recipientIds == null ? List.of()
                : recipientIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (targets.isEmpty()) throw new BizException("没有可发送的用户");
        if (targets.size() > MAX_RECIPIENTS) {
            throw new BizException("单次最多发送 " + MAX_RECIPIENTS + " 个用户");
        }
        String batchNo = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<Notification> rows = new java.util.ArrayList<>();
        for (Long uid : targets) {
            Notification n = new Notification();
            n.setUserId(uid);
            n.setType(Notification.ANNOUNCEMENT);
            n.setTitle(cut(title, TITLE_MAX));
            n.setContent(cut(content, CONTENT_MAX));
            n.setLink(cut(link, 255));
            n.setSenderId(senderId);
            n.setBatchNo(batchNo);
            rows.add(n);
        }
        repository.saveAllAndFlush(rows);
        for (Notification n : rows) pushAfterCommit(n.getUserId(), toPushPayload(n));
        log.info("站内公告发布 batch={} sender={} 送达 {} 人", batchNo, senderId, rows.size());
        return rows.size();
    }

    /** 管理端发布历史：按批次汇总送达与已读人数。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> announcements(int limit) {
        int safe = Math.min(Math.max(limit, 1), 100);
        List<Object[]> groups = repository.announcementBatches(PageRequest.of(0, safe));
        List<Long> sampleIds = groups.stream().map(g -> (Long) g[1]).toList();
        if (sampleIds.isEmpty()) return List.of();
        Map<Long, Notification> byId = new java.util.HashMap<>();
        for (Notification n : repository.findByIdIn(sampleIds)) byId.put(n.getId(), n);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object[] g : groups) {
            Notification n = byId.get((Long) g[1]);
            if (n == null) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("batchNo", g[0]);
            m.put("title", n.getTitle());
            m.put("content", n.getContent());
            m.put("link", n.getLink());
            m.put("senderId", n.getSenderId());
            m.put("createdAt", n.getCreatedAt());
            m.put("delivered", g[2]);
            m.put("read", g[3]);
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = repository.findById(id).orElse(null);
        if (n == null || !userId.equals(n.getUserId())) {
            throw new BizException(40300, "无权操作");
        }
        n.setRead(true);
        repository.save(n);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId);
    }

    /** 批量删除：归属校验由 where user_id 保证，返回实际删除数。 */
    @Transactional
    public int delete(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BizException("请选择要删除的消息");
        return repository.deleteByUserAndIdIn(userId, ids);
    }

    @Transactional
    public int clearRead(Long userId) {
        return repository.deleteRead(userId);
    }

    /** 每小时清理超期的已读通知。 */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    @Transactional
    public void pruneExpired() {
        int removed = repository.deleteReadBefore(LocalDateTime.now().minusDays(READ_RETENTION_DAYS));
        if (removed > 0) log.info("清理超期已读通知 {} 条", removed);
    }

    private static String cut(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
