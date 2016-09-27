package io.cattle.platform.core.dao;

import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.IpAddress;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;

import java.util.List;
import java.util.Map;

public interface HostDao {

    List<? extends Host> getHosts(Long accountId, List<String> uuids);

    List<? extends Host> getActiveHosts(Long accountId);

    Host getHostForIpAddress(long ipAddressId);

    IpAddress getIpAddressForHost(Long hostId);

    boolean isServiceSupportedOnHost(long hostId, long networkId, String serviceKind);

    Map<Long, List<Object>> getInstancesPerHost(List<Long> hosts, IdFormatter idFormatter);
}
