package com.mail.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 消息中心检索：未读、类型、关键字三个可选条件。
     * 空串代表不启用该条件（避免 :param is null 在 MySQL 上的未定型 null 问题）。
     */
    @Query("select n from Notification n where n.userId = :userId "
            + "and (:unreadOnly = false or n.read = false) "
            + "and (:type = '' or n.type = :type) "
            + "and (:kw = '' or lower(n.title) like lower(concat('%', :kw, '%')) "
            + "or lower(n.content) like lower(concat('%', :kw, '%')))")
    Page<Notification> search(@Param("userId") Long userId,
                              @Param("unreadOnly") boolean unreadOnly,
                              @Param("type") String type,
                              @Param("kw") String kw,
                              Pageable pageable);

    /**
     * 公告批次汇总：批次号、代表行 id、送达人数、已读人数。
     * 用 List + Pageable 而非 Page，分组查询派生出的 count 语句会按组返回多行。
     */
    @Query("select n.batchNo, max(n.id), count(n.id), "
            + "sum(case when n.read = true then 1 else 0 end) "
            + "from Notification n where n.type = 'ANNOUNCEMENT' and n.batchNo is not null "
            + "group by n.batchNo order by max(n.id) desc")
    List<Object[]> announcementBatches(Pageable pageable);

    List<Notification> findByIdIn(Collection<Long> ids);

    long countByUserIdAndReadFalse(Long userId);

    @Transactional
    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.id = :id")
    int markRead(@Param("userId") Long userId, @Param("id") Long id);

    @Transactional
    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId);

    @Transactional
    @Modifying
    @Query("delete from Notification n where n.userId = :userId and n.id in :ids")
    int deleteByUserAndIdIn(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Transactional
    @Modifying
    @Query("delete from Notification n where n.userId = :userId and n.read = true")
    int deleteRead(@Param("userId") Long userId);

    /** 已读通知超期清理（定时任务），未读的保留以免用户漏看。 */
    @Transactional
    @Modifying
    @Query("delete from Notification n where n.read = true and n.createdAt < :before")
    int deleteReadBefore(@Param("before") LocalDateTime before);
}
