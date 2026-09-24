package com.mail.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {

    List<MailAccount> findByUserId(Long userId);

    List<MailAccount> findByUserIdAndActive(Long userId, boolean active);

    List<MailAccount> findByActiveTrue();
}