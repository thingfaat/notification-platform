package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.MessageTemplateDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageTemplateMapper extends BaseMapper<MessageTemplateDO> {
}
