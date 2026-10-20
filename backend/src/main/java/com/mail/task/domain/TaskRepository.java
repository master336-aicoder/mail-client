package com.mail.task.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** 分页列表；排序由调用方通过 Pageable 指定（创建时间倒序）。 */
    Page<Task> findByUserId(Long userId, Pageable pageable);

    Page<Task> findByUserIdAndType(Long userId, String type, Pageable pageable);

    /** 一个账户的同类型任务只应有一条（账户同步），用于建号时幂等补建。 */
    Optional<Task> findByUserIdAndTypeAndAccountId(Long userId, String type, Long accountId);

    List<Task> findByAccountId(Long accountId);

    /** 策略清理需要逐任务判断是否覆盖了全局策略。 */
    List<Task> findByUserId(Long userId);

    /** 到点的周期任务：启用且 nextRunAt 为空或已过期。 */
    @Query("select t from Task t where t.periodic = true and t.enabled = true "
            + "and (t.nextRunAt is null or t.nextRunAt <= :now)")
    List<Task> findDuePeriodic(@Param("now") LocalDateTime now);
}
