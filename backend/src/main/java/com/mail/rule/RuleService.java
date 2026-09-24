package com.mail.rule;

import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.rule.domain.Rule;
import com.mail.rule.domain.RuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 规则管理。
 */
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository ruleRepository;

    @Transactional(readOnly = true)
    public List<Rule> list() {
        Long userId = CurrentUser.requireId();
        return ruleRepository.findByUserIdOrderByPriorityAsc(userId);
    }

    @Transactional
    public Rule create(Rule rule) {
        Long userId = CurrentUser.requireId();
        rule.setId(null);
        rule.setUserId(userId);
        if (rule.getPriority() == null) rule.setPriority(0);
        return ruleRepository.save(rule);
    }

    @Transactional
    public Rule update(Long id, Rule update) {
        Long userId = CurrentUser.requireId();
        Rule r = ruleRepository.findById(id).orElseThrow(() -> new BizException("规则不存在"));
        if (!r.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (update.getName() != null) r.setName(update.getName());
        if (update.getConditions() != null) r.setConditions(update.getConditions());
        if (update.getActions() != null) r.setActions(update.getActions());
        if (update.getPriority() != null) r.setPriority(update.getPriority());
        if (update.getAccountId() != null) r.setAccountId(update.getAccountId());
        if (update.isEnabled() != r.isEnabled()) r.setEnabled(update.isEnabled());
        return ruleRepository.save(r);
    }

    @Transactional
    public void toggle(Long id, boolean enabled) {
        Long userId = CurrentUser.requireId();
        Rule r = ruleRepository.findById(id).orElseThrow(() -> new BizException("规则不存在"));
        if (!r.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        r.setEnabled(enabled);
        ruleRepository.save(r);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = CurrentUser.requireId();
        Rule r = ruleRepository.findById(id).orElseThrow(() -> new BizException("规则不存在"));
        if (!r.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        ruleRepository.delete(r);
    }

    /**
     * 按给定的 id 顺序重排序优先级。
     */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        Long userId = CurrentUser.requireId();
        int p = 0;
        for (Long id : orderedIds) {
            Rule r = ruleRepository.findById(id).orElse(null);
            if (r != null && r.getUserId().equals(userId)) {
                r.setPriority(p++);
                ruleRepository.save(r);
            }
        }
    }
}