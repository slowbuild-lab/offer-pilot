package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BossProfileRulesTest {
    private final ObjectMapper json=new ObjectMapper();
    private JobProfile rules(String filters) throws Exception {
        var body=json.valueToTree(new JobProfile("开发岗位",List.of("boss","zhilian"),List.of("Java"),"北京",null,true,List.of("排除公司"),List.of(),"您好",false,true));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).set("bossFilters",json.readTree(filters));
        return json.treeToValue(body,JobProfile.class);
    }
    private JobCandidate job(String platform,String company,String description) throws Exception {
        var body=json.valueToTree(new JobCandidate(platform,"one","Java开发",company,"北京","15-20K","https://www.zhipin.com/job_detail/one.html"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("description",description);
        return json.treeToValue(body,JobCandidate.class);
    }
    @Test void whitelistRequiresActualCompanyAndNeverOverridesExclusions() throws Exception {
        var rules=rules("{\"includeCompanies\":[\"科技\",\"OpenAI\"]}");
        assertTrue(rules.match(job("boss","OPENAI 中国","开发")).eligible());
        assertFalse(rules.match(job("boss","其他公司","开发")).eligible());
        assertFalse(rules.match(job("boss","排除公司科技","开发")).eligible());
        assertFalse(rules.match(job("boss","","开发")).eligible());
    }
    @Test void descriptionIncludesAreAnyAndExclusionsWin() throws Exception {
        var rules=rules("{\"descriptionInclude\":[\"大模型\",\"智能体\"],\"descriptionExclude\":[\"销售\"]}");
        assertTrue(rules.match(job("boss","科技","负责智能体开发")).eligible());
        var result=rules.match(job("boss","科技","负责智能体开发，同时承担销售指标"));
        assertFalse(result.eligible());
        assertTrue(result.reason().contains("销售"));
        var checks=json.valueToTree(result).path("checks");
        assertTrue(checks.isArray());
        assertTrue(checks.toString().contains("承担销售指标"));
    }
    @Test void missingDescriptionDoesNotPassEitherKindOfHardDescriptionRule() throws Exception {
        for(String filters:List.of("{\"descriptionInclude\":[\"大模型\"]}","{\"descriptionExclude\":[\"外包\"]}")) {
            var result=rules(filters).match(job("boss","科技",null));
            assertFalse(result.eligible()); assertTrue(result.reason().contains("工作内容缺失"));
        }
    }
    @Test void explicitNegationsAndSoftwareContextDoNotPretendToBeSalesOrOutsourcing() throws Exception {
        var rules=rules("{\"descriptionExclude\":[\"外包\",\"销售\"]}");
        assertTrue(rules.match(job("boss","科技","不是外包岗位，负责销售系统开发，无需销售")).eligible());
        assertFalse(rules.match(job("boss","科技","不是外包，但需要负责销售")).eligible());
        assertFalse(rules.match(job("boss","科技","负责销售服务")).eligible());
    }
    @Test void keywordsAreLiteralAndEveryOccurrenceIsChecked() throws Exception {
        var rules=rules("{\"descriptionExclude\":[\"[外包]\",\"销售\"]}");
        assertFalse(rules.match(job("boss","科技","本岗位为[外包]开发")).eligible());
        assertTrue(rules.match(job("boss","科技","外包两个字没有方括号")).eligible());
        assertFalse(rules.match(job("boss","科技","无需销售，另有销售职责")).eligible());
    }
    @Test void negatingExperienceDoesNotNegateTheRoleAndUnicodeDoesNotCorruptEvidenceOffsets() throws Exception {
        var rules=rules("{\"descriptionExclude\":[\"销售\"]}");
        assertFalse(rules.match(job("boss","科技","无需销售经验，负责客户拓展")).eligible());
        var result=rules.match(job("boss","科技","İ".repeat(200)+"销售职责"));
        assertFalse(result.eligible());
        assertTrue(json.valueToTree(result).path("checks").toString().contains("销售职责"));
    }
    @Test void bossScopedRulesNeverSilentlyApplyToZhilian() throws Exception {
        var result=rules("{\"descriptionInclude\":[\"大模型\"]}").match(job("zhilian","公司",null));
        assertTrue(result.eligible());
        assertThrows(Exception.class,()->json.readValue("{\"name\":\"方案\",\"platforms\":[\"zhilian\"],\"keywords\":[\"Java\"],\"city\":\"北京\",\"bossFilters\":{\"descriptionExclude\":[\"外包\"]}}",JobProfile.class));
    }
    @Test void legacyVersionsRemainReadableWithoutNewFields() throws Exception {
        var legacy=new JobProfile("旧方案",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false);
        assertTrue(legacy.match(new JobCandidate("boss","1","Java开发","公司","北京","面议","")).eligible());
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(legacy); body.remove("bossFilters");
        assertTrue(json.treeToValue(body,JobProfile.class).match(job("boss","公司",null)).eligible());
    }
}
