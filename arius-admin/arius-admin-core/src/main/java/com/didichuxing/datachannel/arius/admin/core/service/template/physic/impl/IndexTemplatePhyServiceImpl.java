package com.didichuxing.datachannel.arius.admin.core.service.template.physic.impl;

import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.ModuleEnum.TEMPLATE;
import static com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.OperationEnum.DELETE;

import com.alibaba.fastjson.JSON;
import com.didichuxing.datachannel.arius.admin.common.bean.common.IndexTemplatePhysicalConfig;
import com.didichuxing.datachannel.arius.admin.common.bean.common.Result;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.template.IndexTemplateDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.template.IndexTemplatePhyDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.region.ClusterRegion;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.template.IndexTemplate;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.template.IndexTemplatePhy;
import com.didichuxing.datachannel.arius.admin.common.bean.entity.template.IndexTemplatePhyWithLogic;
import com.didichuxing.datachannel.arius.admin.common.bean.po.template.IndexTemplatePO;
import com.didichuxing.datachannel.arius.admin.common.bean.po.template.IndexTemplatePhyPO;
import com.didichuxing.datachannel.arius.admin.common.constant.template.TemplateDeployRoleEnum;
import com.didichuxing.datachannel.arius.admin.common.constant.template.TemplatePhysicalStatusEnum;
import com.didichuxing.datachannel.arius.admin.common.event.template.PhysicalTemplateDeleteEvent;
import com.didichuxing.datachannel.arius.admin.common.exception.AdminOperateException;
import com.didichuxing.datachannel.arius.admin.common.exception.ESOperateException;
import com.didichuxing.datachannel.arius.admin.common.util.AriusObjUtils;
import com.didichuxing.datachannel.arius.admin.common.util.ConvertUtil;
import com.didichuxing.datachannel.arius.admin.common.util.IndexNameFactory;
import com.didichuxing.datachannel.arius.admin.common.util.RackUtils;
import com.didichuxing.datachannel.arius.admin.core.component.CacheSwitch;
import com.didichuxing.datachannel.arius.admin.core.component.ResponsibleConvertTool;
import com.didichuxing.datachannel.arius.admin.core.component.SpringTool;
import com.didichuxing.datachannel.arius.admin.core.service.cluster.region.RegionRackService;
import com.didichuxing.datachannel.arius.admin.core.service.common.OperateRecordService;
import com.didichuxing.datachannel.arius.admin.core.service.es.ESIndexService;
import com.didichuxing.datachannel.arius.admin.core.service.es.ESTemplateService;
import com.didichuxing.datachannel.arius.admin.core.service.template.logic.IndexTemplateService;
import com.didichuxing.datachannel.arius.admin.core.service.template.physic.IndexTemplatePhyService;
import com.didichuxing.datachannel.arius.admin.persistence.mysql.template.IndexTemplateDAO;
import com.didichuxing.datachannel.arius.admin.persistence.mysql.template.IndexTemplatePhyDAO;
import com.didiglobal.logi.elasticsearch.client.response.indices.catindices.CatIndexResult;
import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author d06679
 * @date 2019/3/29
 */
@Service
public class IndexTemplatePhyServiceImpl implements IndexTemplatePhyService {

    private static final ILog                              LOGGER                         = LogFactory
        .getLog(IndexTemplatePhyServiceImpl.class);

    public static final Integer                            NOT_CHECK                      = -100;

    private static final String                            MSG                            = "editTemplateFromLogic fail||physicalId={}||expression={}";

    @Autowired
    private IndexTemplatePhyDAO indexTemplatePhyDAO;

    @Autowired
    private IndexTemplateDAO indexTemplateDAO;

    @Autowired
    private OperateRecordService operateRecordService;

    @Autowired
    private ESIndexService                                 esIndexService;

    @Autowired
    private ESTemplateService                              esTemplateService;

    @Autowired
    private ResponsibleConvertTool                         responsibleConvertTool;

    @Autowired
    private IndexTemplateService indexTemplateService;

    @Autowired
    private RegionRackService regionRackService;

    @Autowired
    private CacheSwitch                                    cacheSwitch;
    private Cache<String, List<?>> templatePhyListCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(10).build();

    /**
     * 条件查询
     *
     * @param param 参数
     * @return 物理模板列表
     */
    @Override
    public List<IndexTemplatePhy> getByCondt(IndexTemplatePhyDTO param) {
        return ConvertUtil.list2List(
            indexTemplatePhyDAO.listByCondition(ConvertUtil.obj2Obj(param, IndexTemplatePhyPO.class)),
            IndexTemplatePhy.class);
    }

    /**
     * 查询指定逻辑模板对应的物理模板
     *
     * @param logicId 逻辑模板
     * @return result
     */
    @Override
    public List<IndexTemplatePhy> getTemplateByLogicId(Integer logicId) {
        return ConvertUtil.list2List(indexTemplatePhyDAO.listByLogicId(logicId), IndexTemplatePhy.class);
    }

    /**
     * 从缓存中查询指定逻辑模板对应的物理模板
     * @param logicId 逻辑模板
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> getValidTemplatesByLogicId(Integer logicId) {
        if (logicId == null) {
            return Lists.newArrayList();
        }

        List<IndexTemplatePhy> indexTemplatePhies = listTemplate();
        if (CollectionUtils.isEmpty(indexTemplatePhies)) {
            return Lists.newArrayList();
        }

        return indexTemplatePhies.parallelStream()
            .filter(i -> i.getLogicId() != null && logicId.intValue() == i.getLogicId().intValue())
            .collect(Collectors.toList());
    }

    @Override
    public IndexTemplatePhy getTemplateById(Long templatePhyId) {
        return ConvertUtil.obj2Obj(indexTemplatePhyDAO.getById(templatePhyId), IndexTemplatePhy.class);
    }

    /**
     * 查询指定id的模板 包含逻辑模板信息
     *
     * @param physicalId 物理模板id
     * @return result
     */
    @Override
    public IndexTemplatePhyWithLogic getTemplateWithLogicById(Long physicalId) {
        IndexTemplatePhyPO physicalPO = indexTemplatePhyDAO.getById(physicalId);
        return buildIndexTemplatePhysicalWithLogic(physicalPO);
    }

    @Override
    public Result<Long> insert(IndexTemplatePhyDTO param) throws AdminOperateException {
        IndexTemplatePhyPO newTemplate = ConvertUtil.obj2Obj(param, IndexTemplatePhyPO.class);
        boolean succ;
        try {
            succ = (1 == indexTemplatePhyDAO.insert(newTemplate));
        } catch (DuplicateKeyException e) {
            LOGGER.warn("class=TemplatePhyServiceImpl||method=insert||errMsg={}", e.getMessage());
            throw new AdminOperateException(String.format("保存物理模板【%s】失败：物理模板已存在！", newTemplate.getName()));
        }
        return Result.build(succ,newTemplate.getId());
    }

    @Override
    public void deleteDirtyByClusterAndName(String cluster, String name) {
        indexTemplatePhyDAO.deleteDirtyByClusterAndName(cluster, name);
    }

    /**
     * 删除
     *
     * @param physicalId 物理模板id
     * @param operator   操作人
     * @return result
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delTemplate(Long physicalId, String operator) throws ESOperateException {
        IndexTemplatePhyPO oldPO = indexTemplatePhyDAO.getById(physicalId);
        if (oldPO == null) {
            return Result.buildNotExist("template not exist");
        }

        boolean succ = 1 == indexTemplatePhyDAO.updateStatus(physicalId,
            TemplatePhysicalStatusEnum.INDEX_DELETING.getCode());
        if (succ) {
            // 删除集群中的模板
            try {
                esTemplateService.syncDelete(oldPO.getCluster(), oldPO.getName(), 1);
            }catch (ESOperateException e){
                String msg = e.getMessage();
                if (StringUtils.indexOf(msg, "index_template_missing_exception") != -1) {
                    LOGGER.warn("class=TemplatePhyServiceImpl||method=delTemplate||physicalId={}||operator={}||msg= index template not found!", physicalId, operator);
                } else {
                    LOGGER.error("class=TemplatePhyServiceImpl||method=delTemplate||physicalId={}||operator={}||msg=delete physical template failed! {}", physicalId, operator, msg);
                    throw new ESOperateException(String.format("删除集群【%s】中物理模板【%s】失败！", oldPO.getCluster(), oldPO.getName()));
                }
            }

            operateRecordService.save(TEMPLATE, DELETE, oldPO.getLogicId(),
                String.format("下线物理集群[%s]中模板[%s]", oldPO.getCluster(), oldPO.getName()), operator);

            SpringTool.publish(new PhysicalTemplateDeleteEvent(this, ConvertUtil.obj2Obj(oldPO, IndexTemplatePhy.class),
                indexTemplateService.getLogicTemplateWithPhysicalsById(oldPO.getLogicId())));
        }

        return Result.build(succ);
    }

    /**
     * 删除
     *
     * @param logicId  id
     * @param operator 操作人
     * @return result
     * @throws ESOperateException e
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delTemplateByLogicId(Integer logicId, String operator) throws ESOperateException {
        List<IndexTemplatePhyPO> physicalPOs = indexTemplatePhyDAO.listByLogicId(logicId);

        boolean succ = true;
        if (CollectionUtils.isEmpty(physicalPOs)) {
            LOGGER.info("class=TemplatePhyServiceImpl||method=delTemplateByLogicId||logicId={}||msg=template no physical info!", logicId);
        } else {
            LOGGER.info("class=TemplatePhyServiceImpl||method=delTemplateByLogicId||logicId={}||physicalSize={}||msg=template has physical info!",
                logicId, physicalPOs.size());
            for (IndexTemplatePhyPO physicalPO : physicalPOs) {
                if (delTemplate(physicalPO.getId(), operator).failed()) {
                    succ = false;
                }

            }
        }

        return Result.build(succ);
    }

    /**
     * 修改由于逻辑模板修改而物理模板需要同步修改的属性
     * 目前有:
     * expression
     *
     * @param param    参数
     * @param operator 操作人
     * @return result
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> editTemplateFromLogic(IndexTemplateDTO param, String operator) throws ESOperateException {
        if (param == null) {
            return Result.buildFail("参数为空！");
        }
        List<IndexTemplatePhyPO> physicalPOs = indexTemplatePhyDAO.listByLogicId(param.getId());
        if (CollectionUtils.isEmpty(physicalPOs)) {
            return Result.buildSucc();
        }

        for (IndexTemplatePhyPO physicalPO : physicalPOs) {
            IndexTemplatePhyPO updateParam = new IndexTemplatePhyPO();
            updateParam.setId(physicalPO.getId());

            if(null != param.getWriteRateLimit() && StringUtils.isNotBlank(physicalPO.getConfig())) {
                IndexTemplatePhysicalConfig indexTemplatePhysicalConfig = JSON.parseObject(physicalPO.getConfig(),
                        IndexTemplatePhysicalConfig.class);

                indexTemplatePhysicalConfig.setManualPipeLineRateLimit(param.getWriteRateLimit());
                updateParam.setConfig(JSON.toJSONString(indexTemplatePhysicalConfig));
                boolean succeed = (1 == indexTemplatePhyDAO.update(updateParam));
                if (!succeed) {
                    LOGGER.warn("class=TemplatePhyServiceImpl||method=editTemplateFromLogic||msg=editTemplateFromLogic fail||physicalId={}||expression={}||writeRateLimit={}", physicalPO.getId(),
                            param.getExpression(), param.getWriteRateLimit());
                    return Result.build(false);
                }
            }

            Result<Void> buildExpression = updateExpressionTemplatePhysical(param, updateParam, physicalPO);
            if (buildExpression.failed()) {return buildExpression;}

            Result<Void> buildShardNum   = updateShardNumTemplatePhy(param, updateParam, physicalPO);
            if (buildShardNum.failed()) {return buildShardNum;}
        }

        return Result.buildSucc();
    }

    /**
     * 通过集群和名字查询
     *
     * @param cluster      集群
     * @param templateName 名字
     * @return result 不存在返回null
     */
    @Override
    public IndexTemplatePhy getTemplateByClusterAndName(String cluster, String templateName) {
        return ConvertUtil.obj2Obj(indexTemplatePhyDAO.getByClusterAndName(cluster, templateName),
            IndexTemplatePhy.class);
    }

    /**
     * 通过集群和名字查询
     *
     * @param cluster      集群
     * @param templateName 名字
     * @return result 不存在返回null
     */
    @Override
    public IndexTemplatePhyWithLogic getTemplateWithLogicByClusterAndName(String cluster, String templateName) {
        return buildIndexTemplatePhysicalWithLogic(indexTemplatePhyDAO.getByClusterAndName(cluster, templateName));
    }

    /**
     * 根据物理模板状态获取模板列表
     *
     * @param cluster 集群
     * @param status  状态
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> getTemplateByClusterAndStatus(String cluster, int status) {
        return ConvertUtil.list2List(indexTemplatePhyDAO.listByClusterAndStatus(cluster, status),
            IndexTemplatePhy.class);
    }

    /**
     * 获取状态正常的模板列表
     *
     * @param cluster 集群
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> getNormalTemplateByCluster(String cluster) {
        return ConvertUtil.list2List(
            indexTemplatePhyDAO.listByClusterAndStatus(cluster, TemplatePhysicalStatusEnum.NORMAL.getCode()),
            IndexTemplatePhy.class);
    }

    @Override
    public Set<String> getMatchNormalLogicIdByCluster(String cluster) {
        return ConvertUtil.list2Set(
                indexTemplatePhyDAO.listByMatchClusterAndStatus(cluster, TemplatePhysicalStatusEnum.NORMAL.getCode()),
                x -> x.getLogicId().toString());
    }

    /**
     * 根据集群和分区获取模板列表
     *
     * @param cluster 集群
     * @param racks
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> getNormalTemplateByClusterAndRack(String cluster, Collection<String> racks) {
        if (CollectionUtils.isEmpty(racks)) {
            return Lists.newArrayList();
        }
        List<IndexTemplatePhy> templatePhysicals = getNormalTemplateByCluster(cluster);
        if (CollectionUtils.isEmpty(templatePhysicals)) {
            return Lists.newArrayList();
        }
        return templatePhysicals.stream()
            .filter(templatePhysical -> RackUtils.hasIntersect(templatePhysical.getRack(), racks))
            .collect(Collectors.toList());
    }

    /**
     * 获取模板匹配的索引列表，按着时间排序
     * 注意：
     * 该方法只能识别出那些时间后缀是一样的情况；
     * 如果模板中途修改过时间后缀，则无法识别之前时间后缀的索引
     *
     * @param physicalId 物理模板id
     * @return list
     */
    @Override
    public List<String> getMatchNoVersionIndexNames(Long physicalId) {
        IndexTemplatePhyWithLogic templatePhysicalWithLogic = getTemplateWithLogicById(physicalId);
        if (templatePhysicalWithLogic == null) {
            return Lists.newArrayList();
        }
        Set<String> indices = esIndexService.syncGetIndexNameByExpression(templatePhysicalWithLogic.getCluster(),
            templatePhysicalWithLogic.getExpression());
        if (CollectionUtils.isEmpty(indices)) {
            return Lists.newArrayList();
        }

        Set<String> noVersionIndices = indices.stream()
            .map(indexName -> IndexNameFactory.genIndexNameClear(indexName, templatePhysicalWithLogic.getExpression()))
            .collect(Collectors.toSet());

        List<String> matchIndices = Lists.newArrayList();
        for (String noVersionIndex : noVersionIndices) {
            if (IndexNameFactory.noVersionIndexMatchExpression(noVersionIndex,
                templatePhysicalWithLogic.getExpression(),
                templatePhysicalWithLogic.getLogicTemplate().getDateFormat())) {
                matchIndices.add(noVersionIndex);
            }
        }

        Collections.sort(matchIndices);

        return matchIndices;
    }

    /**
     * 获取模板匹配的索引列表，按着时间排序
     *
     * @param physicalId 物理模板id
     * @return list
     */
    @Override
    public List<String> getMatchIndexNames(Long physicalId) {
        IndexTemplatePhyWithLogic templatePhysicalWithLogic = getNormalAndDeletingTemplateWithLogicById(physicalId);
        if (templatePhysicalWithLogic == null) {
            return Lists.newArrayList();
        }

        List<CatIndexResult> indices = esIndexService.syncCatIndexByExpression(templatePhysicalWithLogic.getCluster(),
            templatePhysicalWithLogic.getExpression());
        if (CollectionUtils.isEmpty(indices)) {
            return Lists.newArrayList();
        }

        List<String> matchIndices = Lists.newArrayList();
        for (CatIndexResult indexResult : indices) {
            LOGGER.info("class=TemplatePhyServiceImpl||method=getMatchIndexNames||msg=fetch should be deleted indices||template={}||status={}||"
                        + "cluster={}||docCount={}||docSize={}",
                templatePhysicalWithLogic.getName(), templatePhysicalWithLogic.getStatus(),
                templatePhysicalWithLogic.getCluster(), indexResult.getDocsCount(), indexResult.getStoreSize());

            if (IndexNameFactory.indexMatchExpression(indexResult.getIndex(), templatePhysicalWithLogic.getExpression(),
                templatePhysicalWithLogic.getLogicTemplate().getDateFormat())) {
                matchIndices.add(indexResult.getIndex());
            }
        }

        Collections.sort(matchIndices);

        return matchIndices;
    }

    /**
     * 批量获取模板信息
     *
     * @param physicalIds 物理模板id
     * @return list
     */
    @Override
    public List<IndexTemplatePhyWithLogic> getTemplateWithLogicByIds(List<Long> physicalIds) {
        if (CollectionUtils.isEmpty(physicalIds)) {
            return Lists.newArrayList();
        }
        List<IndexTemplatePhyPO> indexTemplatePhyPOS = indexTemplatePhyDAO.listByIds(physicalIds);
        return batchBuildTemplatePhysicalWithLogic(indexTemplatePhyPOS);
    }

    @Override
    public List<IndexTemplatePhyWithLogic> getTemplateByPhyCluster(String phyCluster) {
        if (phyCluster == null) {
            return new ArrayList<>();
        }

        return batchBuildTemplatePhysicalWithLogic(indexTemplatePhyDAO.listByClusterAndStatus(phyCluster, TemplatePhysicalStatusEnum.NORMAL.getCode()));
    }

    /**
     * 根据名字查询
     *
     * @param template 名字
     * @return list
     */
    @Override
    public List<IndexTemplatePhyWithLogic> getTemplateWithLogicByName(String template) {
        List<IndexTemplatePhyPO> indexTemplatePhyPOS = indexTemplatePhyDAO.listByName(template);
        return batchBuildTemplatePhysicalWithLogic(indexTemplatePhyPOS);
    }

    /**
     * 获取全量
     *
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> listTemplate() {
        return ConvertUtil.list2List(indexTemplatePhyDAO.listAll(), IndexTemplatePhy.class);
    }

    @Override
    public List<IndexTemplatePhy> listTemplateWithCache() {
        try {
            return (List<IndexTemplatePhy>) templatePhyListCache.get("listTemplate", this::listTemplate);
        } catch (Exception e) {
            return listTemplate();
        }
    }

    /**
     * 获取IndexTemplatePhysicalWithLogic
     *
     * @return list
     */
    @Override
    public List<IndexTemplatePhyWithLogic> listTemplateWithLogic() {
        List<IndexTemplatePhyPO> indexTemplatePhyPOS = indexTemplatePhyDAO.listAll();
        return batchBuildTemplatePhysicalWithLogic(indexTemplatePhyPOS);
    }

    @Override
    public List<IndexTemplatePhyWithLogic> listTemplateWithLogicWithCache() {
        try {
            return (List<IndexTemplatePhyWithLogic>) templatePhyListCache.get("listTemplateWithLogic", this::listTemplateWithLogic);
        } catch (Exception e) {
            return listTemplateWithLogic();
        }
    }

    @Override
    public Map<String, Integer> getClusterTemplateCountMap() {
        Map<String, Integer> templateCountMap = Maps.newHashMap();
        List<IndexTemplatePhyWithLogic> indexTemplateList = listTemplateWithLogic();
        if (CollectionUtils.isNotEmpty(indexTemplateList)) {
            for (IndexTemplatePhyWithLogic indexTemplate : indexTemplateList) {
                Integer templateCount = templateCountMap.get(indexTemplate.getCluster());
                templateCount = templateCount == null ? 1 : templateCount + 1;
                templateCountMap.put(indexTemplate.getCluster(), templateCount);
            }
        }
        return templateCountMap;
    }

    /**
     * 根绝逻辑模板id列表查询
     *
     * @param logicIds 列表
     * @return list
     */
    @Override
    public List<IndexTemplatePhy> getTemplateByLogicIds(List<Integer> logicIds) {
        return ConvertUtil.list2List(indexTemplatePhyDAO.listByLogicIds(logicIds), IndexTemplatePhy.class);
    }

    @Override
    public Result<Void> updateTemplateName(IndexTemplatePhy physical, String operator) throws ESOperateException {
        Result<Void> validResult = validParamUpdateTemplateName(physical);
        if(validResult.failed()) {
            return Result.buildFrom(validResult);
        }

        IndexTemplatePhyPO editParam = new IndexTemplatePhyPO();
        editParam.setId(physical.getId());
        editParam.setName(physical.getName());

        //更新数据库中物理模板的名称
        boolean succ = 1 == indexTemplatePhyDAO.update(editParam);
        if (!succ) {
            return Result.buildFail("修改物理模板失败");
        }

        //更新ES当中存储的物理模板的名称
        IndexTemplatePhyPO oldPhysicalPO = indexTemplatePhyDAO.getById(physical.getId());
        return Result.build(
            esTemplateService.syncUpdateName(physical.getCluster(), oldPhysicalPO.getName(), physical.getName(), 0));
    }

    @Override
    public Result<Void> updateTemplateExpression(IndexTemplatePhy indexTemplatePhy, String expression, String operator) throws ESOperateException {
        IndexTemplatePhyPO updateParam = new IndexTemplatePhyPO();
        updateParam.setId(indexTemplatePhy.getId());
        updateParam.setExpression(expression);
        boolean succeed = (1 == indexTemplatePhyDAO.update(updateParam));

        if (succeed) {
            esTemplateService.syncUpdateExpression(indexTemplatePhy.getCluster(), indexTemplatePhy.getName(),
                    expression, 0);
        } else {
            LOGGER.warn("class=TemplatePhyServiceImpl||method=updateTemplateExpression||msg=", MSG, indexTemplatePhy.getId(),
                    expression);
            return Result.buildFail("数据库更新失败");
        }
        return Result.buildSucc();
    }

    @Override
    public Result<Void> updateTemplateShardNum(IndexTemplatePhy indexTemplatePhy, Integer shardNum, String operator) throws ESOperateException {
        IndexTemplatePhyPO updateParam = new IndexTemplatePhyPO();
        updateParam.setId(indexTemplatePhy.getId());
        updateParam.setShard(shardNum);
        boolean succeed = 1 == indexTemplatePhyDAO.update(updateParam);
        if (succeed) {
            LOGGER.info("class=TemplatePhyServiceImpl||method=editTemplateFromLogic succeed||physicalId={}||preShardNum={}||currentShardNum={}",
                    indexTemplatePhy.getId(), indexTemplatePhy.getShard(), shardNum);

            esTemplateService.syncUpdateShardNum(indexTemplatePhy.getCluster(), indexTemplatePhy.getName(),
                    shardNum, 0);
        } else {
            LOGGER.warn("class=TemplatePhyServiceImpl||method=updateTemplateShardNum||editTemplateFromLogic fail||physicalId={}||shardNum={}", indexTemplatePhy.getId(),
                    shardNum);
            return Result.buildFail("数据库更新失败");
        }
        return Result.buildSucc();
    }

    @Override
    public Result<Void> updateTemplateRole(IndexTemplatePhy indexTemplatePhy, TemplateDeployRoleEnum templateDeployRoleEnum, String operator) {
        IndexTemplatePhyPO updateParam = new IndexTemplatePhyPO();
        updateParam.setId(indexTemplatePhy.getId());
        updateParam.setRole(templateDeployRoleEnum.getCode());
        return Result.build(1 == indexTemplatePhyDAO.update(updateParam));
    }

    @Override
    public Result<Void> update(IndexTemplatePhyDTO indexTemplatePhyDTO) {
        IndexTemplatePhyPO updateParam = ConvertUtil.obj2Obj(indexTemplatePhyDTO, IndexTemplatePhyPO.class);
        return Result.build(1 == indexTemplatePhyDAO.update(updateParam));
    }

    @Override
    public IndexTemplatePhyWithLogic buildIndexTemplatePhysicalWithLogic(IndexTemplatePhyPO physicalPO) {
        if (physicalPO == null) {
            return null;
        }

        IndexTemplatePhyWithLogic indexTemplatePhyWithLogic = ConvertUtil.obj2Obj(physicalPO,
            IndexTemplatePhyWithLogic.class);

        IndexTemplatePO logicPO = indexTemplateDAO.getById(physicalPO.getLogicId());
        if (logicPO == null) {
            LOGGER.warn("class=TemplatePhyServiceImpl||method=buildIndexTemplatePhysicalWithLogic||logic template not exist||logicId={}", physicalPO.getLogicId());
            return indexTemplatePhyWithLogic;
        }
        indexTemplatePhyWithLogic.setLogicTemplate(ConvertUtil.obj2Obj(logicPO, IndexTemplate.class));
        return indexTemplatePhyWithLogic;
    }

    @Override
    public List<IndexTemplatePhy> getTemplateByRegionId(Long regionId) {
        ClusterRegion region = regionRackService.getRegionById(regionId);
        if (AriusObjUtils.isNull(region)) {
            return Lists.newArrayList();
        }

        return getNormalTemplateByClusterAndRack(region.getPhyClusterName(), RackUtils.racks2List(region.getRacks()));
    }

    @Override
    public Map<Integer, Integer> getAllLogicTemplatesPhysicalCount() {
        Map<Integer, Integer> map = new HashMap<>();
        List<IndexTemplatePhyPO> list = indexTemplatePhyDAO.countListByLogicId();
        if (CollectionUtils.isNotEmpty(list)) {
            map = list.stream().collect(Collectors.toMap(IndexTemplatePhyPO::getLogicId, o -> 1, Integer::sum));
        }
        return map;
    }


    /**************************************************** private method ****************************************************/
    private List<IndexTemplatePhyWithLogic> batchBuildTemplatePhysicalWithLogic(List<IndexTemplatePhyPO> indexTemplatePhyPOS) {
        if (CollectionUtils.isEmpty(indexTemplatePhyPOS)) {
            return Lists.newArrayList();
        }

        List<Integer> logicIds = indexTemplatePhyPOS.stream().map(IndexTemplatePhyPO::getLogicId)
            .collect(Collectors.toList());
        List<IndexTemplatePO> indexTemplatePOS = indexTemplateDAO.listByIds(logicIds);
        Map<Integer, IndexTemplatePO> id2IndexTemplateLogicPOMap = ConvertUtil.list2Map(indexTemplatePOS,
            IndexTemplatePO::getId);

        List<IndexTemplatePhyWithLogic> physicalWithLogics = Lists.newArrayList();
        for (IndexTemplatePhyPO indexTemplatePhyPO : indexTemplatePhyPOS) {
            IndexTemplatePhyWithLogic physicalWithLogic = ConvertUtil.obj2Obj(indexTemplatePhyPO,
                IndexTemplatePhyWithLogic.class);
            physicalWithLogic.setLogicTemplate(ConvertUtil
                .obj2Obj(id2IndexTemplateLogicPOMap.get(indexTemplatePhyPO.getLogicId()), IndexTemplate.class));

            physicalWithLogics.add(physicalWithLogic);
        }

        return physicalWithLogics;
    }

    private IndexTemplatePhyWithLogic getNormalAndDeletingTemplateWithLogicById(Long physicalId) {
        IndexTemplatePhyPO physicalPO = indexTemplatePhyDAO.getNormalAndDeletingById(physicalId);
        return buildIndexTemplatePhysicalWithLogic(physicalPO);
    }

    /**
     * 判定是否是合法的shard number.
     *
     * @param shardNum shard 数量
     * @return boolean
     */
    private boolean isValidShardNum(Integer shardNum) {
        return shardNum != null && shardNum > 0;
    }

    /**
     * 更新物理模板名称时，做参数的校验
     * @param physical 索引物理模板
     * @return 校验结果
     */
    private Result<Void> validParamUpdateTemplateName(IndexTemplatePhy physical) {
        if (AriusObjUtils.isNull(physical)) {
            return Result.buildParamIllegal("输入的物理模板为空");
        }
        if (AriusObjUtils.isNull(physical.getId())) {
            return Result.buildParamIllegal("需要修改的物理模板的Id为空");
        }
        if (AriusObjUtils.isNull(physical.getName())) {
            return Result.buildParamIllegal("需要修改的物理模板的名称为空");
        }

        IndexTemplatePhyPO oldIndexTemplatePhy = indexTemplatePhyDAO.getById(physical.getId());
        if (AriusObjUtils.isNull(oldIndexTemplatePhy)) {
            return Result.buildParamIllegal("需要修改的物理模板的id对应的原数据不存在");
        }

        return Result.buildSucc();
    }

    private Result<Void> updateShardNumTemplatePhy(IndexTemplateDTO param, IndexTemplatePhyPO updateParam, IndexTemplatePhyPO physicalPO) throws ESOperateException {
        if (isValidShardNum(param.getShardNum())
                && AriusObjUtils.isChanged(param.getShardNum(), physicalPO.getShard())) {
            updateParam.setId(physicalPO.getId());
            updateParam.setShard(param.getShardNum());
            boolean succeed = 1 == indexTemplatePhyDAO.update(updateParam);
            if (succeed) {
                LOGGER.info("class=TemplatePhyServiceImpl||method=updateShardNumTemplatePhy||editTemplateFromLogic succeed||physicalId={}||preShardNum={}||currentShardNum={}",
                        physicalPO.getId(), physicalPO.getShard(), param.getShardNum());

                esTemplateService.syncUpdateRackAndShard(physicalPO.getCluster(), physicalPO.getName(),
                        physicalPO.getRack(), param.getShardNum(), physicalPO.getShardRouting(), 0);
            } else {
                LOGGER.warn("class=TemplatePhyServiceImpl||method=updateShardNumTemplatePhy||msg=", MSG, physicalPO.getId(),
                        param.getExpression());
                return Result.build(false);
            }
        }
        return Result.buildSucc();
    }

    private Result<Void> updateExpressionTemplatePhysical(IndexTemplateDTO param, IndexTemplatePhyPO updateParam, IndexTemplatePhyPO physicalPO) throws ESOperateException {
        if (AriusObjUtils.isChanged(param.getExpression(), physicalPO.getExpression())) {
            updateParam.setId(physicalPO.getId());
            updateParam.setExpression(param.getExpression());
            boolean succeed = (1 == indexTemplatePhyDAO.update(updateParam));
            if (succeed) {
                esTemplateService.syncUpdateExpression(physicalPO.getCluster(), physicalPO.getName(),
                        param.getExpression(), 0);
            } else {
                LOGGER.warn("class=TemplatePhyServiceImpl||method=updateExpressionTemplatePhysical||msg=", MSG, physicalPO.getId(),
                        param.getExpression());
                return Result.build(false);
            }
        }
        return Result.buildSucc();
    }
}