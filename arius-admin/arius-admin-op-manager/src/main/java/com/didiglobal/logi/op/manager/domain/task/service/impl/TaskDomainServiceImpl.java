package com.didiglobal.logi.op.manager.domain.task.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.didiglobal.logi.op.manager.domain.task.entity.Task;
import com.didiglobal.logi.op.manager.domain.task.entity.value.TaskDetail;
import com.didiglobal.logi.op.manager.domain.task.repository.TaskDetailRepository;
import com.didiglobal.logi.op.manager.domain.task.repository.TaskRepository;
import com.didiglobal.logi.op.manager.domain.task.service.TaskDomainService;
import com.didiglobal.logi.op.manager.infrastructure.common.Result;
import com.didiglobal.logi.op.manager.infrastructure.common.ResultCode;
import com.didiglobal.logi.op.manager.infrastructure.common.Tuple;
import com.didiglobal.logi.op.manager.infrastructure.common.bean.GeneralBaseOperationComponent;
import com.didiglobal.logi.op.manager.infrastructure.common.bean.GeneralExecuteComponentFunction;
import com.didiglobal.logi.op.manager.infrastructure.common.bean.GeneralGroupConfig;
import com.didiglobal.logi.op.manager.infrastructure.common.enums.HostActionEnum;
import com.didiglobal.logi.op.manager.infrastructure.common.enums.OperationEnum;
import com.didiglobal.logi.op.manager.infrastructure.common.enums.TaskActionEnum;
import com.didiglobal.logi.op.manager.infrastructure.common.enums.TaskStatusEnum;
import com.didiglobal.logi.op.manager.infrastructure.deployment.DeploymentService;
import com.didiglobal.logi.op.manager.infrastructure.util.ConvertUtil;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.didiglobal.logi.op.manager.infrastructure.common.Constants.REX;

/**
 * @author didi
 * @date 2022-07-13 10:51 上午
 */
@Service
public class TaskDomainServiceImpl implements TaskDomainService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskDetailRepository taskDetailRepository;

    @Autowired
    private DeploymentService deploymentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> createTask(String content, Integer type, String describe,
                                   String associationId, Map<String, List<Tuple<String, Integer>>> groupToHostList) {
        //新建
        Task task = new Task();
        task.create(content, type, describe, associationId, groupToHostList);


        //存储
        int id = taskRepository.insertTask(task);
        task.getDetailList().forEach(taskDetail -> {
            taskDetail.setId(id);
        });

        //存储host任务
        taskDetailRepository.batchInsertTaskDetail(task.getDetailList());
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> executeDeployTask(Task task) {
        Result checkRes = task.checkExecuteTaskStatus();
        if (checkRes.failed()) {
            return checkRes;
        }

        //获取要执行的分组信息
        List<TaskDetail> detailList = taskDetailRepository.listTaskDetailByTaskId(task.getId());
        Result<Map.Entry<String, List<String>>> group2HostListRes = getFirstGroup(detailList);
        if (group2HostListRes.failed()) {
            return Result.fail(group2HostListRes.getMessage());
        }

        //获取模板和分组
        Result<Tuple<GeneralGroupConfig, String>> configAndTemplateIdRes = getConfigByGroupName(task, group2HostListRes.getData().getKey());
        if (configAndTemplateIdRes.failed()) {
            return Result.fail(configAndTemplateIdRes.getMessage());
        }

        //执行zeus任务
        Result<Integer> deployRes = deploymentService.execute(configAndTemplateIdRes.getData().v2(),
                Strings.join(group2HostListRes.getData().getValue(), REX), task.getType().toString(),
                task.getId().toString(), group2HostListRes.getData().getKey());

        if (deployRes.failed()) {
            return Result.fail(deployRes.getMessage());
        }

        //更新任务状态
        taskRepository.updateTaskStatus(task.getId(), TaskStatusEnum.RUNNING.getStatus());
        //回写执行id
        taskDetailRepository.updateTaskDetailExecuteIdByTaskIdAndGroupName(task.getId(), group2HostListRes.getData().getKey(),
                deployRes.getData());
        return Result.success();
    }

    @Override
    public Result<Void> executeFunctionTask(Task task) {
        Result checkRes = task.checkExecuteTaskStatus();
        if (checkRes.failed()) {
            return checkRes;
        }

        //获取要执行的分组信息,只有一个分组（function的执行这里只考虑一个分组）
        List<TaskDetail> detailList = taskDetailRepository.listTaskDetailByTaskId(task.getId());
        List<String> hostList = new ArrayList<>();
        //只要取其中一个就行
        String groupName = detailList.get(0).getGroupName();
        detailList.forEach(detail -> {
            hostList.add(detail.getHost());
        });
        GeneralExecuteComponentFunction function = ConvertUtil.obj2ObjByJSON(task.getContent(), GeneralExecuteComponentFunction.class);

        //执行zeus任务
        Result<Integer> deployRes = deploymentService.execute(function.getTemplateId(),
                Strings.join(hostList, REX), task.getType().toString(),
                task.getId().toString(), function.getParam().toString());

        if (deployRes.failed()) {
            return Result.fail(deployRes.getMessage());
        }

        //更新任务状态
        taskRepository.updateTaskStatus(task.getId(), TaskStatusEnum.RUNNING.getStatus());
        //回写执行id
        taskDetailRepository.updateTaskDetailExecuteIdByTaskIdAndGroupName(task.getId(), groupName,
                deployRes.getData());
        return Result.success();
    }

    @Override
    public Result<Void> actionTask(int taskId, TaskActionEnum action) {
        Task task = taskRepository.getTaskById(taskId);
        if (null == task) {
            return Result.fail(ResultCode.TASK_NOT_EXIST_ERROR);
        }
        Result checkRes = task.checkTaskActionStatus(action);
        if (checkRes.failed()) {
            return checkRes;
        }


        //获取正在执行的任务id
        Integer executeTaskId = getExecuteTaskId(taskId);
        Result<Void> actionRes;
        if (null != executeTaskId) {
            //执行zeus任务
            actionRes = deploymentService.actionTask(executeTaskId, action.getAction());
        } else {
            actionRes = executeDeployTask(task);
        }


        if (actionRes.failed()) {
            return actionRes;
        }

        //更新任务状态
        taskRepository.updateTaskStatus(taskId, action.getStatus());
        return Result.success();
    }

    @Override
    public Result<Void> retryTask(int taskId) {
        Task task = taskRepository.getTaskById(taskId);
        if (null == task) {
            return Result.fail(ResultCode.TASK_NOT_EXIST_ERROR);
        }
        Result checkRes = task.checkRetryActionStatus();

        if (checkRes.failed()) {
            return checkRes;
        }

        taskDetailRepository.deleteByTaskId(taskId);
        //更新任务状态
        taskRepository.updateTaskStatus(taskId, TaskStatusEnum.WAITING.getStatus());
        return Result.success();
    }

    @Override
    public Result<Void> actionHost(int taskId, String host, String groupName, HostActionEnum action) {
        TaskDetail taskDetail = taskDetailRepository.getDetailByHostAndGroupName(taskId, host, groupName);

        if (null == taskDetail) {
            return Result.fail(ResultCode.TASK_HOST_IS_NOT_EXIST);
        }
        Result checkRes = taskDetail.checkHostActionStatus(action);
        if (checkRes.failed()) {
            return checkRes;
        }

        //执行zeus任务
        Result<Void> actionRes = deploymentService.actionHost(taskDetail.getExecuteTaskId(), host, action.getAction());
        if (actionRes.failed()) {
            return actionRes;
        }

        //更新子任务状态
        updateTaskDetail(taskId, taskDetail.getExecuteTaskId(), action.getStatus(), Collections.singletonList(host));

        //更新主任务状态，这里因为zeus执行中如果一个节点失败了，然后重试或其他操作，这时整个任务是暂停的，需要再次重启
        taskRepository.updateTaskStatusAndIsFinish(taskId, TaskStatusEnum.PAUSE.getStatus(), 0);
        return Result.success();
    }


    private Integer getExecuteTaskId(int taskId) {
        List<TaskDetail> detailList = taskDetailRepository.listTaskDetailByTaskId(taskId);
        Optional<TaskDetail> optional = detailList.stream().filter(detail ->
                null != detail.getExecuteTaskId() &&
                        (detail.getStatus() == TaskStatusEnum.RUNNING.getStatus() ||
                                detail.getStatus() == TaskStatusEnum.WAITING.getStatus())
        ).findFirst();

        if (optional.isPresent()) {
            return optional.get().getExecuteTaskId();
        }

        return null;
    }

    @NotNull
    private Result<Map.Entry<String, List<String>>> getFirstGroup(List<TaskDetail> detailList) {
        Map<String, List<String>> groupToHostList = new LinkedHashMap<>();
        detailList.forEach(taskDetail -> {
            if (null == taskDetail.getExecuteTaskId()) {
                List<String> hosts = groupToHostList.get(taskDetail.getGroupName());
                if (null == hosts) {
                    hosts = new ArrayList<>();
                    groupToHostList.put(taskDetail.getGroupName(), hosts);
                }
                hosts.add(taskDetail.getHost());
            }
        });
        if (0 == groupToHostList.size()) {
            Result.fail("没有可以执行的分组");
        }
        return Result.success(groupToHostList.entrySet().toArray(new Map.Entry[]{})[0]);
    }

    @Override
    public Result<Task> getTaskById(int taskId) {
        Task task = taskRepository.getTaskById(taskId);
        task.setDetailList(taskDetailRepository.listTaskDetailByTaskId(taskId));
        return Result.success(task);
    }

    @Override
    public Result<GeneralGroupConfig> getConfig(Task task, String groupName) {
        Result<Tuple<GeneralGroupConfig, String>> configRes = getConfigByGroupName(task, groupName);

        if (configRes.failed()) {
            return Result.fail(configRes.getMessage());
        }

        return Result.success(configRes.getData().v1());
    }

    @Override
    public Result<List<Task>> getUnFinishTaskList() {
        List<Task> taskList = taskRepository.getUnFinishTaskList();
        taskList.forEach(task -> {
            List<TaskDetail> detailList = taskDetailRepository.listTaskDetailByTaskId(task.getId());
            task.setDetailList(detailList);
        });
        return Result.success(taskList);
    }

    @Override
    public Result<Void> updateTaskDetail(int taskId, int executeId, int status, List<String> hosts) {
        taskDetailRepository.updateStatusByExecuteTaskId(taskId, executeId, status, hosts);
        return Result.success();
    }

    @Override
    public Result<Void> updateTaskStatusAndIsFinish(int taskId, int isFinish, int status) {
        taskRepository.updateTaskStatusAndIsFinish(taskId, status, isFinish);
        return Result.success();
    }

    @Override
    public Result<Void> hasRepeatTask(String name, Integer componentId, int type) {
        List<Task> taskList = taskRepository.getUnFinalStatusTaskList();
        for (Task task : taskList) {
            JSONObject content = JSON.parseObject(task.getContent());
            if (null != content.get("name") && content.get("name").equals(name) && type == task.getType()) {
                return Result.fail(ResultCode.TASK_REPEAT_ERROR.getCode(), String.format("有重名未完成任务[%s]", task.getId()));
            }
            if (null != content.get("componentId") && content.get("componentId") == componentId && type == task.getType()) {
                return Result.fail(ResultCode.TASK_REPEAT_ERROR.getCode(), String.format("有重复未完成任务[%s]，任务类型[%s]", task.getId(), task.getType()));
            }
        }
        return Result.success();
    }

    private Result<Tuple<GeneralGroupConfig, String>> getConfigByGroupName(Task task, String name) {

        switch (OperationEnum.valueOfType(task.getType())) {
            case INSTALL:
            case EXPAND:
            case SHRINK:
            case CONFIG_CHANGE:
            case RESTART:
            case UPGRADE:
                GeneralBaseOperationComponent baseOperationComponent = ConvertUtil.str2ObjByJson(task.getContent(), GeneralBaseOperationComponent.class);
                for (GeneralGroupConfig config : baseOperationComponent.getGroupConfigList()) {
                    if (config.getGroupName().equals(name)) {
                        return Result.success(new Tuple<>(config, baseOperationComponent.getTemplateId()));
                    }
                }
            case UN_KNOW:
            default:
                return Result.fail("分组名未匹配到相应配置");

        }
    }
}
