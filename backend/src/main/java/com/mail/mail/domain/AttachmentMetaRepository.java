package com.mail.mail.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentMetaRepository extends JpaRepository<AttachmentMeta, Long> {

    List<AttachmentMeta> findByMessageId(Long messageId);
}