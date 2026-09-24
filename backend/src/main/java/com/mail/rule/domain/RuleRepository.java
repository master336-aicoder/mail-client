package com.mail.rule.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    List<Rule> findByUserIdAndEnabledTrueOrderByPriorityAsc(Long userId);

    List<Rule> findByUserIdOrderByPriorityAsc(Long userId);

    /** 某账户启用的规则，按优先级排序 */
    List<Rule> findByUserIdAndAccountIdAndEnabledTrueOrderByPriorityAsc(Long userId, Long accountId);
}