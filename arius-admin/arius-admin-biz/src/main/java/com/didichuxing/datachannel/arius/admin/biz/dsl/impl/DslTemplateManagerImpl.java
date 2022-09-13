package com.didichuxing.datachannel.arius.admin.biz.dsl.impl;

import static com.didichuxing.datachannel.arius.admin.common.constant.PageSearchHandleTypeEnum.DSL_TEMPLATE;

import com.didichuxing.datachannel.arius.admin.biz.dsl.DslTemplateManager;
import com.didichuxing.datachannel.arius.admin.biz.page.DslTemplatePageSearchHandle;
import com.didichuxing.datachannel.arius.admin.common.bean.common.PaginationResult;
import com.didichuxing.datachannel.arius.admin.common.bean.common.Result;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.dsl.DslQueryLimitDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.dto.dsl.template.DslTemplateConditionDTO;
import com.didichuxing.datachannel.arius.admin.common.bean.po.dsl.DslTemplatePO;
import com.didichuxing.datachannel.arius.admin.common.bean.vo.template.DslTemplateVO;
import com.didichuxing.datachannel.arius.admin.common.component.BaseHandle;
import com.didichuxing.datachannel.arius.admin.common.constant.operaterecord.OperateTypeEnum;
import com.didichuxing.datachannel.arius.admin.common.exception.NotFindSubclassException;
import com.didichuxing.datachannel.arius.admin.common.util.ConvertUtil;
import com.didichuxing.datachannel.arius.admin.core.component.HandleFactory;
import com.didichuxing.datachannel.arius.admin.core.service.common.OperateRecordService;
import com.didichuxing.datachannel.arius.admin.metadata.service.DslTemplateService;
import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * @author cjm
 */
@Component
public class DslTemplateManagerImpl implements DslTemplateManager {

    private static final ILog    LOGGER = LogFactory.getLog(DslTemplateManagerImpl.class);

    @Autowired
    private DslTemplateService   dslTemplateService;
    @Autowired
    private OperateRecordService operateRecordService;


    @Autowired
    private HandleFactory        handleFactory;
    @Override
    public Result<Boolean> updateDslTemplateQueryLimit(Integer projectId,String operator,List<DslQueryLimitDTO> dslTemplateList) {
        Boolean succeed =  dslTemplateService.updateDslTemplateQueryLimit(dslTemplateList);
        if (Boolean.TRUE.equals(succeed)) {
            for (DslQueryLimitDTO entry : dslTemplateList) {
                operateRecordService.saveOperateRecordWithManualTrigger(
                        String.format("queryLimit %s->%s", entry.getDslTemplateMd5(), entry.getQueryLimit()), operator,
                        projectId, entry.getProjectIdDslTemplateMd5(),
                        OperateTypeEnum.QUERY_TEMPLATE_DSL_CURRENT_LIMIT_ADJUSTMENT);
            }

        }
        return Result.buildSucc();
    }

    @Override
    public Result<Boolean> changeDslTemplateStatus(Integer projectId,String operator, String dslTemplateMd5) {
        if (StringUtils.isEmpty(dslTemplateMd5)) {
            return Result.build(true);
        }
       Boolean succeed = dslTemplateService.updateDslTemplateStatus(projectId, dslTemplateMd5);
        if (Boolean.TRUE.equals(succeed)) {
            operateRecordService.saveOperateRecordWithManualTrigger("变更状态:" + dslTemplateMd5, operator, projectId,
                    dslTemplateMd5, OperateTypeEnum.QUERY_TEMPLATE_DISABLE);
        }
        return Result.build(succeed);
    }
    @Override
    public Result<DslTemplateVO> getDslTemplateDetail(Integer projectId, String dslTemplateMd5) {
        if (StringUtils.isEmpty(dslTemplateMd5)) {
            return Result.buildSucc();
        }
        DslTemplatePO dslTemplatePO = dslTemplateService.getDslTemplateDetail(projectId, dslTemplateMd5);
        return Result.buildSucc(ConvertUtil.obj2Obj(dslTemplatePO, DslTemplateVO.class));
    }

    @Override
    public PaginationResult<DslTemplateVO> getDslTemplatePage(Integer projectId,
                                                              DslTemplateConditionDTO queryDTO) throws NotFindSubclassException {
        BaseHandle baseHandle = handleFactory.getByHandlerNamePer(DSL_TEMPLATE.getPageSearchType());
        if (baseHandle instanceof DslTemplatePageSearchHandle) {
            DslTemplatePageSearchHandle handle = (DslTemplatePageSearchHandle) baseHandle;
            return handle.doPage(queryDTO, projectId);
        }

        LOGGER.warn(
            "class=DslTemplateManagerImpl||method=getDslTemplatePage||msg=failed to get the DslTemplatePageSearchHandle");

        return PaginationResult.buildFail("分页获取DSL查询模版信息失败");
    }

}