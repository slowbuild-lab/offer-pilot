package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.getjobs.application.service.SqliteJournal.*;

/** Read-only projections. Filters and totals use the same SQLite read snapshot. */
@Service
public class DeliveryReadService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE=20;
    private static final Set<String> STATES=Set.of("RESERVED","SENT","CONFIRMED","UNKNOWN","RELEASED","NOT_DELIVERED","USER_CONFIRMED");
    private final DataSource source;
    private final ObjectMapper json=new ObjectMapper();
    public DeliveryReadService(DataSource source) { this.source=source; }

    public Map<String,Object> records(String platform,int page,String from,String to,String state) {
        DailyQuotaService.validatePlatform(platform);
        if(page<1 || page>10000) throw new IllegalArgumentException("页码必须为 1 到 10000");
        if(state!=null && !state.isEmpty() && (!STATES.contains(state) || (platform.equals("zhilian") && state.equals("USER_CONFIRMED"))))
            throw new IllegalArgumentException("该平台不支持此结果状态");
        LocalDate start=date(from),end=date(to);
        if(start!=null && end!=null && start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        String table=platform.equals("boss")?"boss":"zhilian";
        var args=new ArrayList<Object>();var where=new StringBuilder(" WHERE 1=1");
        if(start!=null) { where.append(" AND a.initiated_at>=?");args.add(start.atStartOfDay(ZONE).toInstant().toEpochMilli()); }
        if(end!=null) { where.append(" AND a.initiated_at<?");args.add(end.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()); }
        if(state!=null && !state.isEmpty()) { where.append(" AND a.state=?");args.add(state); }
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            int total=count(c,"SELECT count(*) FROM "+table+"_attempts a"+where,args.toArray());
            String column=platform.equals("boss")?"t.profile_version":"t.config_json";
            var paged=new ArrayList<>(args);paged.add(PAGE_SIZE);paged.add((page-1)*PAGE_SIZE);
            var items=rows(c,"SELECT a.*,"+column+" FROM "+table+"_attempts a JOIN "+table+"_tasks t ON t.id=a.task_id"+where+" ORDER BY a.initiated_at DESC,a.rowid DESC LIMIT ? OFFSET ?",paged.toArray());
            for(var item:items) {
                if(platform.equals("boss")) item.put("actions",rows(c,"SELECT action,state FROM boss_actions WHERE attempt_id=? ORDER BY started_at,action",item.get("id")));
                else {
                    Object raw=item.remove("config_json");Long version=null;
                    if(raw!=null) {
                        var value=json.readTree(raw.toString()).path("confirmedProfile").path("version");
                        if(value.isIntegralNumber()) version=value.longValue();
                    }
                    item.put("profile_version",version);
                }
            }
            c.commit();
            return Map.of("items",items,"page",page,"pageSize",PAGE_SIZE,"total",total,"hasNext",(long)page*PAGE_SIZE<total);
        } catch(Exception e) { throw new IllegalStateException("记录读取失败，请检查本机服务与数据库；未修改投递记录",e); }
    }

    public List<Map<String,Object>> recentTasks() {
        try(Connection c=source.getConnection()) {
            return rows(c,"SELECT 'boss' AS platform,id,state,reason,started_at AS startedAt FROM boss_tasks UNION ALL SELECT 'zhilian' AS platform,id,state,reason,started_at AS startedAt FROM zhilian_tasks ORDER BY startedAt DESC,platform,id LIMIT 8");
        } catch(Exception e) { throw new IllegalStateException("最近任务读取失败",e); }
    }
    private static LocalDate date(String value) {
        if(value==null || value.isEmpty()) return null;
        try {
            if(!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format",value,0);
            LocalDate date=LocalDate.parse(value);
            if(date.getYear()<1970 || date.getYear()>9998) throw new IllegalArgumentException("日期年份必须为 1970 到 9998");
            return date;
        } catch(DateTimeParseException e) { throw new IllegalArgumentException("日期必须是有效的 YYYY-MM-DD"); }
    }
}
