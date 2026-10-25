package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 单个平台的浏览器会话。
 *
 * <p>每个平台独占：一条执行线程、一个 Playwright 实例、一个持久化 BrowserContext、
 * 一个持久化用户资料目录和一个调试端口。Playwright Java 对象不是线程安全的，
 * 因此这些对象只在本会话的线程上创建、使用和关闭；跨线程调用一律通过 {@link #call}
 * 投递到本会话线程，绝不复用其它平台的对象。</p>
 *
 * <p>与旧实现的区别：旧实现把所有平台放进同一个 BrowserContext 和同一条单线程执行器，
 * 一个平台等待弹窗时其它平台只能排队。现在线程归属用 ThreadLocal 精确判断所属会话，
 * 不再靠线程名判断，也不会出现"自己向自己投递再等待"的死锁。</p>
 */
@Slf4j
public class PlatformBrowserSession {

    /** 本会话所属线程，用于判断"当前线程是不是这个会话的" */
    private static final ThreadLocal<PlatformBrowserSession> OWNER = new ThreadLocal<>();

    private static final int DEFAULT_TIMEOUT = 30_000;
    /** 登录探针超时：探针失败只代表"没看到"，不能拖住主流程 */
    private static final double LOCATOR_PROBE_TIMEOUT = 5_000;
    /** 页面加载状态超时：SPA 的 NETWORKIDLE 可能永远等不到 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    /** 平台登录探测与登录后的动作，由 PlaywrightManager 按平台提供 */
    public interface LoginSupport {
        /** 只读探测，运行在本会话线程上 */
        boolean check(Page page);

        /** 探测到登录成功：保存 Cookie、更新状态 */
        void onLoggedIn(Page page);

        /** 探测到未登录：可选地引导到登录页/二维码 */
        void onLoggedOut(Page page);
    }

    private final String platform;
    private final String entryUrl;
    private final String domainSuffix;
    private final Path userDataDir;
    private final int cdpPort;
    private final LoginSupport loginSupport;
    /** 从数据库读取该平台 Cookie JSON */
    private final Supplier<String> cookieLoader;
    /** 把该平台 Cookie JSON 写回数据库 */
    private final BiConsumer<String, String> cookieSaver;

    private final ExecutorService executor;
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final Object initLock = new Object();

    private volatile Playwright playwright;
    private volatile BrowserContext context;
    private volatile Page page;
    private volatile boolean browserClosed;
    private volatile boolean monitoringPaused;
    private volatile Boolean lastLoginState;
    private volatile long readySince;
    private volatile String lastError = "";

    PlatformBrowserSession(String platform,
                           String entryUrl,
                           String domainSuffix,
                           Path userDataDir,
                           int cdpPort,
                           LoginSupport loginSupport,
                           Supplier<String> cookieLoader,
                           BiConsumer<String, String> cookieSaver) {
        this.platform = platform;
        this.entryUrl = entryUrl;
        this.domainSuffix = domainSuffix;
        this.userDataDir = userDataDir;
        this.cdpPort = cdpPort;
        this.loginSupport = loginSupport;
        this.cookieLoader = cookieLoader;
        this.cookieSaver = cookieSaver;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                OWNER.set(this);
                runnable.run();
                OWNER.remove();
            }, "playwright-" + platform);
            thread.setDaemon(true);
            return thread;
        });
    }

    public String platform() {
        return platform;
    }

    public int cdpPort() {
        return cdpPort;
    }

    public boolean isOwnerThread() {
        return OWNER.get() == this;
    }

    public boolean isReady() {
        return playwright != null && context != null && page != null && !browserClosed;
    }

    /** 最近一次初始化失败的原始原因；成功后清空。供界面提示用，不做重试。 */
    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    public Page page() {
        return page;
    }

    public BrowserContext context() {
        return context;
    }

    /** 本会话线程上是否有前台任务（投递、初始化、登录等）正在执行 */
    public boolean isBusy() {
        return queueDepth.get() > 0;
    }

    /**
     * 在本会话线程上执行并等待结果；已经在本会话线程上时就地执行。
     * 就地执行是避免"自己等自己"造成死锁的关键。
     */
    public <T> T call(Callable<T> task) {
        if (isOwnerThread()) {
            queueDepth.incrementAndGet();
            try {
                return task.call();
            } catch (Exception e) {
                throw toRuntime(e);
            } finally {
                queueDepth.decrementAndGet();
            }
        }
        queueDepth.incrementAndGet();
        try {
            return executor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    queueDepth.decrementAndGet();
                }
            }).get();
        } catch (ExecutionException e) {
            throw toRuntime(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 " + platform + " 浏览器任务被中断", e);
        } catch (RejectedExecutionException e) {
            queueDepth.decrementAndGet();
            throw new IllegalStateException(platform + " 浏览器会话已关闭", e);
        }
    }

    /**
     * 后台任务：本会话正忙（例如正在投递）时直接跳过，不排队、不阻塞调用线程。
     * 登录监控走这条路径，因此一个平台忙只会跳过该平台自己的监控。
     */
    public boolean tryRun(Runnable task) {
        if (queueDepth.get() > 0 || !isReady()) {
            return false;
        }
        queueDepth.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.debug("[{}] 浏览器后台任务异常: {}", platform, e.getMessage());
                } finally {
                    queueDepth.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            queueDepth.decrementAndGet();
            return false;
        }
    }

    /** 按需初始化：只有真正用到这个平台时才启动它的浏览器 */
    public void ensureReady() {
        if (isReady()) {
            return;
        }
        synchronized (initLock) {
            if (isReady()) {
                return;
            }
            call(() -> {
                initInternal();
                return null;
            });
        }
    }

    private void initInternal() {
        if (isReady()) {
            return;
        }
        log.info("========================================");
        log.info("  初始化 {} 浏览器会话（独立进程，资料目录 {}）", platform, userDataDir);
        log.info("========================================");
        closeStaleQuietly();
        try {
            playwright = createPlaywright();
            log.info("[{}] ✓ Playwright 引擎已启动 (driver: {})", platform, describeDriver());

            sanitizeProfileExitState();

            List<String> args = new ArrayList<>(List.of(
                    "--start-maximized",
                    "--test-type",
                    "--disable-session-crashed-bubble",
                    "--hide-crash-restore-bubble",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--no-proxy-server",
                    "--proxy-bypass-list=*",
                    "--disable-blink-features=AutomationControlled"
            ));
            // 默认保持 Chrome 自带沙箱（关掉它本身就是自动化特征）。
            // 受限环境（容器/CI/无法初始化 Chrome sandbox 的本机）可用这两个开关，
            // 只影响本地验证，不改变默认行为。
            boolean noSandbox = Boolean.getBoolean("getjobs.browser.no-sandbox");
            if (noSandbox) {
                args.add("--no-sandbox");
            }
            if (Boolean.getBoolean("getjobs.browser.disable-gpu")) {
                args.add("--disable-gpu");
            }
            if (Boolean.getBoolean("getjobs.cdp.enabled")) {
                // 每个平台用自己的端口，避免多个浏览器争用同一个端口
                args.add("--remote-debugging-port=" + cdpPort);
            }

            context = playwright.chromium().launchPersistentContext(userDataDir,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("chrome")
                            .setHeadless(Boolean.getBoolean("getjobs.browser.headless"))
                            .setSlowMo(Boolean.getBoolean("getjobs.browser.headless") ? 0 : 50)
                            .setViewportSize(null)
                            .setChromiumSandbox(!noSandbox)
                            .setArgs(args));
            browserClosed = false;

            context.onClose(closed -> {
                browserClosed = true;
                log.warn("[{}] 浏览器已关闭，下次操作会自动重新初始化（不影响其它平台）", platform);
            });

            List<Page> startupPages = new ArrayList<>(context.pages());
            page = context.newPage();
            page.setDefaultTimeout(DEFAULT_TIMEOUT);
            for (Page startup : startupPages) {
                try {
                    startup.close();
                } catch (Exception e) {
                    log.debug("[{}] 关闭启动空白页失败（忽略）: {}", platform, e.getMessage());
                }
            }

            loadStoredCookies();
            navigateEntry();
            installLoginMonitoring();

            readySince = System.currentTimeMillis();
            lastError = "";
            boolean loggedIn = probeLogin();
            setLoginState(loggedIn);
            if (loggedIn) {
                loginSupport.onLoggedIn(page);
            } else {
                loginSupport.onLoggedOut(page);
            }
            log.info("[{}] ✓ 浏览器会话就绪（登录状态: {}）", platform, loggedIn ? "已登录" : "未登录");
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.error("[{}] 浏览器会话初始化失败", platform, e);
            closeStaleQuietly();
            throw new RuntimeException(platform + " 浏览器初始化失败: " + lastError, e);
        }
    }

    private void loadStoredCookies() {
        try {
            String json = cookieLoader == null ? null : cookieLoader.get();
            if (json == null || json.isBlank()) {
                log.info("[{}] 数据库没有该平台 Cookie，依赖持久化资料目录的登录态", platform);
                return;
            }
            List<Cookie> cookies = filterByDomain(parseCookies(json), domainSuffix);
            if (cookies.isEmpty()) {
                log.warn("[{}] 数据库 Cookie 中没有匹配 {} 的记录", platform, domainSuffix);
                return;
            }
            context.addCookies(cookies);
            log.info("[{}] 已从数据库注入 {} 条 Cookie", platform, cookies.size());
        } catch (Exception e) {
            log.warn("[{}] 从数据库加载 Cookie 失败: {}", platform, e.getMessage());
        }
    }

    private void navigateEntry() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.navigate(entryUrl, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                break;
            } catch (Exception e) {
                boolean onSite = false;
                try {
                    String url = page.url();
                    onSite = url != null && url.contains(domainSuffix);
                } catch (Exception ignored) {
                }
                if (onSite) {
                    break;
                }
                log.warn("[{}] 打开入口页失败（第 {} 次）: {}", platform, attempt, e.getMessage());
                if (attempt < 3) {
                    sleepQuietly(2_000);
                }
            }
        }
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception e) {
            log.debug("[{}] 等待页面加载状态失败: {}", platform, e.getMessage());
        }
    }

    private void installLoginMonitoring() {
        Page monitored = page;
        monitored.onFrameNavigated(frame -> {
            if (frame == monitored.mainFrame()) {
                requestLoginCheck();
            }
        });
    }

    /** 供定时任务与页面导航事件调用：只检查本平台，且本平台忙时直接跳过 */
    public void requestLoginCheck() {
        if (monitoringPaused) {
            return;
        }
        tryRun(() -> {
            if (monitoringPaused) {
                return;
            }
            boolean now = probeLogin();
            Boolean previous = lastLoginState;
            lastLoginState = now;
            if (now && !Boolean.TRUE.equals(previous)) {
                loginSupport.onLoggedIn(page);
            } else if (!now && Boolean.TRUE.equals(previous)) {
                loginSupport.onLoggedOut(page);
            }
        });
    }

    private boolean probeLogin() {
        try {
            return loginSupport.check(page);
        } catch (Exception e) {
            log.debug("[{}] 登录状态探测失败: {}", platform, e.getMessage());
            return false;
        }
    }

    private void setLoginState(boolean loggedIn) {
        lastLoginState = loggedIn;
    }

    public void pauseMonitoring() {
        monitoringPaused = true;
    }

    public void resumeMonitoring() {
        monitoringPaused = false;
    }

    public boolean isMonitoringPaused() {
        return monitoringPaused;
    }

    /** 在会话线程上执行登录引导（打开登录页/二维码） */
    public void openLogin() {
        ensureReady();
        call(() -> {
            if (isReady()) {
                monitoringPaused = false;
                loginSupport.onLoggedOut(page);
            }
            return null;
        });
    }

    /** 保存本平台 Cookie 到数据库，返回是否成功 */
    public boolean saveCookies(String remark) {
        if (!isReady()) {
            return false;
        }
        return call(() -> {
            List<Cookie> cookies = filterByDomain(context.cookies(), domainSuffix);
            String json = new ObjectMapper().writeValueAsString(cookies);
            cookieSaver.accept(json, remark);
            log.info("[{}] 已保存 {} 条 Cookie 到数据库，remark={}", platform, cookies.size(), remark);
            return true;
        });
    }

    /** 清理本平台上下文 Cookie，不影响其它平台 */
    public void clearCookies() {
        if (!isReady()) {
            log.info("[{}] 浏览器未启动，跳过上下文 Cookie 清理", platform);
            return;
        }
        call(() -> {
            context.clearCookies();
            log.info("[{}] 已清理本平台上下文 Cookie", platform);
            return null;
        });
    }

    /**
     * 关闭本平台浏览器：只影响这个平台，其它平台继续运行。
     * 空闲时在会话线程上正常关闭；会话正忙（任务卡在某个 Playwright 调用里）时，
     * 从外部关闭浏览器进程以解除阻塞 —— 这是"强制结束"路径，会有一次跨线程 close。
     */
    public void close() {
        if (isOwnerThread()) {
            closeStaleQuietly();
            return;
        }
        if (!isReady()) {
            closeStaleQuietly();
            return;
        }
        if (tryRun(this::closeStaleQuietly)) {
            // 等关闭真正完成，保证调用方返回时 isReady() 已是 false；超时说明线程卡住
            long deadline = System.currentTimeMillis() + 5_000;
            while (isReady() && System.currentTimeMillis() < deadline) {
                sleepQuietly(50);
            }
            if (!isReady()) {
                return;
            }
        }
        log.warn("[{}] 会话线程未能在 5 秒内关闭浏览器，直接从外部关闭进程（仅影响该平台）", platform);
        closeStaleQuietly();
    }

    private void closeStaleQuietly() {
        Playwright stalePlaywright = playwright;
        BrowserContext staleContext = context;
        Page stalePage = page;
        playwright = null;
        context = null;
        page = null;
        browserClosed = true;
        lastLoginState = null;
        try {
            if (stalePage != null) {
                stalePage.close();
            }
        } catch (Exception ignored) {
            // 浏览器可能已经不在了
        }
        try {
            if (staleContext != null) {
                staleContext.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (stalePlaywright != null) {
                stalePlaywright.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 应用退出时调用：尽量在会话线程上关闭，最多等 10 秒，然后停掉线程 */
    public void shutdown() {
        try {
            if (isOwnerThread()) {
                closeStaleQuietly();
            } else if (!executor.isShutdown()) {
                try {
                    executor.submit(() -> {
                        closeStaleQuietly();
                        return null;
                    }).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.debug("[{}] 会话线程关闭超时，改为直接关闭浏览器: {}", platform, e.getMessage());
                    closeStaleQuietly();
                }
            }
        } catch (Exception e) {
            log.debug("[{}] 关闭浏览器会话失败: {}", platform, e.getMessage());
            closeStaleQuietly();
        } finally {
            executor.shutdownNow();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", platform);
        status.put("browserReady", isReady());
        status.put("busy", isBusy());
        status.put("monitoringPaused", monitoringPaused);
        status.put("cdpPort", cdpPort);
        status.put("profileDir", userDataDir.toString());
        status.put("lastError", lastError);
        status.put("readySince", readySince);
        return status;
    }

    // ------------------------------------------------------------------
    // 浏览器进程与 profile
    // ------------------------------------------------------------------

    private void sanitizeProfileExitState() {
        Path preferences = userDataDir.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(preferences)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(preferences.toFile());
            if (!(root instanceof ObjectNode rootNode)) {
                return;
            }
            JsonNode profile = rootNode.get("profile");
            if (!(profile instanceof ObjectNode profileNode)) {
                return;
            }
            String exitType = profileNode.path("exit_type").asText("");
            boolean exitedCleanly = profileNode.path("exited_cleanly").asBoolean(true);
            if ("Normal".equals(exitType) && exitedCleanly) {
                return;
            }
            profileNode.put("exit_type", "Normal");
            profileNode.put("exited_cleanly", true);
            mapper.writeValue(preferences.toFile(), rootNode);
            log.info("[{}] 已清除 profile 崩溃退出标记（原 exit_type={}）", platform, exitType);
        } catch (Exception e) {
            log.warn("[{}] 清理 profile 退出状态失败（不影响启动）: {}", platform, e.getMessage());
        }
    }

    private Playwright createPlaywright() {
        if (System.getProperty("playwright.cli.dir") == null) {
            Path driverDir = locatePatchrightDriver();
            if (driverDir != null) {
                System.setProperty("playwright.cli.dir", driverDir.toString());
            } else {
                log.warn("未找到 patchright driver，将退回官方 driver-bundle（反检测能力大幅下降）");
            }
        }
        Map<String, String> env = new HashMap<>();
        if (System.getProperty("playwright.cli.dir") != null && System.getenv("PLAYWRIGHT_NODEJS_PATH") == null) {
            String nodePath = locateNodeExecutable();
            if (nodePath != null) {
                env.put("PLAYWRIGHT_NODEJS_PATH", nodePath);
            }
        }
        return env.isEmpty()
                ? Playwright.create()
                : Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    private Path locatePatchrightDriver() {
        Path projectDir = Paths.get(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                projectDir.resolve("build").resolve("patchright-driver"),
                projectDir.resolve("patchright-driver"),
                projectDir.getParent() == null ? projectDir
                        : projectDir.getParent().resolve("build").resolve("patchright-driver"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("package").resolve("cli.js"))) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    private String locateNodeExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        String exe = windows ? "node.exe" : "node";
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry.trim()).resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private String describeDriver() {
        String cliDir = System.getProperty("playwright.cli.dir");
        if (cliDir == null || cliDir.isBlank()) {
            return "官方 driver-bundle";
        }
        return "patchright @ " + cliDir;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException toRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(throwable);
    }

    // ------------------------------------------------------------------
    // Cookie 工具（数据库里的 Cookie 是 JSON 数组）
    // ------------------------------------------------------------------

    public static List<Cookie> parseCookies(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();
        try {
            JsonNode array = new ObjectMapper().readTree(cookieJson);
            for (JsonNode node : array) {
                if (!node.hasNonNull("name") || !node.hasNonNull("value")) {
                    continue;
                }
                Cookie cookie = new Cookie(node.get("name").asText(), node.get("value").asText());
                if (node.hasNonNull("domain")) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.hasNonNull("path")) {
                    cookie.path = node.get("path").asText();
                }
                if (node.hasNonNull("expires")) {
                    cookie.expires = node.get("expires").asDouble();
                }
                if (node.hasNonNull("httpOnly")) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.hasNonNull("secure")) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.hasNonNull("sameSite")) {
                    String sameSite = node.get("sameSite").asText();
                    if (!sameSite.isBlank()) {
                        cookie.sameSite = SameSiteAttribute.valueOf(sameSite.toUpperCase(Locale.ROOT));
                    }
                }
                cookies.add(cookie);
            }
        } catch (Exception e) {
            log.error("解析 Cookie JSON 失败: {}", e.getMessage());
        }
        return cookies;
    }

    public static List<Cookie> filterByDomain(List<Cookie> cookies, String domainSuffix) {
        List<Cookie> filtered = new ArrayList<>();
        if (cookies == null || cookies.isEmpty() || domainSuffix == null) {
            return filtered;
        }
        String suffix = domainSuffix.toLowerCase(Locale.ROOT);
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }
        return filtered;
    }

}
