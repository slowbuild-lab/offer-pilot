package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlatformTaskGate;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务。
 *
 * <p>任务状态与执行线程按平台隔离：投递流程整体跑在 boss 自己的浏览器会话线程上，
 * 不再和其它平台共享一条 playwright 线程。启动前的互斥由
 * {@link PlatformTaskCoordinator} + {@link PlatformTaskGate} 统一判定。</p>
 *
 * <p>状态机：IDLE → STARTING → RUNNING → STOPPING → STOPPED/COMPLETED/FAILED。
 * 停止请求只会把状态推进到 STOPPING，真正回到空闲必须等投递流程退出（finally 里改），
 * 这样前端不会在任务还没停时就变回"开始"。</p>
 */
@Slf4j
@Service
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final PlatformTaskGate gate;
    private final com.getjobs.application.service.BossDeliveryLedger ledger;
    private volatile String currentTaskId;
    private final com.getjobs.application.service.JobProfileService profiles;
    private final com.getjobs.application.service.JobProfileRuntime profileRuntime;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;
    private volatile String taskState = "IDLE";
    private volatile String lastMessage = "";
    private volatile String lastMessageType = "info";
    private volatile int deliveredCount = 0;
    private volatile long runningSince = 0L;

    public BossJobService(PlaywrightManager playwrightManager,
                          ConfigService configService,
                          ObjectProvider<Boss> bossProvider,
                          PlatformTaskGate gate,
                          com.getjobs.application.service.BossDeliveryLedger ledger,
                          com.getjobs.application.service.JobProfileService profiles,
                          com.getjobs.application.service.JobProfileRuntime profileRuntime) {
        this.playwrightManager = playwrightManager;
        this.configService = configService;
        this.bossProvider = bossProvider;
        this.gate = gate;
        this.ledger = ledger;
        this.profiles = profiles;
        this.profileRuntime = profileRuntime;
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        Consumer<JobProgressMessage> progress = message -> {
            lastMessage = message.getMessage();
            lastMessageType = message.getType();
            progressCallback.accept(message);
        };
        String finalState = "FAILED";
        synchronized (this) {
            if (isRunning) {
                progress.accept(JobProgressMessage.warning(PLATFORM, "Boss任务已在运行中"));
                return;
            }
            isRunning = true;
            shouldStop = false;
            taskState = "STARTING";
            deliveredCount = 0;
            runningSince = System.currentTimeMillis();
            currentTaskId = null;
            lastMessage = "任务正在启动";
            lastMessageType = "info";
        }
        try {
            currentTaskId = ledger.createTask(20);
            progress.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            // 按需启动 boss 自己的浏览器会话（独立进程、独立资料目录）
            playwrightManager.ensureReady(PLATFORM);
            if (shouldStop) {
                finalState = "STOPPED";
                return;
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                finalState = "FAILED";
                progress.accept(JobProgressMessage.error(PLATFORM, "请先登录Boss直聘（可点击“打开浏览器登录”扫码）"));
                return;
            }

            playwrightManager.pauseMonitoring(PLATFORM);
            BossConfig config = configService.getBossConfig();
            var snapshot = profiles.activeFor(PLATFORM).orElse(null);
            if (snapshot != null) profileRuntime.apply(config, snapshot);
            ledger.bindConfig(currentTaskId, config);
            progress.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            Boss boss = bossProvider.getObject();
            boss.setLedger(ledger);
            boss.setTaskId(currentTaskId);
            boss.setExternalGuard(job -> profileRuntime.guard(snapshot, job));
            boss.setConfig(config);
            boss.setShouldStopCallback(this::shouldStop);
            boss.setProgressCallback((message, current, total) -> {
                if (current != null && total != null) {
                    progress.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progress.accept(JobProgressMessage.info(PLATFORM, message));
                }
            });
            boss.prepare();

            synchronized (this) {
                if (shouldStop) {
                    finalState = "STOPPED";
                    return;
                }
                taskState = "RUNNING";
            }
            progress.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 整个投递流程跑在 boss 会话线程上，Page 也在该线程内取用
            int count = playwrightManager.callOn(PLATFORM, page -> {
                boss.setPage(page);
                return boss.execute();
            });
            deliveredCount = count;
            finalState = shouldStop ? "STOPPED" : boss.getOutcome();
            if (shouldStop) {
                progress.accept(JobProgressMessage.warning(PLATFORM, "Boss任务已停止"));
            } else if (!"COMPLETED".equals(finalState)) {
                progress.accept(JobProgressMessage.warning(PLATFORM, boss.getOutcomeReason()));
            } else {
                progress.accept(JobProgressMessage.success(PLATFORM,
                        String.format("本轮完成，页面确认文字消息%d条；其他动作与待核实请查看投递记录", count)));
            }
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            finalState = "FAILED";
            progress.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            if (currentTaskId != null) {
                try { ledger.finishTask(currentTaskId, finalState, lastMessage); }
                catch (Exception e) { finalState = "FAILED"; log.error("BOSS 账本收尾失败", e); }
            }
            try {
                playwrightManager.resumeMonitoring(PLATFORM);
            } catch (Exception ignored) {
            }
            synchronized (this) {
                if (shouldStop && "COMPLETED".equals(finalState)) {
                    finalState = "STOPPED";
                }
                taskState = finalState;
                isRunning = false;
                shouldStop = false;
                runningSince = 0L;
            }
            log.info("Boss任务结束，状态 {}", taskState);
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;
        taskState = "STOPPING";
        lastMessage = "正在停止，等待当前步骤退出";
        lastMessageType = "warning";
        // 不再像以前那样 15 秒后强制把 isRunning 改成 false：
        // 任务可能还卡在浏览器调用里，提前放行会让同一平台出现两个任务。
        // 真卡死时通过"强制结束"关闭该平台浏览器，由异常路径退出并释放槽位。
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("taskState", taskState);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("lastMessage", lastMessage);
        status.put("lastMessageType", lastMessageType);
        status.put("deliveredCount", deliveredCount);
        status.putAll(ledger.summary());
        status.put("runningSeconds", runningForMillis() / 1000);
        status.put("taskHeld", gate.holding(PLATFORM));
        status.putAll(gate.parallelView(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /** 供Boss.java调用 */
    public boolean shouldStop() {
        return shouldStop;
    }

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }
}
