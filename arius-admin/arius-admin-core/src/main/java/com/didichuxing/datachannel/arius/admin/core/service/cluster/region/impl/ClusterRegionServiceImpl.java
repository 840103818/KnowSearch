package com.didichuxing.datachannel.arius.admin.core.service.cluster.region.impl;

import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.ModuleEnum.CLUSTER_REGION;
import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.ModuleEnum.REGION;
import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.OperationEnum.DELETE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.didichuxing.datachannel.arius.admin.common.bean.common.Result;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.cluster.ClusterLogic;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.cluster.ClusterPhy;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.cluster.ecm.ClusterRoleHost;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.region.ClusterRegion;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.template.IndexTemplatePhy;
import com.didichuxing.datachannel.arius.admin.common.bean.po.cluster.ClusterRegionPO;
import com.didichuxing.datachannel.arius.admin.common.constant.AdminConstant;
import com.didichuxing.datachannel.arius.admin.common.constant.cluster.ClusterResourceTypeEnum;
import com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.OperationEnum;
import com.didichuxing.datachannel.arius.admin.common.event.region.RegionUnbindEvent;
import com.didichuxing.datachannel.arius.admin.common.util.AriusObjUtils;
import com.didichuxing.datachannel.arius.admin.common.util.ConvertUtil;
import com.didichuxing.datachannel.arius.admin.common.util.ListUtils;
import com.didichuxing.datachannel.arius.admin.core.component.SpringTool;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.logic.ClusterLogicService;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.physic.ClusterPhyService;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.physic.ClusterRoleHostService;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.region.ClusterRegionService;
import com.didichuxing.datachannel.arius.admin.core.service.common.OperateRecordService;
import com.didichuxing.datachannel.arius.admin.core.service.template.physic.IndexTemplatePhyService;
import com.didichuxing.datachannel.arius.admin.persistence.mysql.region.ClusterRegionDAO;
import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * @author ohushenglin_v
 * @date 2022-05-30
 */
@Service
public class ClusterRegionServiceImpl implements ClusterRegionService {
    private static final ILog       LOGGER           = LogFactory.getLog(ClusterRegionServiceImpl.class);

    private static final String     REGION_NOT_EXIST = "region %d 不存在";

    @Autowired
    private ClusterRegionDAO        clusterRegionDAO;

    @Autowired
    private ClusterLogicService     clusterLogicService;

    @Autowired
    private ClusterPhyService       esClusterPhyService;

    @Autowired
    private OperateRecordService    operateRecordService;

    @Autowired
    private IndexTemplatePhyService indexTemplatePhyService;

    @Autowired
    private ClusterRoleHostService  clusterRoleHostService;

    @Override
    public ClusterRegion getRegionById(Long regionId) {
        if (regionId == null) {
            return null;
        }
        return ConvertUtil.obj2Obj(clusterRegionDAO.getById(regionId), ClusterRegion.class);
    }

    @Override
    public List<String> listPhysicClusterNames(Long logicClusterId) {
        // 获取逻辑集群有的region
        List<ClusterRegion> regions = listLogicClusterRegions(logicClusterId);
        // 从region获取物理集群名
        return regions.stream().map(ClusterRegion::getPhyClusterName).distinct().collect(Collectors.toList());
    }

    @Override
    public List<Integer> listPhysicClusterId(Long logicClusterId) {
        List<String> clusterNames = listPhysicClusterNames(logicClusterId);
        // 从物理集群名获取物理集群ID
        return clusterNames.stream().map(clusterName -> esClusterPhyService.getClusterByName(clusterName).getId())
            .collect(Collectors.toList());
    }

    @Override
    public List<ClusterRegion> listRegionsByLogicAndPhyCluster(Long logicClusterId, String phyClusterName) {
        if (logicClusterId == null || StringUtils.isBlank(phyClusterName)) {
            return new ArrayList<>();
        }

        ClusterRegionPO condt = new ClusterRegionPO();
        condt.setLogicClusterIds(logicClusterId.toString());
        condt.setPhyClusterName(phyClusterName);

        return ConvertUtil.list2List(clusterRegionDAO.listBoundRegionsByCondition(condt), ClusterRegion.class);
    }

    @Override
    public List<ClusterRegion> listPhyClusterRegions(String phyClusterName) {
        return ConvertUtil.list2List(clusterRegionDAO.getByPhyClusterName(phyClusterName), ClusterRegion.class);
    }

    @Override
    public List<ClusterRegion> listAllBoundRegions() {
        return ConvertUtil.list2List(clusterRegionDAO.listBoundRegions(), ClusterRegion.class);
    }

    @Override
    public Result<Long> createPhyClusterRegion(String clusterName, List<Integer> nodeIds, String regionName, String operator) {
        ClusterRegionPO clusterRegionPO = new ClusterRegionPO();
        clusterRegionPO.setName(regionName);
        clusterRegionPO.setLogicClusterIds(AdminConstant.REGION_NOT_BOUND_LOGIC_CLUSTER_ID);
        clusterRegionPO.setPhyClusterName(clusterName);
        return Result.build(1 == clusterRegionDAO.insert(clusterRegionPO), clusterRegionPO.getId());
    }

    @Override
    public Result<Void> deletePhyClusterRegion(Long regionId, String operator) {
        if (regionId == null) { return Result.buildFail("regionId不能为null");}

        ClusterRegion region = getRegionById(regionId);
        if (region == null) { return Result.buildFail(String.format(REGION_NOT_EXIST, regionId));}

        // 已经绑定过的region不能删除
        if (isRegionBound(region)) {
            // 获取逻辑集群的信息,一个region可能被多个逻辑集群绑定
            List<Long> logicClusterIds = ListUtils.string2LongList(region.getLogicClusterIds());
            List<String> logicClusterNames = Lists.newArrayList();
            for (Long logicClusterId : logicClusterIds) {
                ClusterLogic clusterLogic = clusterLogicService.getClusterLogicById(logicClusterId);
                if (AriusObjUtils.isNull(clusterLogic)) {
                    continue;
                }
                // 获取被绑定的全部逻辑集群的名称
                logicClusterNames.add(clusterLogic.getName());
            }
            return Result.buildFail(String.format("region %d 已经被绑定到逻辑集群 %s", regionId, ListUtils.strList2String(logicClusterNames)));
        }

        // 校验region是否还存在数据节点，如region中存在数据节点，需要先进行移除
        Result<List<ClusterRoleHost>> ret = clusterRoleHostService.listByRegionId(region.getId().intValue());
        if (ret.failed()) { return Result.buildFrom(ret);}
        if (CollectionUtils.isNotEmpty(ret.getData())) {
            List<ClusterRoleHost> clusterRoleHostList = ret.getData();
            List<String> nodeNameList = clusterRoleHostList.stream().map(ClusterRoleHost::getNodeSet).distinct().collect(Collectors.toList());
            return Result.buildFail(String.format("当前region中存在节点[%s]，需要先进行编辑移除", ListUtils.strList2String(nodeNameList)));
        }

        boolean succeed = clusterRegionDAO.delete(regionId) == 1;
        if (succeed) { operateRecordService.save(CLUSTER_REGION, DELETE, regionId, "", operator);}

        return Result.build(succeed);
    }

    @Override
    public Result<Void> deleteByClusterPhy(String clusterPhyName, String operator) {
        return Result.build(0 < clusterRegionDAO.deleteByClusterPhyName(clusterPhyName));
    }

    @Override
    public Result<Void> bindRegion(Long regionId, Long logicClusterId, Integer share, String operator) {

        try {
            // 判断region存在
            ClusterRegion region = getRegionById(regionId);
            if (region == null) {
                return Result.buildFail(String.format(REGION_NOT_EXIST, regionId));
            }

            // 检查逻辑集群存在
            ClusterLogic clusterLogic = clusterLogicService.getClusterLogicById(logicClusterId);
            if (AriusObjUtils.isNull(clusterLogic)) {
                return Result.buildFail(String.format("逻辑集群 %S 不存在", logicClusterId));
            }

            // 判断在未绑定状态,获取region被绑定的逻辑集群的类型，只有被共享逻辑集群绑定的region才能被另一个共享逻辑集群重复绑定
            if (isRegionBound(region)) {
                if (!isRegionBindByPublicLogicCluster(region)) {
                    return Result.buildFail(String.format("region %d 已经被非共享逻辑集群绑定",regionId));
                }

                if (!clusterLogic.getType().equals(ClusterResourceTypeEnum.PUBLIC.getCode())) {
                    return Result.buildFail(String.format("region %d 已经被绑定,并且逻辑集群 %s 不是共享集群",
                            regionId, clusterLogic.getName()));
                }
            }

            if (share == null) {
                share = AdminConstant.YES;
            }

            if (!share.equals(AdminConstant.YES) && !share.equals(AdminConstant.NO)) {
                return Result.buildParamIllegal("指定的share非法");
            }

            // 绑定
            updateRegion(regionId, constructNewLogicIds(logicClusterId,region.getLogicClusterIds()));

            // 发送消息，添加容量规划area（幂等地），添加容量规划容量信息
            operateRecordService.save(REGION, OperationEnum.REGION_BIND, regionId, "", operator);
            return Result.buildSucc();
        } catch (Exception e) {
            LOGGER.error(
                "class=RegionRackServiceImpl||method=bindRegion||regionId={}||logicClusterId={}||share={}||operator={}"
                         + "msg=bind region failed||e->",
                regionId, logicClusterId, share, operator, e);
            return Result.buildFail(e.getMessage());
        }
    }

    private String constructNewLogicIds(Long newLogicClusterId, String oldLogicClusterIds) {
        // region未被绑定，做覆盖操作
        if (oldLogicClusterIds.equals(AdminConstant.REGION_NOT_BOUND_LOGIC_CLUSTER_ID)) {
            return newLogicClusterId.toString();
        }

        // region被绑定,逗号隔开连接
        return oldLogicClusterIds + "," + newLogicClusterId.toString();
    }

    @Override
    public Result<Void> unbindRegion(Long regionId, Long logicClusterId, String operator) {
        try {
            if (regionId == null) {
                return Result.buildFail("未指定regionId");
            }
            // 判断region存在
            ClusterRegion region = getRegionById(regionId);
            if (region == null) {
                return Result.buildFail(String.format(REGION_NOT_EXIST, regionId));
            }

            // 判断在绑定状态
            if (!isRegionBound(region)) {
                return Result.buildFail(String.format("region %d 未被绑定", regionId));
            }

            // 判断region上没有模板
            Result<List<IndexTemplatePhy>> ret = indexTemplatePhyService.listByRegionId(regionId.intValue());
            if (ret.failed()) { return Result.buildFail(ret.getMessage());}

            List<IndexTemplatePhy> clusterTemplates = ret.getData();
            if (CollectionUtils.isNotEmpty(clusterTemplates)) {
                return Result.buildFail(String.format("region %d 上已经分配模板", regionId));
            }

            // 删除绑定
            updateRegion(regionId, getNewBoundLogicIds(region,logicClusterId));

            // 发送消息，删除容量规划容量信息
            SpringTool.publish(new RegionUnbindEvent(this, region, operator));


            // 操作记录
            operateRecordService.save(REGION, OperationEnum.REGION_UNBIND, regionId, "", operator);

            return Result.buildSucc();
        } catch (Exception e) {
            LOGGER.error("class=RegionRackServiceImpl||method=unbindRegion||regionId={}||operator={}"
                         + "msg=unbind region failed||e->",
                regionId, operator, e);
            return Result.buildFail(e.getMessage());
        }
    }

    /**
     * 获取region解绑指定逻辑集群之后剩余的逻辑集群id列表
     * @param region region
     * @param logicClusterId 逻辑集群id
     * @return region新的逻辑集群id列表
     */
    private String getNewBoundLogicIds(ClusterRegion region, Long logicClusterId) {
        // 获取region已经关联到的逻辑集群id列表
        List<Long> boundLogicClusterIds = ListUtils.string2LongList(region.getLogicClusterIds());

        // 当没有指定解绑的逻辑集群id或者region没有被逻辑集群绑定或者region仅被指定解绑的逻辑集群绑定，则回滚至默认值-1
        if (AriusObjUtils.isNull(logicClusterId)
                || CollectionUtils.isEmpty(boundLogicClusterIds)
                || (boundLogicClusterIds.size() == 1 && boundLogicClusterIds.contains(logicClusterId))) {
            return AdminConstant.REGION_NOT_BOUND_LOGIC_CLUSTER_ID;
        }

        // 解绑指定逻辑集群
        boundLogicClusterIds.remove(logicClusterId);
        return ListUtils.longList2String(boundLogicClusterIds);
    }

    /**
     * 获取逻辑集群拥有的region
     * @param logicClusterId 逻辑集群ID
     * @return 逻辑集群拥有的region
     */
    @Override
    public List<ClusterRegion> listLogicClusterRegions(Long logicClusterId) {

        if (logicClusterId == null) {
            return new ArrayList<>();
        }

        List<ClusterRegionPO> clusterRegionPOS = clusterRegionDAO.listAll()
                .stream()
                .filter(clusterRegionPO -> ListUtils.string2LongList(clusterRegionPO.getLogicClusterIds()).contains(logicClusterId))
                .collect(Collectors.toList());

        return ConvertUtil.list2List(clusterRegionPOS, ClusterRegion.class);
    }

    @Override
    public ClusterRegion getRegionByLogicClusterId(Long logicClusterId) {
        if (logicClusterId == null) { return null;}

        ClusterRegionPO clusterRegionPO = clusterRegionDAO.getByLogicClusterId(logicClusterId);
        return ConvertUtil.obj2Obj(clusterRegionPO, ClusterRegion.class);
    }

    /**
     * 获取物理下的region
     * @param phyClusterName 物理集群名
     * @return 物理集群下的region
     */
    @Override
    public List<ClusterRegion> listRegionsByClusterName(String phyClusterName) {
        if (StringUtils.isBlank(phyClusterName)) {
            return new ArrayList<>();
        }
        return ConvertUtil.list2List(clusterRegionDAO.getByPhyClusterName(phyClusterName), ClusterRegion.class);
    }

    /**
     * 判断region是否已经被绑定给逻辑集群
     * @param region region
     * @return true-已经被绑定，false-没有被绑定
     */
    @Override
    public boolean isRegionBound(ClusterRegion region) {
        if (region == null) {
            return false;
        }

        return !region.getLogicClusterIds().equals(AdminConstant.REGION_NOT_BOUND_LOGIC_CLUSTER_ID);
    }

    /**
     * 判断region是否被共享类型的逻辑集群绑定
     * @param region region信息
     * @return true or false
     */
    private boolean isRegionBindByPublicLogicCluster(ClusterRegion region) {
        if (!isRegionBound(region)) {
            return false;
        }

        // 只有共享逻辑集群下的region能够被重复绑定
        Long logicClusterId = ListUtils.string2LongList(region.getLogicClusterIds()).get(0);
        ClusterLogic clusterLogic = clusterLogicService.getClusterLogicById(logicClusterId);

        return !AriusObjUtils.isNull(clusterLogic) && clusterLogic.getType().equals(ClusterResourceTypeEnum.PUBLIC.getCode());
    }

    @Override
    public Set<Long> getLogicClusterIdByPhyClusterId(Integer phyClusterId) {
        ClusterPhy clusterPhy = esClusterPhyService.getClusterById(phyClusterId);
        if (clusterPhy == null) {
            return null;
        }
        List<ClusterRegion> clusterRegions = listRegionsByClusterName(clusterPhy.getCluster());
        if (CollectionUtils.isEmpty(clusterRegions)) {
            return null;
        }

        // 获取物理集群对应的逻辑集群，进行去重的操作
        Set<Long> logicClusterIds = Sets.newHashSet();
        clusterRegions.forEach(clusterRegion -> logicClusterIds.addAll(new HashSet<>(ListUtils.string2LongList(clusterRegion.getLogicClusterIds()))));
        return logicClusterIds;
    }

    @Override
    public boolean isExistByRegionName(String regionName) {
        return null != clusterRegionDAO.getByName(regionName);
    }

    @Override
    public boolean isExistByRegionId(Integer regionId) {
        return null != clusterRegionDAO.getById(regionId.longValue());
    }

    @Override
    public List<ClusterRegion> listClusterRegionsByLogicIds(List<Long> clusterLogicIds) {
        if (CollectionUtils.isEmpty(clusterLogicIds)) { return Lists.newArrayList();}

        List<Long> uniqueClusterLogicIds = clusterLogicIds.stream().distinct().collect(Collectors.toList());
        List<ClusterRegion> clusterRegionList = Lists.newArrayList();
        for (Long clusterLogicId : uniqueClusterLogicIds) {
            ClusterRegionPO logicCluster = clusterRegionDAO.getByLogicClusterId(clusterLogicId);
            clusterRegionList.add(ConvertUtil.obj2Obj(logicCluster, ClusterRegion.class));
        }

        return clusterRegionList;
    }

    /***************************************** private method ****************************************************/
    /**
     * 根据regionId更新region的logicClusterId或racks
     * @param regionId       要更新的region的ID
     * @param logicClusterIds 逻辑集群ID列表，为null则不更新
     */
    private void updateRegion(Long regionId, String logicClusterIds) {
        if (regionId == null) { return;}

        ClusterRegionPO updateParam = new ClusterRegionPO();
        updateParam.setId(regionId);
        updateParam.setLogicClusterIds(logicClusterIds);

        clusterRegionDAO.update(updateParam);
    }
}