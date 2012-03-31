package edu.uwo.csd.dcsim2.examples;

import java.util.*;

import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.Logger;

import edu.uwo.csd.dcsim2.DCSimUpdateController;
import edu.uwo.csd.dcsim2.DataCentre;
import edu.uwo.csd.dcsim2.application.WebServerTier;
import edu.uwo.csd.dcsim2.application.workload.TraceWorkload;
import edu.uwo.csd.dcsim2.application.workload.Workload;
import edu.uwo.csd.dcsim2.core.*;
import edu.uwo.csd.dcsim2.management.*;
import edu.uwo.csd.dcsim2.host.*;
import edu.uwo.csd.dcsim2.host.model.*;
import edu.uwo.csd.dcsim2.host.power.LinearHostPowerModel;
import edu.uwo.csd.dcsim2.host.resourcemanager.*;
import edu.uwo.csd.dcsim2.host.scheduler.*;
import edu.uwo.csd.dcsim2.vm.*;

public class StaticAllocationPeak {

	private static Logger logger = Logger.getLogger(StaticAllocationPeak.class);
	
	private static int nHosts = 100;
	
	public static void main(String args[]) {
		
		PropertyConfigurator.configure(Simulation.getConfigDirectory() +"/logger.properties"); //configure logging from file
		
		
		logger.info(StaticAllocationPeak.class.toString());
		
		//create datacentre
		VMPlacementPolicy vmPlacementPolicy = new VMPlacementPolicyFFD();
		DataCentre dc = new DataCentre(vmPlacementPolicy);
		
		Simulation.getInstance().setSimulationUpdateController(new DCSimUpdateController(dc));
		
		//create hosts
		ArrayList<Host> hostList = createHosts(nHosts);
		dc.addHosts(hostList);
		
		//create VMs
		ArrayList<VMAllocationRequest> vmList = new ArrayList<VMAllocationRequest>();
		for (int i = 0; i < 75; ++i) {
			vmList.add(new VMAllocationRequest(createVMDesc("traces/clarknet", (int)(Math.random() * 200000000))));
		}
		for (int i = 0; i < 75; ++i) {
			vmList.add(new VMAllocationRequest(createVMDesc("traces/epa", (int)(Math.random() * 40000000))));
		}
		for (int i = 0; i < 75; ++i) {
			vmList.add(new VMAllocationRequest(createVMDesc("traces/google_cores_job_type_0", (int)(Math.random() * 15000000))));
		}
		for (int i = 0; i < 75; ++i) {
			vmList.add(new VMAllocationRequest(createVMDesc("traces/google_cores_job_type_1", (int)(Math.random() * 15000000))));
		}
		Collections.shuffle(vmList);
		
		//submit VMs
		dc.getVMPlacementPolicy().submitVMs(vmList);
		
		//since this is a static run, turn off hosts with no VMs
		for (Host host : hostList) {
			if (host.getVMAllocations().size() == 0) {
				host.setState(Host.HostState.OFF);
			}
		}
		
		long startTime = System.currentTimeMillis();
		logger.info("Start time: " + startTime + "ms");
		
		//run the simulation
		//Simulation.getInstance().run(864000000, 86400000); //10 days, record metrics after 1 day
		Simulation.getInstance().run(864000000, 0);
		
		long endTime = System.currentTimeMillis();
		logger.info("End time: " + endTime + "ms. Elapsed: " + ((endTime - startTime) / 1000) + "s");
		
	}
	
	public static ArrayList<Host> createHosts(int nHosts) {
		
		ArrayList<Host> hosts = new ArrayList<Host>(nHosts);
		
		for (int i = 0; i < nHosts; ++i) {
			Host host = new ProLiantDL380G5QuadCoreHost(
					new StaticCpuManager(),
					new StaticMemoryManager(),
					new StaticBandwidthManager(),
					new StaticStorageManager(),
					new FairShareCpuScheduler());
			
			host.setHostPowerModel(new LinearHostPowerModel(250, 500)); //override default power model to match original DCSim experiments
			
			hosts.add(host);
		}
		
		return hosts;
	}
	
	public static VMDescription createVMDesc(String fileName, long offset) {
		
		//create workload (external)
		Workload workload = new TraceWorkload(fileName, 2700, offset); //scale of 2700 + 300 overhead = 1 core on ProLiantDL380G5QuadCoreHost
		
		//create single tier (web tier)
		WebServerTier webServerTier = new WebServerTier(256, 0, 1, 1, 300); //256MB RAM, 0MG Storage, 1 cpu per request, 1 bw per request, 300 cpu overhead
		webServerTier.setWorkTarget(workload);
		
		//set the tier as the target for the external workload
		workload.setWorkTarget(webServerTier);
		
		//build VMDescription
		int cores = 1; //requires 1 core
		int coreCapacity = 3000; //1 full core
		int memory = 1024;
		int bandwidth = 16384; //16MB = 16384KB
		long storage = 1024; //1GB
		VMDescription vmDescription = new VMDescription(cores, coreCapacity, memory, bandwidth, storage, webServerTier);

		return vmDescription;
	}
	
}