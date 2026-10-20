package com.getjobs.worker.zhilian;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class ZhilianConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /** 投递成功弹窗出现后等待的秒数。 */
    private Integer popupDelaySeconds;

    public Integer getPopupDelaySeconds() {
        return popupDelaySeconds == null ? 5 : popupDelaySeconds;
    }


    private Integer maxAttemptsPerTask;
    private Integer maxAttemptsPerDay;

    public Integer getMaxAttemptsPerTask() { return maxAttemptsPerTask == null ? 20 : maxAttemptsPerTask; }
    public Integer getMaxAttemptsPerDay() { return maxAttemptsPerDay == null ? 50 : maxAttemptsPerDay; }

    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

}
