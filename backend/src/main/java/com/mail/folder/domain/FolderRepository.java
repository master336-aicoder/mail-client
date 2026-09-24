package com.mail.folder.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    List<Folder> findByUserIdOrderBySeqAsc(Long userId);

    List<Folder> findByUserIdAndAccountIdOrderBySeqAsc(Long userId, Long accountId);

    List<Folder> findByUserIdAndAccountIdIsNullOrderBySeqAsc(Long userId);

    List<Folder> findByUserIdAndAccountIdNotNullOrderBySeqAsc(Long userId);

    long countByUserId(Long userId);
}