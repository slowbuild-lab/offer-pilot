package com.getjobs.worker.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台任务占用与并行上限。
 *
 * <p>这里只做一件事：用一把很短的锁，原子地决定"谁可以开始"。锁内没有任何浏览器或数据库
 * 操作，所以一个平台初始化慢、投递慢都不会挡住其它平台。</p>
 *
 * <p>状态机：IDLE → STARTING → RUNNING → STOPPING → IDLE。
 * 占用期间（包括 STARTING 和 STOPPING）都算占用并行额度，必须等任务真正退出
 * （{@link #release}）才允许同一平台再次启动，避免"点停止后立刻又点开始"产生两个任务。</p>
 *
 * <p>每个占用都有一个 epoch。任务结束时用自己拿到的 Ticket 释放；如果 epoch 已经
 * 不是当前的，说明这是旧任务迟到的回调，直接忽略，不会清掉新任务的状态。</p>
 */
@Slf4j
@Component
public class PlatformTaskGate {

    public enum Phase {IDLE, STARTING, RUNNING, STOPPING}

    public record Ticket(String platform, long epoch) {
    }

    /** 启动被拒绝：code 用于接口返回，message 直接给用户看 */
    public static class Rejected extends RuntimeException {
        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final class Slot {
        final long epoch;
        Phase phase;
        final long startedAt;

        Slot(long epoch, Phase phase) {
            this.epoch = epoch;
            this.phase = phase;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final int limit;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private long sequence;

    public PlatformTaskGate(@Value("${getjobs.parallel.max-platforms:2}") int limit) {
        this.limit = Math.max(1, Math.min(4, limit));
        log.info("平台并行上限: {}（可用 -Dgetjobs.parallel.max-platforms 调整，范围 1-4）", this.limit);
    }

    public int limit() {
        return limit;
    }

    /** 原子占用一个平台；失败时抛 {@link Rejected}，调用方必须把消息原样带给用户 */
    public synchronized Ticket acquire(String platform) {
        Slot slot = slots.get(platform);
        if (slot != null) {
            throw new Rejected("PLATFORM_BUSY", switch (slot.phase) {
                case STARTING -> "该平台任务正在启动中，请等待启动结果";
                case RUNNING -> "该平台已有投递任务在运行，请先停止当前任务";
                case STOPPING -> "该平台任务正在停止中，等停止完成后才能再次启动";
                case IDLE -> "该平台状态异常，请刷新后重试";
            });
        }
        if (slots.size() >= limit) {
            throw new Rejected("PARALLEL_LIMIT", String.format(
                    "已达到并行平台上限（%d 个），当前运行：%s。请先停止一个平台再启动",
                    limit, String.join("、", slots.keySet())));
        }
        Ticket ticket = new Ticket(platform, ++sequence);
        slots.put(platform, new Slot(ticket.epoch(), Phase.STARTING));
        log.info("[{}] 已占用任务槽位（epoch={}），当前并行 {} 个平台: {}",
                platform, ticket.epoch(), slots.size(), slots.keySet());
        return ticket;
    }

    /** 启动完成、真正开始投递 */
    public synchronized boolean markRunning(Ticket ticket) {
        Slot slot = current(ticket);
        if (slot == null) {
            log.warn("[{}] 旧任务回调被忽略：markRunning(epoch={}) 已不是当前任务", ticket.platform(), ticket.epoch());
            return false;
        }
        slot.phase = Phase.RUNNING;
        return true;
    }

    /** 收到停止指令，任务尚未退出 */
    public synchronized boolean markStopping(Ticket ticket) {
        Slot slot = current(ticket);
        if (slot == null) {
            return false;
        }
        slot.phase = Phase.STOPPING;
        return true;
    }

    /**
     * 任务真正结束（finally 里调用）。只有当前 epoch 才有权释放。
     *
     * @return true 表示释放成功；false 表示这是旧任务迟到的回调，什么也没动
     */
    public synchronized boolean release(Ticket ticket) {
        Slot slot = current(ticket);
        if (slot == null) {
            log.warn("[{}] 旧任务迟到的结束回调被忽略（epoch={}），不影响当前任务状态",
                    ticket.platform(), ticket.epoch());
            return false;
        }
        slots.remove(ticket.platform());
        log.info("[{}] 任务槽位已释放（epoch={}），当前并行 {} 个平台: {}",
                ticket.platform(), ticket.epoch(), slots.size(), slots.keySet());
        return true;
    }

    private Slot current(Ticket ticket) {
        Slot slot = slots.get(ticket.platform());
        return slot != null && slot.epoch == ticket.epoch() ? slot : null;
    }

    public synchronized boolean holding(String platform) {
        return slots.containsKey(platform);
    }

    public synchronized Phase phase(String platform) {
        Slot slot = slots.get(platform);
        return slot == null ? Phase.IDLE : slot.phase;
    }

    public synchronized long startedAt(String platform) {
        Slot slot = slots.get(platform);
        return slot == null ? 0L : slot.startedAt;
    }

    /** 供 /api/platforms/status 使用：并行数量、上限、各平台阶段 */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        int running = 0;
        List<String> active = new ArrayList<>();
        Map<String, Object> phases = new LinkedHashMap<>();
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            phases.put(entry.getKey(), entry.getValue().phase.name());
            active.add(entry.getKey());
            if (entry.getValue().phase == Phase.RUNNING) {
                running++;
            }
        }
        result.put("maxParallel", limit);
        result.put("activeCount", active.size());
        result.put("runningCount", running);
        result.put("activePlatforms", active);
        result.put("phases", phases);
        return result;
    }

    /** 只读快照：当前占用中的平台名，用于给前端提示 */
    public synchronized Set<String> activePlatforms() {
        return new LinkedHashSet<>(slots.keySet());
    }

    /** 服务内部用于在状态里附带并行信息 */
    public synchronized Map<String, Object> parallelView(String platform) {
        Map<String, Object> view = new HashMap<>();
        view.put("maxParallel", limit);
        view.put("parallelActiveCount", slots.size());
        view.put("parallelPhase", phase(platform).name());
        view.put("parallelActivePlatforms", new ArrayList<>(slots.keySet()));
        return view;
    }
}
