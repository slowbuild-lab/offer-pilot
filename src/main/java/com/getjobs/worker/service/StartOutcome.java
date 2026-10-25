package com.getjobs.worker.service;

/**
 * 启动/停止请求的结果，直接映射成接口响应，避免"看起来启动了其实没启动"。
 *
 * @param accepted 是否真的启动了任务
 * @param status   机器可读状态：started / running / stopping / not_logged_in / parallel_limit / platform_busy ...
 * @param message  给用户看的中文说明
 */
public record StartOutcome(boolean accepted, String status, String message) {

    public static StartOutcome started(String message) {
        return new StartOutcome(true, "started", message);
    }

    public static StartOutcome rejected(String status, String message) {
        return new StartOutcome(false, status, message);
    }
}
