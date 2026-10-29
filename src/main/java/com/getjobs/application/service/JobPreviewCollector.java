package com.getjobs.application.service;

import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.manager.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Calls the existing parsers with an explicit collect-only branch before any external action. */
@Service
@RequiredArgsConstructor
public class JobPreviewCollector {
    private final PlaywrightManager browsers;
    private final PlatformTaskGate gate;
    private final ConfigService configs;
    private final ObjectProvider<Boss> bossProvider;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final JobProfileRuntime runtime;
    private final ZhilianDeliveryLedger ledger;
    private final DeliveryPacingService pacing;
    public List<JobCandidate> collect(JobProfileService.Snapshot snapshot) {
        var result=new ArrayList<JobCandidate>();
        for(String platform:snapshot.rules().platforms()) {
            var ticket=gate.acquire(platform);
            var collected=new ArrayList<JobCandidate>();
            long deadline=System.currentTimeMillis()+300000;
            try {
                browsers.ensureReady(platform);
                if (!browsers.isLoggedIn(platform)) throw new IllegalStateException("请先在 "+platform+" 页面打开浏览器并登录");
                browsers.pauseMonitoring(platform);
                browsers.callOn(platform,page -> {
                    try(var session=new com.getjobs.worker.utils.DeliveryPacingSession(pacing,platform,()->collected.size()>=10||System.currentTimeMillis()>deadline,m->{})){
                    session.attach(page);
                    if ("boss".equals(platform)) {
                        var config=configs.getBossConfig(); runtime.apply(config,snapshot); config.setDebugger(true);
                        var worker=bossProvider.getObject(); worker.setConfig(config); worker.setPage(page);
                        worker.setPacing(session);
                        worker.setPreviewCallback(collected::add);
                        worker.setShouldStopCallback(() -> collected.size()>=10 || System.currentTimeMillis()>deadline);
                        worker.setProgressCallback((m,c,t) -> {}); worker.prepare(); worker.execute();
                    } else {
                        var config=configs.getZhilianConfig(); runtime.apply(config,snapshot);
                        var worker=zhilianProvider.getObject(); worker.setConfig(config); worker.setPage(page); worker.setLedger(ledger);
                        worker.setPacing(session);
                        worker.setPreviewCallback(collected::add);
                        worker.setShouldStopCallback(() -> collected.size()>=10 || System.currentTimeMillis()>deadline);
                        worker.setProgressCallback((m,c,t) -> {}); worker.prepare(); worker.execute();
                        if (worker.getOutcome()!=ZhiLian.Outcome.COMPLETED) throw new IllegalStateException(worker.getOutcomeReason());
                    }
                    return null;
                    }
                });
                if (System.currentTimeMillis()>deadline) throw new IllegalStateException("预览采集超时，请检查该平台页面");
                result.addAll(collected);
            } finally {
                try { browsers.resumeMonitoring(platform); } finally { gate.release(ticket); }
            }
        }
        return List.copyOf(result);
    }
}
