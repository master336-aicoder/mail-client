package com.mail.task.dto;

import lombok.Data;

/**
 * 任务级执行记录保存策略。两个字段都可为空，空值表示该项跟随用户全局策略。
 */
@Data
public class TaskRunPolicyRequest {

    /** ALL / SUCCESS / FAILED / NONE，null=跟随全局 */
    private String runRetentionScope;

    /** 保存天数 1~3650，null=跟随全局 */
    private Integer runRetentionDays;
}
