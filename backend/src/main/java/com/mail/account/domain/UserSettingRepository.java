package com.mail.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserSettingRepository extends JpaRepository<UserSetting, Long>, JpaSpecificationExecutor<UserSetting> {
}