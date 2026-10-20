package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.ZhilianDeliveryLedger;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns a single task, including its startup and shutdown. Browser work stays on the Playwright thread. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";
    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;
    @Autowired private ZhilianDeliveryLedger ledger;
    private volatile boolean isRunning;
    private volatile boolean shouldStop;
    private volatile String taskState = "IDLE";
    private volatile String lastMessage = "";
    private volatile String lastMessageType = "info";
    private volatile int deliveredCount;
    private volatile String currentTaskId;
    private volatile int dayLimit = 50;

    @PostConstruct
    public void restoreLastTask() {
        var last = ledger.latestTask();
        if (last.isEmpty()) return;
        currentTaskId = (String) last.get("id");
        taskState = (String) last.get("state");
        lastMessage = (String) last.get("reason");
        dayLimit = ledger.loadConfig(currentTaskId).getMaxAttemptsPerDay();
        if ("PAUSED".equals(taskState)) lastMessageType = "warning";
    }

    public boolean startDelivery(Consumer<JobProgressMessage> listener) { return dispatch(listener, false); }
    public boolean resumeDelivery(Consumer<JobProgressMessage> listener) { return dispatch(listener, true); }
    private boolean dispatch(Consumer<JobProgressMessage> listener, boolean resume) {
        if (!reserveTask(resume)) return false;
        try {
            CompletableFuture.runAsync(() -> executeReserved(listener, resume));
            return true;
        } catch (RuntimeException e) {
            lastMessage = "任务启动失败，请重试";
            lastMessageType = "error";
            finishTask(resume ? "PAUSED" : "FAILED");
            throw e;
        }
    }

    private synchronized boolean reserveTask(boolean resume) {
        if (isRunning || (resume != "PAUSED".equals(taskState))) return false;
        if (ledger != null && ledger.platformLimitedToday()) {
            taskState = "PLATFORM_LIMIT";
            lastMessage = "平台已提示今日投递上限，今天不再启动投递";
            lastMessageType = "warning";
            return false;
        }
        isRunning = true;
        shouldStop = false;
        taskState = "STARTING";
        lastMessage = resume ? "正在检查暂停任务" : "任务正在启动";
        lastMessageType = "info";
        return true;
    }

    private synchronized void finishTask(String state) {
        taskState = state;
        isRunning = false;
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> listener) {
        if (!reserveTask(false)) {
            listener.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行或暂停中，请查看当前状态"));
            return;
        }
        executeReserved(listener, false);
    }

    private void executeReserved(Consumer<JobProgressMessage> listener, boolean resume) {
        Consumer<JobProgressMessage> progress = message -> {
            lastMessage = message.getMessage();
            lastMessageType = message.getType();
            listener.accept(message);
        };
        String finalState = resume ? "PAUSED" : "FAILED";
        String executingTaskId = resume ? currentTaskId : null;
        try {
            deliveredCount = 0;
            progress.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            if (shouldStop) return;
            playwrightManager.ensureReady();
            if (shouldStop) return;
            var page = playwrightManager.getZhilianPage();
            if (page == null) { progress.accept(JobProgressMessage.error(PLATFORM, "智联招聘页面未初始化")); return; }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progress.accept(JobProgressMessage.error(PLATFORM, "请先登录智联招聘")); return;
            }
            playwrightManager.pauseZhilianMonitoring();
            ZhilianConfig config = resume && ledger != null ? ledger.loadConfig(currentTaskId) : configService.getZhilianConfig();
            if (config == null || config.getKeywords() == null || config.getKeywords().stream()
                    .noneMatch(word -> word != null && !word.isBlank())) {
                progress.accept(JobProgressMessage.error(PLATFORM, "请先填写搜索关键词并保存配置")); return;
            }
            synchronized (this) {
                if (shouldStop) return;
                taskState = "RUNNING";
            }
            if (ledger != null) {
                if (resume) ledger.resumeTask(executingTaskId);
                else executingTaskId = ledger.createTask(config);
                currentTaskId = executingTaskId;
                dayLimit = config.getMaxAttemptsPerDay();
            }
            progress.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));
            AtomicReference<String> workerError = new AtomicReference<>();
            ZhiLian worker = zhilianProvider.getObject();
            worker.setPage(page);
            worker.setConfig(config);
            worker.setLedger(ledger);
            worker.setTaskId(executingTaskId);
            worker.setShouldStopCallback(this::shouldStop);
            worker.setProgressCallback((message, current, total) -> {
                // Older workers can still report diagnostics through this callback.
                boolean error = message != null && (message.contains("超时") || message.contains("失败")
                        || message.contains("异常") || message.contains("未确认"));
                if (error) workerError.set(message);
                progress.accept(error ? JobProgressMessage.error(PLATFORM, message)
                        : current != null && total != null ? JobProgressMessage.progress(PLATFORM, message, current, total)
                        : JobProgressMessage.info(PLATFORM, message));
            });
            worker.prepare();
            deliveredCount = playwrightManager.callOnPlaywright(worker::execute);
            if (shouldStop) return;
            if (worker.getOutcome() != null && worker.getOutcome() != ZhiLian.Outcome.COMPLETED) {
                finalState = worker.getOutcome().name();
                progress.accept("FAILED".equals(finalState) ? JobProgressMessage.error(PLATFORM, worker.getOutcomeReason())
                        : JobProgressMessage.warning(PLATFORM, worker.getOutcomeReason()));
            } else if (worker.getOutcome() == null && workerError.get() != null) {
                progress.accept(JobProgressMessage.error(PLATFORM, workerError.get()));
            } else {
                finalState = "COMPLETED";
                progress.accept(JobProgressMessage.success(PLATFORM, String.format("投递任务完成，共确认投递%d个职位，请核实待确认记录", deliveredCount)));
            }
        } catch (Exception e) {
            log.error("智联招聘任务执行失败", e);
            finalState = "FAILED";
            progress.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            try { playwrightManager.resumeZhilianMonitoring(); } catch (Exception ignored) {}
            synchronized (this) {
                if (shouldStop) {
                    finalState = "STOPPED";
                    progress.accept(JobProgressMessage.warning(PLATFORM, "投递任务已停止，已发出的申请保留结果"));
                }
                try {
                    if (ledger != null && executingTaskId != null) ledger.finishTask(executingTaskId, finalState, lastMessage);
                } catch (Exception e) {
                    finalState = "FAILED";
                    progress.accept(JobProgressMessage.error(PLATFORM, "投递记录保存失败，任务已停止，请查看日志"));
                    log.error("保存智联任务结果失败", e);
                } finally {
                    finishTask(finalState);
                }
            }
        }
    }

    @Override
    public synchronized void stopDelivery() {
        if (isRunning) { shouldStop = true; taskState = "STOPPING"; }
        else if (isPaused()) {
            if (ledger != null) ledger.finishTask(currentTaskId, "STOPPED", "用户结束暂停任务");
            taskState = "STOPPED";
            lastMessage = "已结束暂停任务，可修改配置后开始新任务";
            lastMessageType = "info";
        }
    }
    public boolean isPaused() { return "PAUSED".equals(taskState); }
    public boolean shouldStop() { return shouldStop; }
    @Override public boolean isRunning() { return isRunning; }
    @Override public String getPlatformName() { return PLATFORM; }
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("taskState", taskState);
        status.put("taskId", currentTaskId);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("lastMessage", lastMessage);
        status.put("lastMessageType", lastMessageType);
        status.put("deliveredCount", deliveredCount);
        if (ledger != null) status.putAll(ledger.summary(currentTaskId, dayLimit));
        return status;
    }
}
