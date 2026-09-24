package com.mail.sync.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByAccountIdOrderByIdDesc(Long accountId);

    Optional<SyncJob> findTopByAccountIdAndFolderIdAndTypeOrderByIdDesc(Long accountId, Long folderId, String type);
}