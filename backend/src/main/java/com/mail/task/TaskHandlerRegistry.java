package com.mail.task;

import com.mail.common.BizException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务处理器注册表。
 */
@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> byType = new HashMap<>();

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        for (TaskHandler h : handlers) {
            byType.put(h.type(), h);
        }
    }

    public TaskHandler get(String type) {
        TaskHandler h = byType.get(type);
        if (h == null) throw new BizException("未知任务类型: " + type);
        return h;
    }
}