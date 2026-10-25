package com.getjobs.application.service;

import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** Maps a confirmed single-city profile onto fresh in-memory platform configuration; never overwrites old forms. */
@Service
@RequiredArgsConstructor
public class JobProfileRuntime {
    private final BossService boss;
    private final ZhilianService zhilian;
    private final JobProfileService profiles;
    private final BossResumeImageService images;
    public String cityCode(String platform,String city) {
        String code;
        if ("boss".equals(platform)) code=boss.getOptionsByType("city").stream()
                .filter(o -> JobProfile.cityName(city).equals(JobProfile.cityName(o.getName())))
                .map(o -> o.getCode()).findFirst().orElse(null);
        else code=zhilian.getOptionsByType("city").stream()
                .filter(o -> JobProfile.cityName(city).equals(JobProfile.cityName(o.getName())))
                .map(o -> o.getCode()).findFirst().orElse(null);
        if (code==null || code.isBlank() || code.equals("0")) throw new IllegalStateException("无法映射 "+platform+" 的目标城市，请检查平台城市字典");
        return code;
    }
    public void apply(BossConfig config,JobProfileService.Snapshot snapshot) {
        var p=snapshot.rules();
        config.setKeywords(p.keywords()); config.setCityCode(List.of(cityCode("boss",p.city())));
        config.setSayHi(p.bossMessage()); config.setSendImgResume(p.bossImage()); config.setAllowRepeatChat(false);
        config.setEnableAI(false); config.setDebugger(false); config.setConfirmedProfile(snapshot);
        // Legacy search filters could silently narrow a new profile; use the explicit local rules instead.
        config.setSalary(List.of()); config.setExperience(List.of()); config.setDegree(List.of());
        config.setIndustry(List.of()); config.setScale(List.of()); config.setStage(List.of()); config.setJobType(null);
    }
    public void apply(ZhilianConfig config,JobProfileService.Snapshot snapshot) {
        config.setKeywords(snapshot.rules().keywords()); config.setCityCode(cityCode("zhilian",snapshot.rules().city()));
        config.setSalary(null); config.setConfirmedProfile(snapshot);
    }
    public String materialToken(JobProfile rules) {
        if (!rules.platforms().contains("boss") || !rules.bossImage()) return "";
        var metadata=images.getMetadata();
        if (!Boolean.TRUE.equals(metadata.get("exists"))) throw new IllegalStateException("请先在 BOSS 页面上传图片简历，再重新预览");
        return metadata.get("fileName")+":"+metadata.get("size")+":"+metadata.get("updatedAt");
    }
    public void guard(JobProfileService.Snapshot snapshot,JobCandidate job) {
        if (snapshot!=null) {
            profiles.assertAllowed(snapshot,job);
            if ("boss".equals(job.platform()) && !snapshot.materialToken().equals(materialToken(snapshot.rules()))) throw new IllegalStateException("图片简历已变化，请重新预览确认方案");
        } else if (profiles.forbidden(job)) throw new IllegalStateException("命中紧急禁止，已停止外发");
    }
}
