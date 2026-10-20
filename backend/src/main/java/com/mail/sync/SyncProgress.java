package com.mail.sync;

/**
 * 收信过程进度回调。后台任务把它适配成任务进度上报；定时轮询可以不传。
 */
@FunctionalInterface
public interface SyncProgress {

    /**
     * @param percent 0-100
     * @param message 可直接展示的进度说明
     */
    void step(int percent, String message);
}
