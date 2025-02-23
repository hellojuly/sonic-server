package org.cloud.sonic.controller.services.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.AopUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.common.config.WhiteUrl;
import org.cloud.sonic.controller.mapper.ResourcesMapper;
import org.cloud.sonic.controller.mapper.RoleResourcesMapper;
import org.cloud.sonic.controller.models.base.CommentPage;
import org.cloud.sonic.controller.models.base.TypeConverter;
import org.cloud.sonic.controller.models.domain.Resources;
import org.cloud.sonic.controller.models.domain.RoleResources;
import org.cloud.sonic.controller.models.dto.ResourcesDTO;
import org.cloud.sonic.controller.models.interfaces.UrlType;
import org.cloud.sonic.controller.services.ResourcesService;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.cloud.sonic.controller.tools.SpringTool;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Component
public class ResourcesServiceImpl extends SonicServiceImpl<ResourcesMapper, Resources> implements ResourcesService {

    @Resource
    private RoleResourcesMapper roleResourcesMapper;

    @Override
    @Transactional
    public void init() {
        RequestMappingHandlerMapping requestMappingHandlerMapping = SpringTool.getApplicationContext().getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> map = requestMappingHandlerMapping.getHandlerMethods();
        Map<String, Resources> parentMap = new HashMap<>();

        map.forEach((key, value) ->{
            String beanName = value.getBean().toString();
            Resources parentResource = parentMap.getOrDefault(beanName, processParent(beanName, parentMap)) ;
            if (parentResource == null) {
                return;
            }

            processResource(parentResource, key, value);
        });

    }

    private Resources processParent(String beanName, Map<String, Resources> parentMap) {
        Api api = SpringTool.getBean(beanName).getClass().getAnnotation(Api.class);
        if (api == null) {
            return null;
        }
        RequestMapping requestMapping = AopUtils.getTargetObject(SpringTool.getBean(beanName)).getClass().getAnnotation(RequestMapping.class);
        if (requestMapping == null) {
            return null;
        }
        boolean needInsert = false;
        String res = requestMapping.value()[0];
        Resources parentResource = searchByPath(res, UrlType.PARENT);
        if (parentResource == null) {
            parentResource = new Resources();
            parentResource.setNeedAuth(0);
            parentResource.setMethod("parent");
            parentMap.put(beanName, parentResource);
            needInsert = true;
        }
        String tag = api.tags()[0];
        parentResource.setDesc(tag);
        parentResource.setPath(res);

        if (needInsert) {
            insert(parentResource);
        }else {
            parentResource.setUpdateTime(null);
            updateById(parentResource);
        }

        return parentResource;
    }

    private void processResource(Resources parentResource, RequestMappingInfo key, HandlerMethod value) {

        String path = (String) key.getPatternsCondition().getPatterns().toArray()[0];
        String method = ((RequestMethod) key.getMethodsCondition().getMethods().toArray()[0]).toString();

        boolean needInsert = false;

        Resources resource = lambdaQuery().eq(Resources::getPath, path)
                .eq(Resources::getMethod, method)
                .last("limit 1")
                .one();

        if (resource == null) {
            resource = new Resources();
            //初始化说有资源不需要鉴权
            resource.setNeedAuth(0);
            needInsert = true;
        }
        resource.setParentId(parentResource.getId());
        resource.setMethod(method);
        resource.setPath(path);

        ApiOperation apiOperation = value.getMethodAnnotation(ApiOperation.class);
        WhiteUrl whiteUrl = value.getMethodAnnotation(WhiteUrl.class);
        if (apiOperation == null) {
            resource.setDesc("未设置");
        }else {
            resource.setDesc(apiOperation.value());
        }
        //标记相关资源加白
        resource.setWhite(whiteUrl == null ? UrlType.NORMAL : UrlType.WHITE);

        if (needInsert) {
            insert(resource);
        }else {
            resource.setUpdateTime(null);
            updateById(resource);
        }
    }

    @Override
    public Resources searchByPath(String path, Integer parentId) {

        return lambdaQuery().eq(Resources::getPath, path)
                .eq(Resources::getParentId, parentId)
                .orderByAsc(Resources::getId)
                .last("limit 1")
                .one();
    }

    @Override
    public Resources search(String path, String method) {
        return lambdaQuery().eq(Resources::getPath, path)
                .eq(Resources::getMethod, method)
                .gt(Resources::getParentId, UrlType.PARENT)
                .orderByAsc(Resources::getId)
                .last("limit 1")
                .one();
    }

    public int insert(Resources resources) {
        return getBaseMapper().insert(resources);
    }

    @Override
    public void updateResourceAuth(Integer id, Boolean needAuth) {
        lambdaUpdate().eq(Resources::getId, id)
                .set(Resources::getNeedAuth, needAuth ? 1 : 0)
                .update();
    }


    @Override
    public CommentPage<ResourcesDTO> listResource(Page<Resources> page, String path, boolean isAll) {
        if (isAll) {
            page.setSize(10000);
            page.setCurrent(1);
        }
        Page<Resources> resources = lambdaQuery()
                .gt(Resources::getParentId, UrlType.PARENT)
                .eq(Resources::getWhite, UrlType.NORMAL)
                .like(!StringUtils.isEmpty(path), Resources::getPath, path)
                .orderByDesc(Resources::getId)
                .page(page);
        List<ResourcesDTO> resourcesDTOList = resources.getRecords().stream()
                .map(TypeConverter::convertTo).collect(Collectors.toList());
        return CommentPage.convertFrom(page, resourcesDTOList);
    }

    private List<ResourcesDTO> listParentResource() {
        return lambdaQuery().eq(Resources::getParentId, UrlType.PARENT)
                .orderByDesc(Resources::getId)
                .list().stream()
                .map(TypeConverter::convertTo).collect(Collectors.toList());
    }

    @Override
    public List<ResourcesDTO> listRoleResource(Integer roleId) {
        CommentPage<ResourcesDTO> commentPage = listResource(new Page<>(),null, true);

        List<ResourcesDTO>  parentListResource = listParentResource();
        Map<Integer, ResourcesDTO> mapParent = parentListResource.stream().collect(Collectors.toMap(ResourcesDTO::getId, Function.identity() ,(a, b) -> a));

        List<RoleResources> roleResourcesList = lambdaQuery(roleResourcesMapper).eq(RoleResources::getRoleId, roleId).list();
        Map<Integer, RoleResources> map = null;
        if (!CollectionUtils.isEmpty(roleResourcesList)) {
            map = roleResourcesList.stream().collect(Collectors.toMap(RoleResources::getResId, Function.identity() ,(a, b) -> a));
        }

        Map<Integer, RoleResources> finalMap = map;
        commentPage.getContent().stream().forEach(a -> {
            //判断当前资源是否具有权限
            if (finalMap != null && finalMap.containsKey(a.getId())) {
                a.setHasAuth(true);
            }else {
                a.setHasAuth(false);
            }
            // 构建权限树
            ResourcesDTO parent = mapParent.get(a.getParentId());
            if (parent != null) {
                if (parent.getChild() == null) {
                    parent.setChild(new ArrayList<>());
                }
                parent.getChild().add(a);
            }
        });

        return parentListResource;
    }

}
