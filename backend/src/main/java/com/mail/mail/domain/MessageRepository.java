package com.mail.mail.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {

    Page<Message> findByFolderIdAndDeletedFalseAndDraftFalse(Long folderId, Pageable pageable);

    Page<Message> findByFolderIdAndDeletedFalseAndDraftTrue(Long folderId, Pageable pageable);

    List<Message> findByFolderIdAndDeletedFalseAndDraftFalse(Long folderId);

    List<Message> findByFolderIdAndDeletedFalse(Long folderId);

    Optional<Message> findByAccountIdAndFolderIdAndRemoteUid(Long accountId, Long folderId, String remoteUid);

    /**
     * 该账户收件箱已入库的最高 IMAP 消息序号（remote_uid 存的是消息序号）。
     * 用于增量拉取：仅取回序号大于此值的新邮件。CAST 为无符号整数，非数值返回 0，DB 层安全。
     */
    @Query(value = "select coalesce(max(cast(remote_uid as unsigned)), 0) from messages "
            + "where account_id = :accountId and folder_id = :folderId and deleted = 0", nativeQuery = true)
    long maxRemoteSeq(@Param("accountId") Long accountId, @Param("folderId") Long folderId);

    List<Message> findByUserIdAndDeletedFalse(Long userId);

    long countByFolderIdAndDeletedFalse(Long folderId);

    long countByFolderIdInAndDeletedFalse(List<Long> folderIds);

    Page<Message> findByFolderIdInAndDeletedFalseAndDraftFalse(List<Long> folderIds, Pageable pageable);

    Page<Message> findByFolderIdInAndDeletedFalseAndDraftTrue(List<Long> folderIds, Pageable pageable);

    @Query("select m from Message m where m.deleted = false and m.draft = false and m.folderId = :folderId "
            + "and (lower(m.subject) like lower(concat('%', :kw, '%')) "
            + "or lower(m.sender) like lower(concat('%', :kw, '%')) "
            + "or lower(m.senderEmail) like lower(concat('%', :kw, '%')))")
    Page<Message> searchByFolder(@Param("folderId") Long folderId, @Param("kw") String kw, Pageable pageable);

    @Query("select m from Message m where m.deleted = false and m.draft = false and m.folderId in :folderIds "
            + "and (lower(m.subject) like lower(concat('%', :kw, '%')) "
            + "or lower(m.sender) like lower(concat('%', :kw, '%')) "
            + "or lower(m.senderEmail) like lower(concat('%', :kw, '%')))")
    Page<Message> searchByFolders(@Param("folderIds") List<Long> folderIds, @Param("kw") String kw, Pageable pageable);

    @Query("select m from Message m where m.deleted = false and m.folderId = :folderId "
            + "and (lower(m.subject) like lower(concat('%', :kw, '%')) "
            + "or lower(m.sender) like lower(concat('%', :kw, '%')) "
            + "or lower(m.senderEmail) like lower(concat('%', :kw, '%')))")
    Page<Message> searchByFolderAnyDraft(@Param("folderId") Long folderId, @Param("kw") String kw, Pageable pageable);

    @Query("select m from Message m where m.deleted = false and m.folderId in :folderIds "
            + "and (lower(m.subject) like lower(concat('%', :kw, '%')) "
            + "or lower(m.sender) like lower(concat('%', :kw, '%')) "
            + "or lower(m.senderEmail) like lower(concat('%', :kw, '%')))")
    Page<Message> searchByFoldersAnyDraft(@Param("folderIds") List<Long> folderIds, @Param("kw") String kw, Pageable pageable);

    /** 清空文件夹：整批软删除，避免逐封加载正文实体。 */
    @Transactional
    @Modifying
    @Query("update Message m set m.deleted = true where m.userId = :userId and m.deleted = false "
            + "and m.folderId in :folderIds")
    int softDeleteByFolders(@Param("userId") Long userId, @Param("folderIds") List<Long> folderIds);

    /** 重置账户：硬删除该账户全部本地邮件（收件箱/已发送/草稿等），随后由同步重新拉取。 */
    @Transactional
    @Modifying
    @Query("delete from Message m where m.accountId = :accountId")
    int deleteAllByAccountId(@Param("accountId") Long accountId);

    /**
     * 整个文件夹标记已读。flags 是位图，JPQL 没有按位或，只能用原生 SQL；
     * 已读位（1）已置位的行不更新，返回的是本次真正改动数。
     */
    @Transactional
    @Modifying
    @Query(value = "update messages set flags = flags | 1, updated_at = now() "
            + "where user_id = :userId and deleted = 0 and folder_id in :folderIds and (flags & 1) = 0",
            nativeQuery = true)
    int markFoldersRead(@Param("userId") Long userId, @Param("folderIds") List<Long> folderIds);
}