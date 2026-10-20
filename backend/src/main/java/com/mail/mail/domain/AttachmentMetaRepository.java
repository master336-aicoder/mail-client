package com.mail.mail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AttachmentMetaRepository extends JpaRepository<AttachmentMeta, Long> {

    List<AttachmentMeta> findByMessageId(Long messageId);

    List<AttachmentMeta> findByAccountId(Long accountId);

    boolean existsByMessageIdAndDisposition(Long messageId, String disposition);

    /** 重置账户：硬删除该账户全部附件元数据（存储文件由调用方先清理）。 */
    @Transactional
    @Modifying
    @Query("delete from AttachmentMeta a where a.accountId = :accountId")
    int deleteAllByAccountId(@Param("accountId") Long accountId);
}