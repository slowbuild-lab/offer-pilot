package com.getjobs.worker.manager;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 浏览器会话门面：按平台管理独立的浏览器会话、登录状态与 Cookie。
 *
 * <p>改造要点（本次多平台并行）：原来所有平台共用一条 playwright-thread 单线程和同一个
 * BrowserContext，投递流程整段提交到这条线程上执行，因此两个平台实际上只能排队。
 * 现在每个平台有独立的 {@link PlatformBrowserSession}（独立线程、独立 Playwright、
 * 独立上下文、独立持久化资料目录、独立调试端口），一个平台等待弹窗时另一个平台照常推进。</p>
 *
 * <p>兼容性：原有的公开方法（getBossPage、ensureReady、saveXxxCookiesToDb、
 * pauseXxxMonitoring、triggerXxxLogin 等）全部保留；新增按平台的
 * {@link #ensureReady(String)} / {@link #callOn(String, Function)}。旧的无参
 * {@link #callOnPlaywright} 仍保留，但内部只落到 boss 会话，仅为兼容旧调用。</p>
 */
@Slf4j
@Component
@Lazy
public class PlaywrightManager {
    @Autowired @Lazy private com.getjobs.application.service.DeliveryPacingService pacing;

    private static final int DEFAULT_TIMEOUT = 30_000;
    private static final double LOCATOR_PROBE_TIMEOUT = 5_000;
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    private static final String BOSS_URL = "https://www.zhipin.com";
    private static final String BOSS_ENTRY_URL = "https://www.zhipin.com/web/geek/jobs";
    private static final String LIEPIN_URL = "https://www.liepin.com";
    private static final String JOB51_URL = "https://www.51job.com";
    private static final String ZHILIAN_URL = "https://www.zhaopin.com";

    private static final String BOSS = "boss";
    private static final String ZHILIAN = "zhilian";
    private static final String LIEPIN = "liepin";
    private static final String JOB51 = "51job";

    private static final Set<String> BOSS_LOGIN_COOKIES = Set.of("bst", "wt2", "zp_at", "geek_zp_token");

    /** 各平台独立的持久化资料目录，旧的 browser-data/ 原样保留、不再写入 */
    private static final String DEFAULT_PROFILE_ROOT = "browser-data";

    private final Map<String, PlatformBrowserSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    /** 启动时数据库里是否已有该平台 Cookie；用于"还没打开浏览器"时的乐观状态 */
    private final Map<String, Boolean> storedCookies = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loginGuided = new ConcurrentHashMap<>();

    @Autowired
    private CookieService cookieService;

    // ------------------------------------------------------------------
    // 会话注册
    // ------------------------------------------------------------------

    @PostConstruct
    public void initSessions() {
        register(BOSS, BOSS_ENTRY_URL, "zhipin.com", 7866, this::detectBossLogin, this::guideBossLogin);
        register(ZHILIAN, ZHILIAN_URL, "zhaopin.com", 7867, this::detectZhilianLogin, this::guideZhilianLogin);
        register(LIEPIN, LIEPIN_URL, "liepin.com", 7868, this::detectLiepinLogin, this::guideLiepinLogin);
        register(JOB51, JOB51_URL, "51job.com", 7869, this::detect51jobLogin, this::guide51jobLogin);
        log.info("已注册 {} 个平台浏览器会话（按需启动，启动时不打开任何浏览器）", sessions.size());
    }

    private void register(String platform,
                          String defaultEntryUrl,
                          String domain,
                          int cdpPort,
                          Function<Page, Boolean> detector,
                          Consumer<Page> loginGuide) {
        String entryUrl = System.getProperty("getjobs." + platform + ".entryUrl", defaultEntryUrl);
        Path profileDir = profileRoot().resolve(platform);
        storedCookies.put(platform, hasStoredCookie(platform));
        PlatformBrowserSession.LoginSupport support = new PlatformBrowserSession.LoginSupport() {
            @Override
            public boolean check(Page page) {
                return Boolean.TRUE.equals(detector.apply(page));
            }

            @Override
            public void onLoggedIn(Page page) {
                savePlatformCookies(platform, "login success");
                setLoginStatus(platform, true);
            }

            @Override
            public void onLoggedOut(Page page) {
                setLoginStatus(platform, false);
                AtomicBoolean guided = new AtomicBoolean(Boolean.TRUE.equals(loginGuided.get(platform)));
                if (guided.get()) {
                    return;
                }
                loginGuided.put(platform, true);
                try {
                    loginGuide.accept(page);
                } catch (Exception e) {
                    log.debug("[{}] 打开登录页失败: {}", platform, e.getMessage());
                }
            }
        };
        PlatformBrowserSession session = new PlatformBrowserSession(
                platform, entryUrl, domain, profileDir, cdpPort, support,
                () -> loadCookieJson(platform),
                (json, remark) -> saveCookieJson(platform, json, remark));
        sessions.put(platform, session);
        log.info("[{}] 会话已注册：入口 {}，资料目录 {}，调试端口 {}", platform, entryUrl, profileDir, cdpPort);
    }

    private static Path profileRoot() {
        String root = System.getProperty("getjobs.profile.root", DEFAULT_PROFILE_ROOT);
        return Paths.get(System.getProperty("user.dir")).resolve(root);
    }

    private boolean hasStoredCookie(String platform) {
        try {
            CookieEntity cookie = cookieService.getCookieByPlatform(platform);
            return cookie != null && cookie.getCookieValue() != null && !cookie.getCookieValue().isBlank();
        } catch (Exception e) {
            log.debug("[{}] 读取数据库 Cookie 失败: {}", platform, e.getMessage());
            return false;
        }
    }

    private String loadCookieJson(String platform) {
        CookieEntity cookie = cookieService.getCookieByPlatform(platform);
        return cookie == null ? null : cookie.getCookieValue();
    }

    private void saveCookieJson(String platform, String json, String remark) {
        cookieService.saveOrUpdateCookie(platform, json, remark);
    }

    /**
     * 启动时的旧实现会一次性拉起一个 Chrome 并开 4 个标签页。
     * 现在改为按需初始化：只有真正要投递或要扫码登录时才启动对应平台的浏览器，
     * 避免启动时打开多个用不到的浏览器进程。
     */
    public void init() {
        log.info("浏览器改为按平台按需启动，本次启动不打开任何浏览器窗口");
    }

    // ------------------------------------------------------------------
    // 会话访问
    // ------------------------------------------------------------------

    private PlatformBrowserSession session(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        if (session == null) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }
        return session;
    }

    /** 按需启动指定平台的浏览器（每个平台自己的线程与 Playwright 实例） */
    public void ensureReady(String platform) {
        PlatformBrowserSession session = session(platform);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                session.ensureReady();
                return;
            } catch (RuntimeException e) {
                if (attempt >= 2) {
                    throw e;
                }
                log.warn("[{}] 浏览器初始化失败，清理后重试一次: {}", platform, e.getMessage());
                session.close();
            }
        }
    }

    /** 旧的全局入口：只落到 boss 会话，保留是为了兼容历史调用 */
    @Deprecated
    public void ensureReady() {
        ensureReady(BOSS);
    }

    /**
     * 在该平台自己的线程上执行，并把该平台的 Page 交给任务。
     * 任务里可以放心调用 Playwright：它一定运行在创建这些对象的线程上。
     */
    public <T> T callOn(String platform, Function<Page, T> task) {
        PlatformBrowserSession session = session(platform);
        return session.call(() -> {
            Page page = session.page();
            if (page == null) {
                throw new IllegalStateException(platform + " 浏览器页面未初始化");
            }
            return task.apply(page);
        });
    }

    /** 旧入口：只落到 boss 会话，保留是为了兼容历史调用 */
    @Deprecated
    public <T> T callOnPlaywright(java.util.concurrent.Callable<T> task) {
        return session(BOSS).call(task);
    }

    @Deprecated
    public void runOnPlaywright(Runnable task) {
        callOnPlaywright(() -> {
            task.run();
            return null;
        });
    }

    /** 某个平台是否正忙（投递/初始化/登录），只影响该平台自己的监控 */
    public boolean isPlatformBusy(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session != null && session.isBusy();
    }

    /** 兼容旧字段：任一平台忙 */
    @Deprecated
    public boolean isPlaywrightBusy() {
        return sessions.values().stream().anyMatch(PlatformBrowserSession::isBusy);
    }

    public Page getBossPage() {
        return page(BOSS);
    }

    public Page getZhilianPage() {
        return page(ZHILIAN);
    }

    public Page getLiepinPage() {
        return page(LIEPIN);
    }

    public Page getJob51Page() {
        return page(JOB51);
    }

    private Page page(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session == null ? null : session.page();
    }

    public com.microsoft.playwright.BrowserContext getContext() {
        PlatformBrowserSession session = sessions.get(BOSS);
        return session == null ? null : session.context();
    }

    public com.microsoft.playwright.BrowserContext getContext(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session == null ? null : session.context();
    }

    public int getCdpPort() {
        return cdpPort(BOSS);
    }

    public int cdpPort(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session == null ? 0 : session.cdpPort();
    }

    /** 有任一平台浏览器已启动 */
    public boolean isInitialized() {
        return sessions.values().stream().anyMatch(PlatformBrowserSession::isReady);
    }

    /** 该平台浏览器是否已经启动（不会触发起动） */
    public boolean isBrowserReady(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session != null && session.isReady();
    }

    /**
     * 该平台最近一次启动浏览器失败的原因（空字符串表示没有失败）。
     * 打开浏览器是异步的，失败必须能在界面上看到，否则用户只看到"点了没反应"。
     */
    public String lastBrowserError(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        return session == null ? "" : session.lastError();
    }

    /** 供状态接口使用：各平台浏览器与登录状态 */
    public Map<String, Object> browserStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, PlatformBrowserSession> entry : sessions.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>(entry.getValue().status());
            item.put("isLoggedIn", isLoggedIn(entry.getKey()));
            result.put(entry.getKey(), item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 登录状态
    // ------------------------------------------------------------------

    public boolean isLoggedIn(String platform) {
        Boolean value = loginStatus.get(platform);
        if (value != null) {
            return value;
        }
        // 还没打开浏览器，但数据库里有该平台的 Cookie：乐观认为仍是登录态，
        // 真正的验证在 ensureReady() 里做，避免用户看不到"开始"按钮也没法重新登录。
        return Boolean.TRUE.equals(storedCookies.get(platform));
    }

    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previous = loginStatus.get(platform);
        if (previous != null && previous == isLoggedIn) {
            return;
        }
        loginStatus.put(platform, isLoggedIn);
        if (isLoggedIn) {
            storedCookies.put(platform, true);
        }
        LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
        for (Consumer<LoginStatusChange> listener : loginStatusListeners) {
            try {
                listener.accept(change);
            } catch (Exception e) {
                log.error("通知登录状态监听器失败: platform={}", platform, e);
            }
        }
        // 退出登录后把该平台窗口带回登录页：只在浏览器已经开着且空闲时做。
        // 不在这里拉起新浏览器，也不阻塞调用线程；初始化阶段的这一步会因为会话正忙被跳过，
        // 由 LoginSupport.onLoggedOut 负责二维码引导，避免重复导航。
        if (!isLoggedIn) {
            PlatformBrowserSession session = sessions.get(platform);
            if (session != null && session.isReady()) {
                session.tryRun(() -> {
                    try {
                        session.page().navigate(loginUrlFor(platform), new Page.NavigateOptions()
                                .setTimeout(30_000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    } catch (Exception e) {
                        log.debug("[{}] 退出登录后打开登录页失败: {}", platform, e.getMessage());
                    }
                });
            }
        }
    }

    private String loginUrlFor(String platform) {
        return switch (platform) {
            case BOSS -> BOSS_URL + "/web/user/?ka=header-login";
            case ZHILIAN -> "https://passport.zhaopin.com/login";
            case LIEPIN -> "https://www.liepin.com/login";
            case JOB51 -> "https://login.51job.com/login.php";
            default -> "about:blank";
        };
    }

    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // 定时登录检查：每个平台单独判断，一个平台忙只跳过它自己
    // ------------------------------------------------------------------

    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        for (PlatformBrowserSession session : sessions.values()) {
            try {
                if(pacing!=null&&!pacing.persistentState(session.platform()).get("paused").toString().isEmpty())continue;
                session.requestLoginCheck();
            } catch (Exception e) {
                log.debug("[{}] 定时登录检查异常: {}", session.platform(), e.getMessage());
            }
        }
    }

    public void pauseMonitoring(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        if (session != null) {
            session.pauseMonitoring();
        }
    }

    public void resumeMonitoring(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        if (session != null) {
            session.resumeMonitoring();
        }
    }

    public void pauseBossMonitoring() {
        pauseMonitoring(BOSS);
    }

    public void resumeBossMonitoring() {
        resumeMonitoring(BOSS);
    }

    public void pauseZhilianMonitoring() {
        pauseMonitoring(ZHILIAN);
    }

    public void resumeZhilianMonitoring() {
        resumeMonitoring(ZHILIAN);
    }

    public void pauseLiepinMonitoring() {
        pauseMonitoring(LIEPIN);
    }

    public void resumeLiepinMonitoring() {
        resumeMonitoring(LIEPIN);
    }

    public void pause51jobMonitoring() {
        pauseMonitoring(JOB51);
    }

    public void resume51jobMonitoring() {
        resumeMonitoring(JOB51);
    }

    // ------------------------------------------------------------------
    // Cookie
    // ------------------------------------------------------------------

    public void saveCookiesToDb(String platform, String remark) {
        PlatformBrowserSession session = session(platform);
        if (!session.isReady()) {
            throw new IllegalStateException(platform + " 浏览器未启动，暂无 Cookie 可保存");
        }
        session.saveCookies(remark);
    }

    public void saveBossCookiesToDb(String remark) {
        saveCookiesToDb(BOSS, remark);
    }

    public void saveZhilianCookiesToDb(String remark) {
        saveCookiesToDb(ZHILIAN, remark);
    }

    public void saveLiepinCookiesToDb(String remark) {
        saveCookiesToDb(LIEPIN, remark);
    }

    public void save51jobCookiesToDb(String remark) {
        saveCookiesToDb(JOB51, remark);
    }

    private void savePlatformCookies(String platform, String remark) {
        PlatformBrowserSession session = sessions.get(platform);
        if (session == null || !session.isReady()) {
            return;
        }
        try {
            session.saveCookies(remark);
        } catch (Exception e) {
            log.warn("[{}] 保存 Cookie 失败: {}", platform, e.getMessage());
        }
    }

    public void clearBossCookies() {
        session(BOSS).clearCookies();
    }

    public void clearZhilianCookies() {
        session(ZHILIAN).clearCookies();
    }

    public void clearLiepinCookies() {
        session(LIEPIN).clearCookies();
    }

    public void clear51jobCookies() {
        session(JOB51).clearCookies();
    }

    // ------------------------------------------------------------------
    // 登录入口（按需拉起对应平台浏览器）
    // ------------------------------------------------------------------

    public void openLogin(String platform) {
        PlatformBrowserSession session = session(platform);
        loginGuided.put(platform, false);
        loginStatus.remove(platform);
        session.openLogin();
    }

    public void triggerBossLogin() {
        openLogin(BOSS);
    }

    public void triggerZhilianLogin() {
        openLogin(ZHILIAN);
    }

    public void triggerLiepinLogin() {
        openLogin(LIEPIN);
    }

    public void trigger51jobLogin() {
        openLogin(JOB51);
    }

    // ------------------------------------------------------------------
    // 平台登录探测（全部在所属会话线程上执行，只读，不做导航）
    // ------------------------------------------------------------------

    private boolean detectBossLogin(Page page) {
        try {
            for (com.microsoft.playwright.options.Cookie cookie : page.context().cookies(BOSS_URL)) {
                if (BOSS_LOGIN_COOKIES.contains(cookie.name) && cookie.value != null && !cookie.value.isBlank()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (isVisibleQuick(page.locator("li.nav-figure span.label-text").first())) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (isVisibleQuick(page.locator("li.nav-figure").first())) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Locator loginAnchor = page.locator("li.nav-sign a, .btns").first();
            if (isVisibleQuick(loginAnchor)) {
                String text = loginAnchor.textContent(new Locator.TextContentOptions().setTimeout(LOCATOR_PROBE_TIMEOUT));
                if (text != null && text.contains("登录")) {
                    return false;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void guideBossLogin(Page page) {
        try {
            String currentUrl = null;
            try {
                currentUrl = page.url();
            } catch (Exception ignored) {
            }
            if (currentUrl == null || !currentUrl.contains("/web/user/")) {
                page.navigate(BOSS_URL + "/web/user/?ka=header-login", new Page.NavigateOptions()
                        .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            Locator qrSwitch = page.locator(".btn-sign-switch.ewm-switch").first();
            if (isVisibleQuick(qrSwitch)) {
                qrSwitch.click();
                return;
            }
            Locator tip = page.getByText("APP扫码登录").first();
            if (isVisibleQuick(tip)) {
                tip.click();
                log.info("已切换到 Boss 二维码登录");
                return;
            }
            Locator legacy = page.locator("li.sign-switch-tip").first();
            if (isVisibleQuick(legacy)) {
                legacy.click();
            }
        } catch (Exception e) {
            log.debug("Boss 登录引导失败: {}", e.getMessage());
        }
    }

    private boolean detectZhilianLogin(Page page) {
        try {
            Locator loginButton = page.locator("a.home-header__c-no-login").first();
            if (loginButton.count() > 0) {
                String text = null;
                try {
                    text = loginButton.textContent();
                } catch (Exception ignored) {
                }
                if (text == null || text.contains("登录")) {
                    return false;
                }
            }
            String url = page.url();
            return url == null || !url.contains("passport.zhaopin.com");
        } catch (Exception e) {
            log.debug("智联登录探测异常: {}", e.getMessage());
            return false;
        }
    }

    private void guideZhilianLogin(Page page) {
        try {
            String currentUrl = null;
            try {
                currentUrl = page.url();
            } catch (Exception ignored) {
            }
            if (currentUrl == null || !currentUrl.contains("passport.zhaopin.com/login")) {
                page.navigate("https://passport.zhaopin.com/login", new Page.NavigateOptions()
                        .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            try {
                page.waitForSelector("div.zppp-panel-normal-bar__img, div.passport-login, #J_loginWrap",
                        new Page.WaitForSelectorOptions().setTimeout(20_000));
            } catch (Exception ignored) {
            }
            Locator qrToggle = page.locator("div.zppp-panel-normal-bar__img").first();
            if (qrToggle.count() > 0 && isVisibleQuick(qrToggle)) {
                qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                log.info("已切换到智联二维码登录，等待扫码");
            }
        } catch (Exception e) {
            log.debug("智联登录引导失败: {}", e.getMessage());
        }
    }

    private boolean detectLiepinLogin(Page page) {
        try {
            Locator loginEntry = page.locator(
                    "#header-quick-menu-login, a[href*='login'], a[data-key='login'], button[data-key='login']").first();
            if (isVisibleQuick(loginEntry)) {
                return false;
            }
            if (page.locator("#header-quick-menu-user-info").count() > 0) {
                return true;
            }
            if (page.locator("img.header-quick-menu-user-photo, .header-quick-menu-user-photo").count() > 0) {
                return true;
            }
            boolean loginEntryExists = page.locator("#header-quick-menu-login, a[href*='login']").count() > 0;
            return !loginEntryExists;
        } catch (Exception e) {
            log.debug("猎聘登录探测异常: {}", e.getMessage());
            return false;
        }
    }

    private void guideLiepinLogin(Page page) {
        try {
            page.navigate("https://www.liepin.com/login", new Page.NavigateOptions()
                    .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
            } catch (Exception ignored) {
            }
            Locator qrSwitch = page.locator(".switch-type-mask-img-box").first();
            if (isVisibleQuick(qrSwitch)) {
                qrSwitch.click();
                return;
            }
            Locator qrImg = page.locator("img[src*='qrcode-btn']").first();
            if (qrImg.count() > 0 && isVisibleQuick(qrImg)) {
                qrImg.click();
            }
        } catch (Exception e) {
            log.debug("猎聘登录引导失败: {}", e.getMessage());
        }
    }

    private boolean detect51jobLogin(Page page) {
        try {
            Locator loginBtn = page.locator("span.login.loginBtnClick").first();
            if (isVisibleQuick(loginBtn)) {
                String text = loginBtn.textContent() == null ? "" : loginBtn.textContent();
                if (text.contains("登录")) {
                    return false;
                }
            }
            Locator userAnchor = page.locator("a.uname.e_icon.at");
            if (userAnchor.count() > 0 && isVisibleQuick(userAnchor.first())) {
                return true;
            }
            Locator myJobLink = page.locator("a[href*='/pc/my/myjob']");
            if (myJobLink.count() > 0 && isVisibleQuick(myJobLink.first())) {
                return true;
            }
            return page.locator(".login-info, .user-info, .username").count() > 0;
        } catch (Exception e) {
            log.debug("51job 登录探测异常: {}", e.getMessage());
            return false;
        }
    }

    private void guide51jobLogin(Page page) {
        try {
            Locator loginEntry = page.locator("span.login.loginBtnClick").first();
            if (isVisibleQuick(loginEntry)) {
                loginEntry.click(new Locator.ClickOptions().setTimeout(10_000));
                log.info("已点击 51job 登录入口，等待扫码");
                return;
            }
            page.navigate("https://login.51job.com/login.php", new Page.NavigateOptions()
                    .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator wechatScan = page.locator(
                    "i.passIcon[data-sensor-id='sensor_login_wechatScan'], " +
                            "[data-sensor-id='sensor_login_wechatScan']").first();
            if (isVisibleQuick(wechatScan)) {
                wechatScan.click(new Locator.ClickOptions().setTimeout(10_000));
            }
        } catch (Exception e) {
            log.debug("51job 登录引导失败: {}", e.getMessage());
        }
    }

    private boolean isVisibleQuick(Locator locator) {
        try {
            return locator.isVisible(new Locator.IsVisibleOptions().setTimeout(LOCATOR_PROBE_TIMEOUT));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    /** 只关闭指定平台的浏览器，其它平台不受影响 */
    public void closeSession(String platform) {
        PlatformBrowserSession session = sessions.get(platform);
        if (session == null) {
            return;
        }
        log.warn("[{}] 正在关闭该平台浏览器（其它平台继续运行）", platform);
        session.close();
        loginStatus.remove(platform);
        loginGuided.put(platform, false);
    }

    @PreDestroy
    public void destroy() {
        log.info("开始关闭所有平台浏览器会话...");
        for (PlatformBrowserSession session : sessions.values()) {
            try {
                session.shutdown();
                log.info("[{}] 浏览器会话已关闭", session.platform());
            } catch (Exception e) {
                log.warn("[{}] 关闭浏览器会话失败: {}", session.platform(), e.getMessage());
            }
        }
        log.info("Playwright 管理器关闭完成");
    }

    /** 平台清单（状态接口用） */
    public List<String> platforms() {
        return new ArrayList<>(sessions.keySet());
    }

    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
