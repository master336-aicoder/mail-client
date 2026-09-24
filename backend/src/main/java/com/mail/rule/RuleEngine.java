package com.mail.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mail.rule.domain.Rule;
import com.mail.mail.domain.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则引擎：按 priority 顺序评估条件并执行动作。
 * conditions 结构：[{"field":"from","op":"contains","value":"boss"}]
 * actions 结构：[{"type":"move","folderId":2},{"type":"star"},{"type":"read"},{"type":"delete"}]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {

    private final ObjectMapper objectMapper;

    /**
     * 对一封新邮件执行用户全部启用规则。返回 true 表示至少命中一条。
     */
    public boolean apply(Long userId, Message message, List<Rule> rules) {
        boolean hit = false;
        for (Rule rule : rules) {
            try {
                if (!rule.isEnabled()) continue;
                if (evaluate(rule, message)) {
                    execute(userId, rule, message);
                    hit = true;
                }
            } catch (Exception e) {
                log.warn("规则执行异常 rule={} msg={}: {}", rule.getId(), message.getId(), e.getMessage());
            }
        }
        return hit;
    }

    boolean evaluate(Rule rule, Message message) {
        try {
            JsonNode node = objectMapper.readTree(rule.getConditions());
            if (node == null || !node.isArray()) return true;
            boolean all = true;
            for (JsonNode cond : node) {
                String field = cond.path("field").asText();
                String op = cond.path("op").asText("contains");
                String value = cond.path("value").asText("");
                String actual = fieldOf(message, field);
                boolean ok = match(actual, op, value);
                if (!ok) { all = false; break; }
            }
            return all;
        } catch (Exception e) {
            return false;
        }
    }

    private String fieldOf(Message m, String field) {
        return switch (field) {
            case "from" -> m.getSenderEmail();
            case "sender" -> m.getSender();
            case "subject" -> m.getSubject();
            case "body" -> m.getBodyText();
            case "hasAttach" -> m.isHasAttach() ? "true" : "false";
            default -> "";
        };
    }

    private boolean match(String actual, String op, String value) {
        if ("hasAttach".equalsIgnoreCase(value) || op.equals("is")) {
            return Boolean.parseBoolean(actual) == Boolean.parseBoolean(value);
        }
        String a = actual == null ? "" : actual.toLowerCase();
        String v = value.toLowerCase();
        return switch (op) {
            case "equals" -> a.equals(v);
            case "notContains" -> !a.contains(v);
            case "startsWith" -> a.startsWith(v);
            default -> a.contains(v);
        };
    }

    private void execute(Long userId, Rule rule, Message message) {
        // v1：动作仅记录日志并在内存标记；持久化移动等由上层（SyncWorker）按 action 类型处理。
        // 这里实现 star / read 两个本地动作。
        try {
            JsonNode node = objectMapper.readTree(rule.getActions());
            if (node == null || !node.isArray()) return;
            for (JsonNode act : node) {
                String type = act.path("type").asText();
                switch (type) {
                    case "star" -> message.setFlags(com.mail.common.MailFlags.set(message.getFlags(),
                            com.mail.common.MailFlags.STARRED, true));
                    case "read" -> message.setFlags(com.mail.common.MailFlags.set(message.getFlags(),
                            com.mail.common.MailFlags.READ, true));
                    case "move" -> {
                        // 移动到选定账户下的目标文件夹
                        long folderId = act.path("folderId").asLong();
                        if (folderId > 0) {
                            message.setFolderId(folderId);
                            log.info("规则 {} 将邮件移动到文件夹 {}", rule.getName(), folderId);
                        }
                    }
                    case "delete" -> {
                        message.setDeleted(true);
                        log.info("规则 {} 将邮件标记删除", rule.getName());
                    }
                    case "forward" ->
                            log.info("规则 {} 触发动作 {}（由同步流程执行）", rule.getName(), type);
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.warn("规则动作解析失败: {}", e.getMessage());
        }
    }
}