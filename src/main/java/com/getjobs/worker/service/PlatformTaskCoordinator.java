package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlatformTaskGate;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 平台任务启动/停止的统一入口。
 *
 * <p>所有平台都从这里启动任务，于是"同一平台只能有一个任务"和"最多同时运行 N 个平台"
 * 只有在 {@link PlatformTaskGate} 里判定一次，控制器不再各自做竞态检查。
 * 任务真正退出（executeDelivery 返回）时才释放槽位，因此停止请求发出后、任务尚未退出前，
 * 再次启动会被明确拒绝，而不是出现两个任务。</p>
 */
@Slf4j
@Service
public class PlatformTaskCoordinator {

    private final PlatformTaskGate gate;
    private final PlaywrightManager playwrightManager;
    private final Map<String, JobPlatformService> services = new LinkedHashMap<>();
    private final Map<String, PlatformTaskGate.Ticket> current = new ConcurrentHashMap<>();

    public PlatformTaskCoordinator(PlatformTaskGate gate,
                                   PlaywrightManager playwrightManager,
                                   List<JobPlatformService> platformServices) {
        this.gate = gate;
        this.playwrightManager = playwrightManager;
        for (JobPlatformService service : platformServices) {
            services.put(service.getPlatformName(), service);
        }
        log.info("平台任务协调器已注册: {}", services.keySet());
    }

    public StartOutcome start(String platform, Consumer<JobProgressMessage> listener) {
        JobPlatformService service = service(platform);
        PlatformTaskGate.Ticket ticket;
        try {
            ticket = gate.acquire(platform);
        } catch (PlatformTaskGate.Rejected rejected) {
            log.warn("[{}] 启动被拒绝: {} {}", platform, rejected.code(), rejected.getMessage());
            return StartOutcome.rejected(rejected.code(), rejected.getMessage());
        }
        current.put(platform, ticket);
        try {
            CompletableFuture.runAsync(() -> runAndRelease(platform, ticket, service, listener));
            return StartOutcome.started(platformMessage(platform) + "任务已启动");
        } catch (RuntimeException e) {
            release(platform, ticket);
            throw e;
        }
    }

    /** 继续智联的暂停任务：暂停任务不占槽位，继续时重新占用 */
    public StartOutcome resume(String platform, Consumer<JobProgressMessage> listener) {
        JobPlatformService service = service(platform);
        if (!(service instanceof ZhilianJobService zhilian)) {
            return StartOutcome.rejected("not_resumable", "该平台没有可继续的暂停任务");
        }
        PlatformTaskGate.Ticket ticket;
        try {
            ticket = gate.acquire(platform);
        } catch (PlatformTaskGate.Rejected rejected) {
            return StartOutcome.rejected(rejected.code(), rejected.getMessage());
        }
        current.put(platform, ticket);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    zhilian.executeResume(listener);
                } catch (Exception e) {
                    log.error("[{}] 继续任务失败", platform, e);
                    listener.accept(JobProgressMessage.error(platform, "继续任务失败: " + e.getMessage()));
                } finally {
                    release(platform, ticket);
                }
            });
            return StartOutcome.started("正在重新检查登录与额度，暂停任务将使用原配置继续");
        } catch (RuntimeException e) {
            release(platform, ticket);
            throw e;
        }
    }

    private void runAndRelease(String platform,
                               PlatformTaskGate.Ticket ticket,
                               JobPlatformService service,
                               Consumer<JobProgressMessage> listener) {
        try {
            // 真正开始执行：状态从 STARTING 进入 RUNNING（进程内可能还停在"检查浏览器"阶段，
            // 具体进度由各平台服务自己的 taskState 反映）
            gate.markRunning(ticket);
            service.executeDelivery(listener);
        } catch (Throwable e) {
            log.error("[{}] 任务执行异常", platform, e);
            try {
                listener.accept(JobProgressMessage.error(platform, "任务异常结束: " + e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            release(platform, ticket);
        }
    }

    /** 停止指定平台；任务退出前槽位保持占用，重复启动会被拒绝 */
    public StartOutcome stop(String platform) {
        JobPlatformService service = service(platform);
        PlatformTaskGate.Ticket ticket = current.get(platform);
        if (ticket == null) {
            return StartOutcome.rejected("not_running", "没有正在运行的" + platformMessage(platform) + "任务");
        }
        service.stopDelivery();
        gate.markStopping(ticket);
        return new StartOutcome(true, "stopping",
                platformMessage(platform) + "停止请求已发送，等待任务在当前步骤退出");
    }

    /**
     * 强制结束：先请求停止，再只关闭该平台的浏览器。
     * 任务卡在某个 Playwright 调用里时，关闭它自己的浏览器会让调用抛错并走到 finally 释放槽位；
     * 其它平台的浏览器不受影响。
     */
    public StartOutcome forceStop(String platform) {
        JobPlatformService service = service(platform);
        PlatformTaskGate.Ticket ticket = current.get(platform);
        if (ticket == null) {
            return StartOutcome.rejected("not_running", "没有正在运行的" + platformMessage(platform) + "任务");
        }
        service.stopDelivery();
        gate.markStopping(ticket);
        playwrightManager.closeSession(platform);
        return new StartOutcome(true, "stopping",
                platformMessage(platform) + "已关闭该平台浏览器，等待任务退出");
    }

    private void release(String platform, PlatformTaskGate.Ticket ticket) {
        if (gate.release(ticket)) {
            current.remove(platform, ticket);
        }
    }

    public boolean isHolding(String platform) {
        return gate.holding(platform);
    }

    public PlatformTaskGate gate() {
        return gate;
    }

    public Map<String, String> phaseByPlatform() {
        Map<String, String> phases = new LinkedHashMap<>();
        for (String platform : services.keySet()) {
            phases.put(platform, gate.phase(platform).name());
        }
        return phases;
    }

    /** 各平台自己的任务状态（不包含浏览器细节） */
    public Map<String, Map<String, Object>> statuses() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        services.forEach((platform, service) -> {
            try {
                result.put(platform, service.getStatus());
            } catch (Exception e) {
                result.put(platform, Map.of("platform", platform, "error", String.valueOf(e.getMessage())));
            }
        });
        return result;
    }

    private JobPlatformService service(String platform) {
        JobPlatformService service = services.get(platform);
        if (service == null) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }
        return service;
    }

    private String platformMessage(String platform) {
        return switch (platform) {
            case "boss" -> "Boss直聘";
            case "zhilian" -> "智联招聘";
            case "liepin" -> "猎聘";
            case "51job" -> "前程无忧";
            default -> platform;
        };
    }
}
