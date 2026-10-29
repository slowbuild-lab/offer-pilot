package com.getjobs.application.service;

import java.util.*;
import java.util.regex.Pattern;

public record JobProfile(String name, List<String> platforms, List<String> keywords, String city,
                         Integer minimumMonthlySalary, boolean salaryHard, List<String> excludeCompanies,
                         List<String> excludeJobs, String bossMessage, boolean bossImage, boolean zhilianResumeConfirmed,
                         BossFilters bossFilters, ContactPolicy contactPolicy) {
    public record RuleCheck(String code,String label,boolean passed,String term,String evidence,String reason) {}
    public record Match(boolean eligible, String reason, List<RuleCheck> checks) {
        public Match { checks=checks==null?List.of():List.copyOf(checks); }
        public Match(boolean eligible,String reason) { this(eligible,reason,List.of()); }
        public Match reject(String code,String label,String reason,String evidence) {
            var all=new ArrayList<>(checks); all.add(new RuleCheck(code,label,false,"",evidence,reason));
            return new Match(false,reason,all);
        }
        /** Appends a check that may be decided outside the pure rules (for example a database lookup). */
        public Match with(RuleCheck check) {
            var all=new ArrayList<>(checks); all.add(check);
            boolean eligible=this.eligible && check.passed();
            return new Match(eligible,eligible?reason:check.reason(),all);
        }
    }
    public JobProfile(String name,List<String> platforms,List<String> keywords,String city,Integer minimumMonthlySalary,
                      boolean salaryHard,List<String> excludeCompanies,List<String> excludeJobs,String bossMessage,
                      boolean bossImage,boolean zhilianResumeConfirmed) {
        this(name,platforms,keywords,city,minimumMonthlySalary,salaryHard,excludeCompanies,excludeJobs,bossMessage,bossImage,zhilianResumeConfirmed,null,null);
    }
    public JobProfile(String name,List<String> platforms,List<String> keywords,String city,Integer minimumMonthlySalary,
                      boolean salaryHard,List<String> excludeCompanies,List<String> excludeJobs,String bossMessage,
                      boolean bossImage,boolean zhilianResumeConfirmed,BossFilters bossFilters) {
        this(name,platforms,keywords,city,minimumMonthlySalary,salaryHard,excludeCompanies,excludeJobs,bossMessage,bossImage,zhilianResumeConfirmed,bossFilters,null);
    }
    /** Optional BOSS-only HR cooldown. Absent (null days) means disabled, so old profiles keep their exact behaviour. */
    public record ContactPolicy(Integer hrCooldownDays) {
        public static final int MAX_DAYS=365;
        public ContactPolicy {
            if (hrCooldownDays!=null && (hrCooldownDays<1 || hrCooldownDays>MAX_DAYS))
                throw new IllegalArgumentException("BOSS HR 冷却期必须为 1 到 "+MAX_DAYS+" 天");
        }
        public boolean enabled() { return hrCooldownDays!=null; }
    }
    public record BossFilters(List<String> includeCompanies,List<String> descriptionInclude,List<String> descriptionExclude) {
        private static final Pattern NEGATION=Pattern.compile("(?:不是|并非|不属于|不涉及|无需|不需要|无须|没有|非)\\s*$");
        public BossFilters {
            includeCompanies=values(includeCompanies,20,100,"BOSS公司白名单");
            descriptionInclude=values(descriptionInclude,20,100,"BOSS工作内容包含词");
            descriptionExclude=values(descriptionExclude,20,100,"BOSS工作内容排除词");
        }
        public boolean configured() { return !includeCompanies.isEmpty() || !descriptionInclude.isEmpty() || !descriptionExclude.isEmpty(); }
        public List<RuleCheck> evaluate(JobCandidate job) {
            var checks=new ArrayList<RuleCheck>();
            if (!includeCompanies.isEmpty()) {
                String hit=includeCompanies.stream().filter(t -> normal(job.company()).contains(normal(t))).findFirst().orElse("");
                checks.add(new RuleCheck("BOSS_COMPANY_INCLUDE","公司白名单",!hit.isEmpty(),hit,
                        job.company()==null?"":job.company(),!hit.isEmpty()?"公司命中白名单："+hit:"公司名称缺失或未命中任一白名单词"));
            }
            if (descriptionInclude.isEmpty() && descriptionExclude.isEmpty()) return checks;
            String description=job.description();
            if (description==null || description.isBlank() || description.length()>100000) {
                checks.add(new RuleCheck("BOSS_DESCRIPTION_AVAILABLE","工作内容完整性",false,"","",
                        description!=null && description.length()>100000?"岗位工作内容过长，无法完整核验":"岗位工作内容缺失，无法核验BOSS高级筛选"));
                return checks;
            }
            if (!descriptionInclude.isEmpty()) {
                var hit=hit(description,descriptionInclude);
                checks.add(new RuleCheck("BOSS_DESCRIPTION_INCLUDE","工作内容至少包含一项",hit!=null,hit==null?"":hit.term(),
                        excerpt(description,hit==null?0:hit.index()),hit==null?"工作内容未命中任一包含词":"工作内容命中包含词："+hit.term()));
            }
            if (!descriptionExclude.isEmpty()) {
                var hit=hit(description,descriptionExclude);
                checks.add(new RuleCheck("BOSS_DESCRIPTION_EXCLUDE","工作内容排除",hit==null,hit==null?"":hit.term(),
                        excerpt(description,hit==null?0:hit.index()),hit==null?"未命中有效排除词；明确否定及销售系统/软件/工具语境不作命中":"工作内容命中排除词："+hit.term()));
            }
            return checks;
        }
        private record Hit(String term,int index) {}
        private static Hit hit(String description,List<String> terms) {
            for(String term:terms) {
                String needle=normal(term);
                var occurrences=Pattern.compile(Pattern.quote(term),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(description);
                while(occurrences.find()) {
                    int at=occurrences.start();
                    String before=description.substring(Math.max(0,at-12),at);
                    String after=description.substring(occurrences.end());
                    boolean qualification=List.of("经验","背景","技能","资格").stream().anyMatch(after::startsWith);
                    boolean negated=!qualification && NEGATION.matcher(before).find();
                    boolean software=needle.equals("销售") && (after.startsWith("系统") || after.startsWith("软件") || after.startsWith("工具"));
                    if (!negated && !software) return new Hit(term,at);
                }
            }
            return null;
        }
    }
    private static String excerpt(String text,int at) {
        int start=Math.max(0,at-40),end=Math.min(text.length(),at+120);
        return (start>0?"…":"")+text.substring(start,end)+(end<text.length()?"…":"");
    }
    public JobProfile {
        name=required(name,30,"方案名称"); city=required(city,50,"目标城市");
        platforms=values(platforms,2,20,"平台"); if (platforms.isEmpty()) throw new IllegalArgumentException("请至少选择一个平台");
        for (String p:platforms) DailyQuotaService.validatePlatform(p);
        bossFilters=bossFilters==null?new BossFilters(null,null,null):bossFilters;
        if (bossFilters.configured() && !platforms.contains("boss")) throw new IllegalArgumentException("BOSS高级条件需要选择BOSS平台");
        contactPolicy=contactPolicy==null?new ContactPolicy(null):contactPolicy;
        if (contactPolicy.enabled() && !platforms.contains("boss")) throw new IllegalArgumentException("BOSS HR 冷却期需要选择BOSS平台");
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
        var basic=basicMatch(job);
        var checks=new ArrayList<RuleCheck>();
        checks.add(new RuleCheck("BASE","基础条件",basic.eligible(),"",String.join(" · ",
                Objects.toString(job.title(),""),Objects.toString(job.company(),""),Objects.toString(job.city(),""),Objects.toString(job.salary(),"")),basic.reason()));
        if (basic.eligible() && "boss".equals(job.platform())) checks.addAll(bossFilters.evaluate(job));
        if (job.platformFilterReason()!=null && !job.platformFilterReason().isBlank())
            checks.add(new RuleCheck("PLATFORM_FILTER","原平台配置",false,"",job.platformFilterReason(),"原平台配置："+job.platformFilterReason()));
        var failed=checks.stream().filter(c -> !c.passed()).findFirst();
        return new Match(failed.isEmpty(),failed.map(RuleCheck::reason).orElse(basic.reason()),checks);
    }
    private Match basicMatch(JobCandidate job) {
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
