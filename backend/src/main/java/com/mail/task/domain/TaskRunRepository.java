package com.mail.task.domain;

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

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /** 批量取任务的执行记录（按 id 倒序），调用方据此取每条任务的最近一次执行。 */
    List<TaskRun> findByTaskIdInOrderByIdDesc(Collection<Long> taskIds);

    /** 单个任务的执行记录分页，新的在前。 */
    Page<TaskRun> findByTaskId(Long taskId, Pageable pageable);

    boolean existsByTaskIdAndStatusIn(Long taskId, Collection<String> statuses);

    List<TaskRun> findByStatusIn(Collection<String> statuses);

    /**
     * 只回写进度与说明，且仅在记录仍处于 RUNNING 时生效：整体 save 实体会把
     * 中止线程刚写入的 CANCELLED 覆盖回 RUNNING，留下永不结束的僵尸执行。
     */
    @Transactional
    @Modifying
    @Query("update TaskRun r set r.progress = :progress, r.message = :message "
            + "where r.id = :id and r.status = 'RUNNING'")
    int markProgress(@Param("id") Long id, @Param("progress") int progress,
                     @Param("message") String message);

    /** 从库里读当前状态（标量投影不受持久化上下文缓存影响），用于判断执行是否已被中止。 */
    @Query("select r.status from TaskRun r where r.id = :id")
    String findStatus(@Param("id") Long id);

    /** 按 id 升序取指定执行记录，导出时保持时间顺序。 */
    List<TaskRun> findByIdInOrderByIdAsc(Collection<Long> ids);

    /** 某任务全部已结束的执行记录 id，供「全部删除/全部导出」。 */
    @Query("select r.id from TaskRun r where r.taskId = :taskId "
            + "and r.status in ('COMPLETED', 'FAILED', 'CANCELLED') order by r.id asc")
    List<Long> findFinishedIdsByTask(@Param("taskId") Long taskId);

    /** 任务级策略：删除该任务全部已结束记录。 */
    @Transactional
    @Modifying
    @Query("delete from TaskRun r where r.taskId = :taskId "
            + "and r.status in ('COMPLETED', 'FAILED', 'CANCELLED')")
    int deleteFinishedByTask(@Param("taskId") Long taskId);

    /** 任务级策略：删除该任务指定状态的记录。 */
    @Transactional
    @Modifying
    @Query("delete from TaskRun r where r.taskId = :taskId and r.status in :statuses")
    int deleteByTaskAndStatusIn(@Param("taskId") Long taskId, @Param("statuses") Collection<String> statuses);

    /** 任务级策略：删除该任务超出保存天数的已结束记录。 */
    @Transactional
    @Modifying
    @Query("delete from TaskRun r where r.taskId = :taskId "
            + "and r.status in ('COMPLETED', 'FAILED', 'CANCELLED') and r.createdAt < :before")
    int deleteFinishedByTaskBefore(@Param("taskId") Long taskId, @Param("before") LocalDateTime before);
}
