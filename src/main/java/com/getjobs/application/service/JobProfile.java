package com.getjobs.application.service;

import java.util.*;
import java.util.regex.Pattern;

public record JobProfile(String name, List<String> platforms, List<String> keywords, String city,
                         Integer minimumMonthlySalary, boolean salaryHard, List<String> excludeCompanies,
                         List<String> excludeJobs, String bossMessage, boolean bossImage, boolean zhilianResumeConfirmed) {
    public record Match(boolean eligible, String reason) {}
    public JobProfile {
        name=required(name,30,"方案名称"); city=required(city,50,"目标城市");
        platforms=values(platforms,2,20,"平台"); if (platforms.isEmpty()) throw new IllegalArgumentException("请至少选择一个平台");
        for (String p:platforms) DailyQuotaService.validatePlatform(p);
        keywords=values(keywords,10,50,"关键词"); if (keywords.isEmpty()) throw new IllegalArgumentException("请填写关键词");
        excludeCompanies=values(excludeCompanies,50,100,"公司排除项"); excludeJobs=values(excludeJobs,50,100,"岗位排除项");
        if (minimumMonthlySalary!=null && (minimumMonthlySalary<0 || minimumMonthlySalary>100000000)) throw new IllegalArgumentException("最低月薪必须是有效的非负整数");
        bossMessage=bossMessage==null?"":bossMessage.strip();
        if (bossMessage.length()>2000) throw new IllegalArgumentException("招呼语不能超过 2000 字");
    }
    private static String required(String value,int max,String label) {
        if (value==null || value.isBlank() || value.strip().length()>max) throw new IllegalArgumentException(label+"不能为空且长度不能超过 "+max);
        return value.strip();
    }
    private static List<String> values(List<String> input,int max,int length,String label) {
        if (input==null) return List.of();
        if (input.size()>1000) throw new IllegalArgumentException(label+"过多");
        var unique=new LinkedHashMap<String,String>();
        for(String s:input) { if (s!=null && s.isBlank()) continue; String text=required(s,length,label); unique.putIfAbsent(normal(text),text); }
        if (unique.size()>max) throw new IllegalArgumentException(label+"最多 "+max+" 项");
        return List.copyOf(unique.values());
    }
    public static String normal(String s) { return s==null?"":s.strip().toLowerCase(Locale.ROOT); }
    public static String cityName(String s) {
        String value=normal(s).split("[·,，\\s]",2)[0];
        return value.endsWith("市")?value.substring(0,value.length()-1):value;
    }
    public Match match(JobCandidate job) {
        if (!platforms.contains(job.platform())) return new Match(false,"平台不在此方案内");
        if (normal(job.jobId()).isEmpty()) return new Match(false,"岗位稳定编号缺失");
        if (normal(job.title()).isEmpty()) return new Match(false,"岗位名称缺失");
        if (keywords.stream().noneMatch(k -> normal(job.title()).contains(normal(k)))) return new Match(false,"岗位关键词不匹配");
        if (!cityName(city).equals(cityName(job.city()))) return new Match(false,"工作城市不匹配或无法确认");
        if (!excludeCompanies.isEmpty() && normal(job.company()).isEmpty()) return new Match(false,"公司信息缺失，无法检查排除项");
        if (excludeCompanies.stream().anyMatch(k -> normal(job.company()).contains(normal(k)))) return new Match(false,"命中公司排除项");
        if (excludeJobs.stream().anyMatch(k -> normal(job.title()).contains(normal(k)))) return new Match(false,"命中岗位排除项");
        if (minimumMonthlySalary!=null) {
            Double lower=monthlyLower(job.salary());
            if (salaryHard && (lower==null || lower<minimumMonthlySalary)) return new Match(false,lower==null?"月固定薪资无法确认":"薪资下限低于要求或区间跨越底线");
            if (!salaryHard && (lower==null || lower<minimumMonthlySalary)) return new Match(true,"硬性条件通过，薪资偏好未满足或无法确认");
        }
        return new Match(true,"已设置的硬性条件通过");
    }
    private static Double monthlyLower(String raw) {
        if (raw==null || raw.matches(".*(面议|年|天|日|时|周).*")) return null;
        String base=raw.split("[·*×]",2)[0].strip();
        var m=Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([kK千万元]?)\\s*[-–—~至]\\s*(\\d+(?:\\.\\d+)?)\\s*([kK千万元]?)(?:元)?\\s*(?:/月|每月)?$").matcher(base);
        if (!m.matches()) return null;
        String unit=m.group(2).isEmpty()?m.group(4):m.group(2);
        if (unit.equals("万") && !raw.contains("月")) return null;
        double scale=unit.equalsIgnoreCase("k")||unit.equals("千")?1000:unit.equals("万")?10000:1;
        double upperScale=m.group(4).equalsIgnoreCase("k")||m.group(4).equals("千")?1000:m.group(4).equals("万")?10000:scale;
        double lower=Double.parseDouble(m.group(1))*scale, upper=Double.parseDouble(m.group(3))*upperScale;
        return lower<=upper?lower:null;
    }
}
