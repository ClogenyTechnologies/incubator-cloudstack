// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.agent.manager;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.PatternSyntaxException;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.HostStatsEntry;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.host.Host;
import com.cloud.resource.AgentResourceBase;
import com.cloud.resource.AgentRoutingResource;
import com.cloud.resource.AgentStorageResource;
import com.cloud.resource.ResourceManager;
import com.cloud.simulator.MockHost;
import com.cloud.simulator.MockHostVO;
import com.cloud.simulator.MockVMVO;
import com.cloud.simulator.dao.MockHostDao;
import com.cloud.simulator.dao.MockVMDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Inject;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;

@Local(value = { MockAgentManager.class })
public class MockAgentManagerImpl implements MockAgentManager {
	private static final Logger s_logger = Logger.getLogger(MockAgentManagerImpl.class);
	@Inject
	HostPodDao _podDao = null;
	@Inject
	MockHostDao _mockHostDao = null;
	@Inject
	MockVMDao _mockVmDao = null;
	@Inject
	SimulatorManager _simulatorMgr = null;
	@Inject
	AgentManager _agentMgr = null;
	@Inject
	MockStorageManager _storageMgr = null;
	@Inject
	ResourceManager _resourceMgr;
	private SecureRandom random;
	private Map<String, AgentResourceBase> _resources = new ConcurrentHashMap<String, AgentResourceBase>();
	private ThreadPoolExecutor _executor;

	private Pair<String, Long> getPodCidr(long podId, long dcId) {
		try {

			HashMap<Long, List<Object>> podMap = _podDao.getCurrentPodCidrSubnets(dcId, 0);
			List<Object> cidrPair = podMap.get(podId);
			String cidrAddress = (String) cidrPair.get(0);
			Long cidrSize = (Long) cidrPair.get(1);
			return new Pair<String, Long>(cidrAddress, cidrSize);
		} catch (PatternSyntaxException e) {
			s_logger.error("Exception while splitting pod cidr");
			return null;
		} catch (IndexOutOfBoundsException e) {
			s_logger.error("Invalid pod cidr. Please check");
			return null;
		}
	}

	private String getIpAddress(long instanceId, long dcId, long podId) {
		Pair<String, Long> cidr = this.getPodCidr(podId, dcId);
		return NetUtils.long2Ip(NetUtils.ip2Long(cidr.first()) + instanceId);
	}

	private String getMacAddress(long dcId, long podId, long clusterId, int instanceId) {
		return NetUtils.long2Mac((dcId << 40 + podId << 32 + clusterId << 24 + instanceId));
	}

	public synchronized int getNextAgentId(long cidrSize) {
		return random.nextInt((int) cidrSize);
	}

	@Override
	@DB
	public Map<AgentResourceBase, Map<String, String>> createServerResources(Map<String, Object> params) {

		Map<String, String> args = new HashMap<String, String>();
		Map<AgentResourceBase, Map<String, String>> newResources = new HashMap<AgentResourceBase, Map<String, String>>();
		AgentResourceBase agentResource;
		long cpuCore = Long.parseLong((String) params.get("cpucore"));
		long cpuSpeed = Long.parseLong((String) params.get("cpuspeed"));
		long memory = Long.parseLong((String) params.get("memory"));
		long localStorageSize = Long.parseLong((String) params.get("localstorage"));
		synchronized (this) {
			long dataCenterId = Long.parseLong((String) params.get("zone"));
			long podId = Long.parseLong((String) params.get("pod"));
			long clusterId = Long.parseLong((String) params.get("cluster"));
			long cidrSize = getPodCidr(podId, dataCenterId).second();

			int agentId = getNextAgentId(cidrSize);
			String ipAddress = getIpAddress(agentId, dataCenterId, podId);
			String macAddress = getMacAddress(dataCenterId, podId, clusterId, agentId);
			MockHostVO mockHost = new MockHostVO();
			mockHost.setDataCenterId(dataCenterId);
			mockHost.setPodId(podId);
			mockHost.setClusterId(clusterId);
			mockHost.setCapabilities("hvm");
			mockHost.setCpuCount(cpuCore);
			mockHost.setCpuSpeed(cpuSpeed);
			mockHost.setMemorySize(memory);
			String guid = UUID.randomUUID().toString();
			mockHost.setGuid(guid);
			mockHost.setName("SimulatedAgent." + guid);
			mockHost.setPrivateIpAddress(ipAddress);
			mockHost.setPublicIpAddress(ipAddress);
			mockHost.setStorageIpAddress(ipAddress);
			mockHost.setPrivateMacAddress(macAddress);
			mockHost.setPublicMacAddress(macAddress);
			mockHost.setStorageMacAddress(macAddress);
			mockHost.setVersion(this.getClass().getPackage().getImplementationVersion());
			mockHost.setResource("com.cloud.agent.AgentRoutingResource");

			Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				mockHost = _mockHostDao.persist(mockHost);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				s_logger.error("Error while configuring mock agent " + ex.getMessage());
				throw new CloudRuntimeException("Error configuring agent", ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}

			_storageMgr.getLocalStorage(guid, localStorageSize);

			agentResource = new AgentRoutingResource();
			if (agentResource != null) {
				try {
					params.put("guid", mockHost.getGuid());
					agentResource.start();
					agentResource.configure(mockHost.getName(), params);

					newResources.put(agentResource, args);
				} catch (ConfigurationException e) {
					s_logger.error("error while configuring server resource" + e.getMessage());
				}
			}
		}
		return newResources;
	}

	@Override
	public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
		try {
			random = SecureRandom.getInstance("SHA1PRNG");
			_executor = new ThreadPoolExecutor(1, 5, 1, TimeUnit.DAYS, new LinkedBlockingQueue<Runnable>(),
					new NamedThreadFactory("Simulator-Agent-Mgr"));
			// ComponentLocator locator = ComponentLocator.getCurrentLocator();
			// _simulatorMgr = (SimulatorManager)
			// locator.getComponent(SimulatorManager.Name);
		} catch (NoSuchAlgorithmException e) {
			s_logger.debug("Failed to initialize random:" + e.toString());
			return false;
		}
		return true;
	}

	@Override
	public boolean handleSystemVMStart(long vmId, String privateIpAddress, String privateMacAddress,
			String privateNetMask, long dcId, long podId, String name, String vmType, String url) {
		_executor.execute(new SystemVMHandler(vmId, privateIpAddress, privateMacAddress, privateNetMask, dcId, podId,
				name, vmType, _simulatorMgr, url));
		return true;
	}

	@Override
	public boolean handleSystemVMStop(long vmId) {
		_executor.execute(new SystemVMHandler(vmId));
		return true;
	}

	private class SystemVMHandler implements Runnable {
		private long vmId;
		private String privateIpAddress;
		private String privateMacAddress;
		private String privateNetMask;
		private long dcId;
		private long podId;
		private String guid;
		private String name;
		private String vmType;
		private SimulatorManager mgr;
		private String mode;
		private String url;

		public SystemVMHandler(long vmId, String privateIpAddress, String privateMacAddress, String privateNetMask,
				long dcId, long podId, String name, String vmType, SimulatorManager mgr, String url) {
			this.vmId = vmId;
			this.privateIpAddress = privateIpAddress;
			this.privateMacAddress = privateMacAddress;
			this.privateNetMask = privateNetMask;
			this.dcId = dcId;
			this.guid = "SystemVM-" + UUID.randomUUID().toString();
			this.name = name;
			this.vmType = vmType;
			this.mgr = mgr;
			this.mode = "Start";
			this.url = url;
			this.podId = podId;
		}

		public SystemVMHandler(long vmId) {
			this.vmId = vmId;
			this.mode = "Stop";
		}

		@Override
		@DB
		public void run() {

			Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				if (this.mode.equalsIgnoreCase("Stop")) {
					txn.start();
					MockHost host = _mockHostDao.findByVmId(this.vmId);
					if (host != null) {
						String guid = host.getGuid();
						if (guid != null) {
							AgentResourceBase res = _resources.get(guid);
							if (res != null) {
								res.stop();
								_resources.remove(guid);
							}
						}
					}
					txn.commit();
					return;
				}
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Unable to get host " + guid + " due to " + ex.getMessage(), ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}

			String resource = null;
			if (vmType.equalsIgnoreCase("secstorage")) {
				resource = "com.cloud.agent.AgentStorageResource";
			}
			MockHostVO mockHost = new MockHostVO();
			mockHost.setDataCenterId(this.dcId);
			mockHost.setPodId(this.podId);
			mockHost.setCpuCount(DEFAULT_HOST_CPU_CORES);
			mockHost.setCpuSpeed(DEFAULT_HOST_SPEED_MHZ);
			mockHost.setMemorySize(DEFAULT_HOST_MEM_SIZE);
			mockHost.setGuid(this.guid);
			mockHost.setName(name);
			mockHost.setPrivateIpAddress(this.privateIpAddress);
			mockHost.setPublicIpAddress(this.privateIpAddress);
			mockHost.setStorageIpAddress(this.privateIpAddress);
			mockHost.setPrivateMacAddress(this.privateMacAddress);
			mockHost.setPublicMacAddress(this.privateMacAddress);
			mockHost.setStorageMacAddress(this.privateMacAddress);
			mockHost.setVersion(this.getClass().getPackage().getImplementationVersion());
			mockHost.setResource(resource);
			mockHost.setVmId(vmId);
			Transaction simtxn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				simtxn.start();
				mockHost = _mockHostDao.persist(mockHost);
				simtxn.commit();
			} catch (Exception ex) {
				simtxn.rollback();
				throw new CloudRuntimeException("Unable to persist host " + mockHost.getGuid() + " due to "
						+ ex.getMessage(), ex);
			} finally {
				simtxn.close();
                simtxn = Transaction.open(Transaction.CLOUD_DB);
                simtxn.close();
			}

			if (vmType.equalsIgnoreCase("secstorage")) {
				AgentStorageResource storageResource = new AgentStorageResource();
				try {
					Map<String, Object> params = new HashMap<String, Object>();
					Map<String, String> details = new HashMap<String, String>();
					params.put("guid", this.guid);
					details.put("guid", this.guid);
					storageResource.configure("secondaryStorage", params);
					storageResource.start();
					// on the simulator the ssvm is as good as a direct
					// agent
					_resourceMgr.addHost(mockHost.getDataCenterId(), storageResource, Host.Type.SecondaryStorageVM,
							details);
					_resources.put(this.guid, storageResource);
				} catch (ConfigurationException e) {
					s_logger.debug("Failed to load secondary storage resource: " + e.toString());
					return;
				}
			}
		}
	}

	@Override
	public MockHost getHost(String guid) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockHost _host = _mockHostDao.findByGuid(guid);
			txn.commit();
			if (_host != null) {
				return _host;
			} else {
				s_logger.error("Host with guid " + guid + " was not found");
				return null;
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to get host " + guid + " due to " + ex.getMessage(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public GetHostStatsAnswer getHostStatistic(GetHostStatsCommand cmd) {
		String hostGuid = cmd.getHostGuid();
		MockHost host = null;
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			host = _mockHostDao.findByGuid(hostGuid);
			txn.commit();
			if (host == null) {
				return null;
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to get host " + hostGuid + " due to " + ex.getMessage(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		Transaction vmtxn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			vmtxn.start();
			List<MockVMVO> vms = _mockVmDao.findByHostId(host.getId());
			vmtxn.commit();
			double usedMem = 0.0;
			double usedCpu = 0.0;
			for (MockVMVO vm : vms) {
				usedMem += vm.getMemory();
				usedCpu += vm.getCpu();
			}

			HostStatsEntry hostStats = new HostStatsEntry();
			hostStats.setTotalMemoryKBs(host.getMemorySize());
			hostStats.setFreeMemoryKBs(host.getMemorySize() - usedMem);
			hostStats.setNetworkReadKBs(32768);
			hostStats.setNetworkWriteKBs(16384);
			hostStats.setCpuUtilization(usedCpu / (host.getCpuCount() * host.getCpuSpeed()));
			hostStats.setEntityType("simulator-host");
			hostStats.setHostId(cmd.getHostId());
			return new GetHostStatsAnswer(cmd, hostStats);
		} catch (Exception ex) {
			vmtxn.rollback();
			throw new CloudRuntimeException("Unable to get Vms on host " + host.getGuid() + " due to "
					+ ex.getMessage(), ex);
		} finally {
			vmtxn.close();
            vmtxn = Transaction.open(Transaction.CLOUD_DB);
            vmtxn.close();
		}
	}

	@Override
	public Answer checkHealth(CheckHealthCommand cmd) {
		return new Answer(cmd);
	}

	@Override
	public Answer pingTest(PingTestCommand cmd) {
		return new Answer(cmd);
	}

	@Override
	public PrepareForMigrationAnswer prepareForMigrate(PrepareForMigrationCommand cmd) {
		VirtualMachineTO vm = cmd.getVirtualMachine();
		if (s_logger.isDebugEnabled()) {
			s_logger.debug("Preparing host for migrating " + vm);
		}
		return new PrepareForMigrationAnswer(cmd);
	}

	@Override
	public boolean start() {
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public MaintainAnswer maintain(com.cloud.agent.api.MaintainCommand cmd) {
		return new MaintainAnswer(cmd);
	}

	@Override
	public Answer checkNetworkCommand(CheckNetworkCommand cmd) {
		if (s_logger.isDebugEnabled()) {
			s_logger.debug("Checking if network name setup is done on the resource");
		}
		return new CheckNetworkAnswer(cmd, true, "Network Setup check by names is done");
	}
}
