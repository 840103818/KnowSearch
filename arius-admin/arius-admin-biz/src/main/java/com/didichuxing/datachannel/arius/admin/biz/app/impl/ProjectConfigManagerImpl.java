package com.didichuxing.datachannel.arius.admin.biz.app.impl;

import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.ModuleEnum.APP_CONFIG;
import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.OperationEnum.EDIT;

import com.didichuxing.datachannel.arius.admin.biz.app.ProjectConfigManager;
import com.didichuxing.datachannel.arius.admin.common.Tuple;
import com.didichuxing.datachannel.arius.admin.common.bean.common.Result;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.app.ProjectConfigDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.app.ProjectConfig;
import com.didichuxing.datachannel.arius.admin.common.bean.po.app.ESUserPO;
import com.didichuxing.datachannel.arius.admin.common.bean.po.app.ProjectConfigPO;
import com.didichuxing.datachannel.arius.admin.common.util.AriusObjUtils;
import com.didichuxing.datachannel.arius.admin.core.service.app.ProjectConfigService;
import com.didichuxing.datachannel.arius.admin.core.service.common.OperateRecordService;
import org.apache.logging.log4j.core.tools.picocli.CommandLine.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProjectConfigManagerImpl implements ProjectConfigManager {
    @Autowired
    private ProjectConfigService projectConfigService;
    @Autowired
    private OperateRecordService operateRecordService;
    
    /**
     * 获取esUserName配置信息
     *
     * @param projectId@return 配置信息
     */
    @Override
    public ProjectConfig get(int projectId) {
        return projectConfigService.getProjectConfig(projectId);
    }
    
    /**
     * 更新 es user config
     *
     * @param configDTO configdto
     * @param operator  操作人或角色
     * @return {@code Result<Void>}
     */
    @Override
    public Result<Void> updateProjectConfig(ProjectConfigDTO configDTO, String operator) {
        //只有success时候会存在tuple._2不为null
        final Tuple<Result<Void>, ProjectConfigPO> tuple = projectConfigService.updateProjectConfig(configDTO,
                operator);
        if (tuple.getV1().success()) {
            operateRecordService.save(APP_CONFIG, EDIT, configDTO.getProjectId(),
                    AriusObjUtils.findChangedWithClear(tuple.getV2(), configDTO), operator);
        }
        return tuple.getV1();
    }
    
}