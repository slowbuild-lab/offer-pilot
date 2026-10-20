package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.io.IOException;
import java.time.LocalDateTime;

@Data
@TableName("zhilian_config")
public class ZhilianConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市（中文名或代码，单值） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值） */
    private String salary;

    /** 投递成功弹窗出现后等待的秒数；空值表示更新时保留原配置。 */
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer popupDelaySeconds;

    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer maxAttemptsPerTask;

    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer maxAttemptsPerDay;

    /** 防止 Jackson 把 1.5 等非整数静默截断后保存。 */
    public static class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
