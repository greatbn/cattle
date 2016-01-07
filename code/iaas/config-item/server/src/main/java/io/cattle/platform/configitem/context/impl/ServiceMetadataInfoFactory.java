package io.cattle.platform.configitem.context.impl;

import static io.cattle.platform.core.model.tables.EnvironmentTable.ENVIRONMENT;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import io.cattle.platform.configitem.context.dao.MetaDataInfoDao;
import io.cattle.platform.configitem.context.dao.MetaDataInfoDao.Version;
import io.cattle.platform.configitem.context.data.metadata.common.ContainerMetaData;
import io.cattle.platform.configitem.context.data.metadata.common.DefaultMetaData;
import io.cattle.platform.configitem.context.data.metadata.common.HostMetaData;
import io.cattle.platform.configitem.context.data.metadata.common.SelfMetaData;
import io.cattle.platform.configitem.context.data.metadata.common.ServiceMetaData;
import io.cattle.platform.configitem.context.data.metadata.common.StackMetaData;
import io.cattle.platform.configitem.context.data.metadata.version1.ServiceMetaDataVersion1;
import io.cattle.platform.configitem.context.data.metadata.version1.StackMetaDataVersion1;
import io.cattle.platform.configitem.context.data.metadata.version2.ServiceMetaDataVersion2;
import io.cattle.platform.configitem.context.data.metadata.version2.StackMetaDataVersion2;
import io.cattle.platform.configitem.server.model.ConfigItem;
import io.cattle.platform.configitem.server.model.impl.ArchiveContext;
import io.cattle.platform.core.dao.GenericMapDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Environment;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.constants.ServiceDiscoveryConstants;
import io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao;
import io.cattle.platform.servicediscovery.api.util.ServiceDiscoveryUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

@Named
public class ServiceMetadataInfoFactory extends AbstractAgentBaseContextFactory {

    @Inject
    ServiceConsumeMapDao consumeMapDao;

    @Inject
    MetaDataInfoDao metaDataInfoDao;

    @Inject
    GenericMapDao mapDao;

    @Inject
    JsonMapper jsonMapper;

    @Override
    protected void populateContext(Agent agent, Instance instance, ConfigItem item, ArchiveContext context) {
        Account account = objectManager.loadResource(Account.class, instance.getAccountId());
        List<ContainerMetaData> containersMD = metaDataInfoDao.getContainersData(account.getId());
        Map<String, StackMetaData> stackNameToStack = new HashMap<>();
        Map<Long, Map<String, ServiceMetaData>> serviceIdToService = new HashMap<>();
        populateStacksServicesInfo(account, stackNameToStack, serviceIdToService);

        Map<String, Object> dataWithVersionTag = new HashMap<>();
        Map<String, Object> versionToData = new HashMap<>();
        for (MetaDataInfoDao.Version version : MetaDataInfoDao.Version.values()) {
            Object data = versionToData.get(version.getValue());
            if (data == null) {
                data = getFullMetaData(instance, context, containersMD, stackNameToStack, serviceIdToService,
                        version);
                versionToData.put(version.getValue(), data);
            }
            dataWithVersionTag.put(version.getTag(), data);
        }
        context.getData().put("data", generateYml(dataWithVersionTag));
    }

    protected String generateYml(Map<String, Object> dataWithVersion) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Representer representer = new Representer();
        representer.addClassTag(SelfMetaData.class, Tag.MAP);
        representer.addClassTag(DefaultMetaData.class, Tag.MAP);
        representer.addClassTag(ServiceMetaDataVersion1.class, Tag.MAP);
        representer.addClassTag(ServiceMetaDataVersion2.class, Tag.MAP);
        representer.addClassTag(StackMetaDataVersion1.class, Tag.MAP);
        representer.addClassTag(StackMetaDataVersion2.class, Tag.MAP);
        Yaml yaml = new Yaml(representer, options);
        String yamlStr = yaml.dump(dataWithVersion);
        return yamlStr;
    }

    protected Map<String, Object> getFullMetaData(Instance instance, ArchiveContext context,
            List<ContainerMetaData> containersMD, Map<String, StackMetaData> stackNameToStack,
            Map<Long, Map<String, ServiceMetaData>> serviceIdToService, Version version) {

        Map<String, SelfMetaData> selfMD = new HashMap<>();
        Map<Long, List<ContainerMetaData>> serviceIdToContainer = new HashMap<>();
        for (ContainerMetaData containerMD : containersMD) {
            ServiceMetaData svcData = null;
            StackMetaData stackData = null;
            if (containerMD.getServiceId() != null) {
                String configName = containerMD.getDnsPrefix();
                if (configName == null) {
                    configName = ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME;
                }
                Map<String, ServiceMetaData> svcsData = serviceIdToService.get(containerMD.getServiceId());
                if (svcsData != null) {
                    svcData = svcsData.get(configName);
                    containerMD.setStack_name(svcData.getStack_name());
                    containerMD.setService_name(svcData.getName());
                    List<ContainerMetaData> serviceContainers = serviceIdToContainer.get(containerMD.getServiceId());
                    if (serviceContainers == null) {
                        serviceContainers = new ArrayList<>();
                    }
                    serviceContainers.add(containerMD);
                    serviceIdToContainer.put(containerMD.getServiceId(), serviceContainers);
                    stackData = stackNameToStack.get(svcData.getStack_name());
                }
            }
            addToSelf(selfMD, containerMD, svcData, stackData, getInstanceHostId(instance));
        }

        List<ServiceMetaData> servicesMD = new ArrayList<>();
        for (Long serviceId : serviceIdToService.keySet()) {
            for (ServiceMetaData svcData : serviceIdToService.get(serviceId).values()) {
                setLinksInfo(serviceIdToService, svcData);
                if (serviceIdToContainer.get(serviceId) != null) {
                    svcData.setContainersObj(serviceIdToContainer.get(serviceId));
                }
                ServiceMetaData serviceMD = metaDataInfoDao.getServiceMetaData(svcData, version);
                servicesMD.add(serviceMD);
            }
        }

        List<StackMetaData> stacksMD = new ArrayList<>();
        for (StackMetaData stack : stackNameToStack.values()) {
            stacksMD.add(metaDataInfoDao.getStackMetaData(stack, version));
        }

        List<HostMetaData> hostsMD = metaDataInfoDao.getInstanceHostMetaData(instance.getAccountId(), null);
        List<HostMetaData> selfHostMD = metaDataInfoDao.getInstanceHostMetaData(instance.getAccountId(),
                instance);

        Map<String, Object> fullData = new HashMap<>();
        fullData.putAll(selfMD);
        fullData.put("default", new DefaultMetaData(context.getVersion(), containersMD, servicesMD,
                stacksMD, hostsMD, selfHostMD.isEmpty() ? selfHostMD.get(0) : null));

        return fullData;
    }

    protected void populateStacksServicesInfo(Account account, Map<String, StackMetaData> stacksMD,
            Map<Long, Map<String, ServiceMetaData>> servicesMD) {
        List<? extends Environment> envs = objectManager.find(Environment.class, ENVIRONMENT.ACCOUNT_ID,
                account.getId(), ENVIRONMENT.REMOVED, null);
        for (Environment env : envs) {
            List<ServiceMetaData> stackServicesMD = getServicesInfo(env, account);
            StackMetaData stackMetaData = new StackMetaData(env, account, stackServicesMD);
            stacksMD.put(stackMetaData.getName(), stackMetaData);
            for (ServiceMetaData stackServiceMD : stackServicesMD) {
                Map<String, ServiceMetaData> launchConfigToSvcMap = servicesMD.get(stackServiceMD.getServiceId());
                if (launchConfigToSvcMap == null) {
                    launchConfigToSvcMap = new HashMap<>();
                }
                if (stackServiceMD.isPrimaryConfig()) {
                    launchConfigToSvcMap.put(ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME, stackServiceMD);
                } else {
                    launchConfigToSvcMap.put(stackServiceMD.getName(), stackServiceMD);
                }
                servicesMD.put(stackServiceMD.getServiceId(), launchConfigToSvcMap);
            }
        }
    }

    protected void addToSelf(Map<String, SelfMetaData> self, ContainerMetaData containerMD,
            ServiceMetaData serviceMD, StackMetaData stackMD, long hostId) {
        if (containerMD.getPrimary_ip() == null) {
            return;
        }

        if (containerMD.getHostMetaData() == null) {
            return;
        }

        if (containerMD.getHostMetaData().getHostId().equals(hostId)) {
            self.put(containerMD.getPrimary_ip(), new SelfMetaData(containerMD, serviceMD,
                    stackMD, containerMD.getHostMetaData()));
        }
    }


    protected List<ServiceMetaData> getServicesInfo(Environment env, Account account) {
        List<? extends Service> services = objectManager.find(Service.class, SERVICE.ENVIRONMENT_ID,
                env.getId(), SERVICE.REMOVED, null);
        List<ServiceMetaData> stackServicesMD = new ArrayList<>();
        Map<Long, Service> idToService = new HashMap<>();
        for (Service service : services) {
            List<ContainerMetaData> serviceContainersMD = new ArrayList<>();
            getServiceInfo(account, serviceContainersMD, env, stackServicesMD, idToService, service);
        }
        return stackServicesMD;
    }

    protected void getServiceInfo(Account account, List<ContainerMetaData> serviceContainersMD,
            Environment env, List<ServiceMetaData> stackServices, Map<Long, Service> idToService,
            Service service) {
        idToService.put(service.getId(), service);
        List<String> launchConfigNames = ServiceDiscoveryUtil.getServiceLaunchConfigNames(service);
        if (launchConfigNames.isEmpty()) {
            launchConfigNames.add(ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME);
        }
        for (String launchConfigName : launchConfigNames) {
            List<ContainerMetaData> lanchConfigContainersMD = new ArrayList<>();
            getLaunchConfigInfo(account, lanchConfigContainersMD, env, stackServices, idToService, service,
                    launchConfigNames, launchConfigName);
            serviceContainersMD.addAll(lanchConfigContainersMD);
        }
    }

    @SuppressWarnings("unchecked")
    protected void getLaunchConfigInfo(Account account, List<ContainerMetaData> lanchConfigContainersMD,
            Environment env, List<ServiceMetaData> stackServices, Map<Long, Service> idToService,
            Service service, List<String> launchConfigNames, String launchConfigName) {
        boolean isPrimaryConfig = launchConfigName
                .equalsIgnoreCase(ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME);
        String serviceName = isPrimaryConfig ? service.getName()
                : launchConfigName;
        List<String> sidekicks = new ArrayList<>();
        
        if (isPrimaryConfig) {
            getSidekicksInfo(service, sidekicks, launchConfigNames);
        }
        Map<String, Object> metadata = DataAccessor.fields(service).withKey(ServiceDiscoveryConstants.FIELD_METADATA)
                .withDefault(Collections.EMPTY_MAP).as(Map.class);
        ServiceMetaData svcMetaData = new ServiceMetaData(service, serviceName, env, sidekicks, metadata);
        stackServices.add(svcMetaData);
    }

    protected void getSidekicksInfo(Service service, List<String> sidekicks, List<String> launchConfigNames) {
        for (String launchConfigName : launchConfigNames) {
            if (!launchConfigName.equalsIgnoreCase(ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME)) {
                sidekicks.add(launchConfigName);
            }
        }
    }

    protected void setLinksInfo(Map<Long, Map<String, ServiceMetaData>> services,
            ServiceMetaData serviceMD) {
        Map<String, String> links = new HashMap<>();
        List<? extends ServiceConsumeMap> consumedMaps = consumeMapDao.findConsumedServices(serviceMD.getServiceId());
        for (ServiceConsumeMap consumedMap : consumedMaps) {
            Service service = objectManager.loadResource(Service.class, consumedMap.getConsumedServiceId());
            Map<String, ServiceMetaData> consumedService = services.get(service.getId());
            if (consumedService == null) {
                continue;
            }
            ServiceMetaData consumedServiceData = consumedService.get(
                    serviceMD.getLaunchConfigName());
            String linkAlias = ServiceDiscoveryUtil.getDnsName(service, consumedMap, null, false);
            if (consumedServiceData != null) {
                links.put(
                        consumedServiceData.getStack_name() + "/" + consumedServiceData.getName(), linkAlias);
            }
        }
        serviceMD.setLinks(links);
    }

    protected long getInstanceHostId(Instance instance) {
        List<? extends InstanceHostMap> maps = mapDao.findNonRemoved(InstanceHostMap.class, Instance.class,
                instance.getId());
        if (!maps.isEmpty()) {
            return maps.get(0).getHostId();
        }
        return 0;
    }

}
