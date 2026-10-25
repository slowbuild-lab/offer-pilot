package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.manager.PlatformTaskGate;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行前预检。
 *
 * <p>不启动浏览器、不改变任务状态；额度查询可让已到期的次日设置生效。目的是把"点开始之后才发现没登录/没配置/没额度"
 * 提前到点击之前。真正的启动校验仍然保留在各自的 /start 接口里，预检只是更早给用户一个清单。</p>
 *
 * <p>WARN 不阻塞启动（例如浏览器还没开、该平台没有每日上限），只在界面上用文字提示。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPreflightService {

    private static final String PASS = "PASS";
    private static final String WARN = "WARN";
    private static final String FAIL = "FAIL";

    private final PlaywrightManager playwrightManager;
    private final PlatformTaskGate gate;
    private final ConfigService configService;
    private final ZhilianJobService zhilianJobService;
    private final com.getjobs.application.service.BossDeliveryLedger bossLedger;
    private final com.getjobs.application.service.JobProfileService profiles;
    private final com.getjobs.application.service.JobProfileRuntime profileRuntime;

    public Map<String, Object> check(String platform) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(loginCheck(platform));
        checks.add(configCheck(platform));
        checks.add(quotaCheck(platform));
        checks.add(parallelCheck(platform));
        checks.add(browserCheck(platform));

        boolean ready = checks.stream().noneMatch(c -> FAIL.equals(c.get("status")));
        List<String> warnings = checks.stream()
                .filter(c -> WARN.equals(c.get("status")))
                .map(c -> String.valueOf(c.get("hint")))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);
        result.put("ready", ready);
        result.put("checks", checks);
        result.put("warnings", warnings);
        return result;
    }

    private Map<String, Object> loginCheck(String platform) {
        boolean loggedIn = playwrightManager.isLoggedIn(platform);
        return loggedIn
                ? check("login", "登录状态", PASS, "已登录", null)
                : check("login", "登录状态", FAIL, "未登录或登录已失效，需要扫码", "login");
    }

    private Map<String, Object> configCheck(String platform) {
        try {
            var active = profiles.activeFor(platform);
            if (active.isPresent()) {
                profileRuntime.cityCode(platform, active.get().rules().city());
                return check("config", "投递条件", PASS, "已确认求职方案 v" + active.get().version(), null);
            }
            boolean ok;
            String missing;
            switch (platform) {
                case "boss" -> {
                    BossConfig config = configService.getBossConfig();
                    ok = hasText(config.getKeywords()) && config.getCityCode() != null && !config.getCityCode().isEmpty();
                    missing = "请先在 BOSS 页面填写搜索关键词和城市并保存";
                }
                case "zhilian" -> {
                    ZhilianConfig config = configService.getZhilianConfig();
                    ok = hasText(config.getKeywords()) && config.getCityCode() != null && !config.getCityCode().isBlank();
                    missing = "请先在智联页面填写搜索关键词和城市并保存";
                }
                case "liepin" -> {
                    LiepinConfig config = configService.getLiepinConfig();
                    ok = hasText(config.getKeywords());
                    missing = "请先在猎聘页面填写搜索关键词并保存";
                }
                case "51job" -> {
                    Job51Config config = configService.getJob51Config();
                    ok = hasText(config.getKeywords());
                    missing = "请先在前程无忧页面填写搜索关键词并保存";
                }
                default -> {
                    ok = false;
                    missing = "不支持的平台";
                }
            }
            return ok
                    ? check("config", "投递条件", PASS, "关键词与城市已配置", null)
                    : check("config", "投递条件", FAIL, missing, "config");
        } catch (Exception e) {
            log.warn("[{}] 预检读取配置失败: {}", platform, e.getMessage());
            return check("config", "投递条件", FAIL, "配置读取失败：" + e.getMessage(), "config");
        }
    }

    private Map<String, Object> quotaCheck(String platform) {
        if ("boss".equals(platform)) {
            int remaining = ((Number) bossLedger.summary().get("remainingToday")).intValue();
            return check("quota", "今日额度", remaining > 0 ? PASS : FAIL,
                    remaining > 0 ? "今日还可尝试 " + remaining + " 个岗位" : "今日尝试额度已用完", "config");
        }
        if ("zhilian".equals(platform)) {
            try {
                Object remaining = zhilianJobService.getStatus().get("remainingToday");
                if (remaining instanceof Number number) {
                    if (number.intValue() <= 0) {
                        return check("quota", "今日额度", FAIL, "今日尝试次数已用完；上调额度次日生效", "config");
                    }
                    return check("quota", "今日额度", PASS, "今日还可尝试 " + number.intValue() + " 次", null);
                }
            } catch (Exception e) {
                log.warn("预检读取智联额度失败: {}", e.getMessage());
                return check("quota", "今日额度", WARN, "投递记录暂时读取失败，启动后仍会再次校验", null);
            }
        }
        // BOSS / 猎聘 / 前程无忧 目前没有本地每日上限，不阻塞启动，只提示
        return check("quota", "今日额度", WARN, "该平台未设置每日尝试上限，本次不会按次数自动停止", null);
    }

    private Map<String, Object> parallelCheck(String platform) {
        PlatformTaskGate.Phase phase = gate.phase(platform);
        if (phase != PlatformTaskGate.Phase.IDLE) {
            String message = switch (phase) {
                case STARTING -> "该平台任务正在启动中";
                case RUNNING -> "该平台已有投递任务在运行";
                case STOPPING -> "该平台任务正在停止，需等它退出";
                case IDLE -> "";
            };
            return check("parallel", "并行占用", FAIL, message, null);
        }
        Map<String, Object> snapshot = gate.snapshot();
        int active = ((Number) snapshot.get("activeCount")).intValue();
        int max = ((Number) snapshot.get("maxParallel")).intValue();
        if (active >= max) {
            return check("parallel", "并行占用", FAIL,
                    "已达到并行上限（" + max + " 个），请先停止一个平台", null);
        }
        return check("parallel", "并行占用", PASS,
                "当前并行 " + active + "/" + max, null);
    }

    private Map<String, Object> browserCheck(String platform) {
        boolean ready = playwrightManager.isBrowserReady(platform);
        return ready
                ? check("browser", "浏览器", PASS, "浏览器已启动", null)
                : check("browser", "浏览器", WARN, "浏览器尚未启动，开始时会自动打开（首次约 10-30 秒）", null);
    }

    private static boolean hasText(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private static Map<String, Object> check(String id, String label, String status, String hint, String action) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", label);
        item.put("status", status);
        item.put("hint", hint);
        if (action != null) {
            item.put("action", action);
        }
        return item;
    }
}
