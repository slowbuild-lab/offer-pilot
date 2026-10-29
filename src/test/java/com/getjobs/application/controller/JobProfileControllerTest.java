package com.getjobs.application.controller;

import com.getjobs.application.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobProfileControllerTest {
    @Test void acceptsExplicitBossOnlyFiltersButRejectsCoercionUnknownFieldsAndWrongScopes() throws Exception {
        var profiles=mock(JobProfileService.class); when(profiles.save(any())).thenReturn(9L);
        var controller=new JobProfileController(profiles,mock(JobProfileRuntime.class),mock(JobPreviewCollector.class),mock(BossService.class),mock(ZhilianService.class));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var base=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new JobProfile("开发",java.util.List.of("boss"),java.util.List.of("Java"),"北京",null,true,java.util.List.of(),java.util.List.of(),"您好",false,false));
        base.set("bossFilters",json.readTree("{\"includeCompanies\":[\"科技\"],\"descriptionExclude\":[\"外包\"]}"));
        mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(9));
        for(String filters:new String[]{"null","[]","{\"descriptionInclude\":[1]}","{\"unknown\":[]}","{\"descriptionExclude\":\"外包\"}"}) {
            base.set("bossFilters",json.readTree(filters));
            mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isBadRequest());
        }
        base.set("bossFilters",json.readTree("{\"descriptionExclude\":[\"外包\"]}")); base.set("platforms",json.readTree("[\"zhilian\"]"));
        mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isBadRequest());
        verify(profiles,times(1)).save(any());
    }
    @Test void acceptsExplicitCooldownAndRejectsWrongShapeRangeOrScope() throws Exception {
        var profiles=mock(JobProfileService.class); when(profiles.save(any())).thenReturn(11L);
        var controller=new JobProfileController(profiles,mock(JobProfileRuntime.class),mock(JobPreviewCollector.class),mock(BossService.class),mock(ZhilianService.class));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var base=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new JobProfile("开发",java.util.List.of("boss"),java.util.List.of("Java"),"北京",null,true,java.util.List.of(),java.util.List.of(),"您好",false,false));
        base.set("contactPolicy",json.readTree("{\"hrCooldownDays\":30}"));
        mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(11));
        for(String policy:new String[]{"null","[]","{\"hrCooldownDays\":\"30\"}","{\"hrCooldownDays\":0}","{\"hrCooldownDays\":366}","{\"hrCooldownDays\":1.5}","{\"unknown\":1}"}) {
            base.set("contactPolicy",json.readTree(policy));
            mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isBadRequest());
        }
        base.set("contactPolicy",json.readTree("{\"hrCooldownDays\":30}")); base.set("platforms",json.readTree("[\"zhilian\"]"));
        mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(base.toString())).andExpect(status().isBadRequest());
        verify(profiles,times(1)).save(any());
    }
    @Test void rejectsNullObjectsCoercedFieldsAndUnimplementedHardConditions() throws Exception {
        var profiles=mock(JobProfileService.class);
        var controller=new JobProfileController(profiles,mock(JobProfileRuntime.class),mock(JobPreviewCollector.class),mock(BossService.class),mock(ZhilianService.class));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        for(String input:new String[]{"null","[]","{\"salaryHard\":\"true\"}","{\"minimumMonthlySalary\":1.5}","{\"experience\":3}","{\"keywords\":[1]}"})
            mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(input)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/job-profile").header("Origin","https://foreign.invalid")).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
}
