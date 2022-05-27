package com.didichuxing.datachannel.arius.admin.biz.worktask.handler;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.fastjson.JSON;
import com.didichuxing.datachannel.arius.admin.biz.workorder.WorkOrderManager;
import com.didichuxing.datachannel.arius.admin.biz.worktask.OpTaskHandler;
import com.didichuxing.datachannel.arius.admin.biz.worktask.OpTaskManager;
import com.didichuxing.datachannel.arius.admin.biz.worktask.ecm.EcmTaskManager;
import com.didichuxing.datachannel.arius.admin.common.bean.common.Result;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.task.ecm.EcmTaskDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.task.OpTask;
import com.didichuxing.datachannel.arius.admin.common.bean.po.task.ecm.EcmTaskPO;
import com.didichuxing.datachannel.arius.admin.common.constant.task.OpTaskStatusEnum;
import com.didichuxing.datachannel.arius.admin.common.util.AriusObjUtils;
import com.didichuxing.datachannel.arius.admin.common.util.ConvertUtil;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.ecm.ESClusterConfigService;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.ecm.ESPluginService;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.physic.ClusterPhyService;
import com.didichuxing.datachannel.arius.admin.core.service.es.ESClusterService;
import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;

public abstract class AbstractOpTaskHandler implements OpTaskHandler {

    protected final ILog             LOGGER                   = LogFactory.getLog(this.getClass());

    @Autowired
    protected EcmTaskManager ecmTaskManager;

    @Autowired
    protected OpTaskManager opTaskManager;

    @Autowired
    protected ESClusterService esClusterService;

    @Autowired
    protected ESClusterConfigService esClusterConfigService;

    @Autowired
    protected WorkOrderManager workOrderManager;

    @Autowired
    protected ESPluginService esPluginService;

    @Autowired
    protected ClusterPhyService clusterPhyService;

    @Override
    public Result<OpTask> addTask(OpTask opTask) {
        if (AriusObjUtils.isNull(opTask.getExpandData())) {
            return Result.buildParamIllegal("提交内容为空");
        }

        EcmTaskDTO ecmTaskDTO = ConvertUtil.str2ObjByJson(opTask.getExpandData(), EcmTaskDTO.class);
        Result<Long> ret = ecmTaskManager.saveEcmTask(ecmTaskDTO);
        if (null == ret || ret.failed()) {
            return Result.buildFail("生成集群新建操作任务失败!");
        }

        opTask.setBusinessKey(String.valueOf(ret.getData()));
        opTask.setTitle(ecmTaskDTO.getTitle());
        opTask.setCreateTime(new Date());
        opTask.setUpdateTime(new Date());
        opTask.setStatus(OpTaskStatusEnum.WAITING.getStatus());
        opTask.setDeleteFlag(false);
        opTaskManager.insert(opTask);

        return Result.buildSucc(opTask);
    }

    @Override
    public boolean existUnClosedTask(String key, Integer type) {
        return ecmTaskManager.existUnClosedEcmTask(Long.valueOf(key));
    }

    @Override
    public Result<Void> process(OpTask opTask, Integer step, String status, String expandData) {
        if (AriusObjUtils.isNull(opTask.getExpandData())) {
            return Result.buildParamIllegal("提交内容为空");
        }

        EcmTaskPO ecmTaskPO = JSON.parseObject(opTask.getExpandData(), EcmTaskPO.class);

        opTask.setStatus(status);
        opTask.setUpdateTime(new Date());
        opTask.setExpandData(JSON.toJSONString(ecmTaskPO));
        opTaskManager.updateTask(opTask);

        return Result.buildSucc();
    }
}
