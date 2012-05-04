package edu.uwo.csd.dcsim2;

import java.util.ArrayList;

import org.apache.log4j.Logger;

import edu.uwo.csd.dcsim2.application.Application;
import edu.uwo.csd.dcsim2.application.workload.Workload;
import edu.uwo.csd.dcsim2.core.Simulation;
import edu.uwo.csd.dcsim2.core.SimulationEventListener;
import edu.uwo.csd.dcsim2.core.Utility;
import edu.uwo.csd.dcsim2.host.Host;
import edu.uwo.csd.dcsim2.host.scheduler.MasterCpuScheduler;
import edu.uwo.csd.dcsim2.management.action.MigrationAction;
import edu.uwo.csd.dcsim2.management.action.ReplicateAction;
import edu.uwo.csd.dcsim2.management.action.ShutdownVmAction;

public class DataCentreSimulation extends Simulation {

	private static Logger logger = Logger.getLogger(DataCentreSimulation.class);
	
	private ArrayList<DataCentre> datacentres = new ArrayList<DataCentre>();

	public void addDatacentre(DataCentre dc) {
		datacentres.add(dc);
	}
	
	@Override
	public void beginSimulation() {
		logger.info("Starting DCSim2");
		
		logger.info("Random Seed: " + Utility.getRandomSeed());
	}

	@Override
	public void updateSimulation(long simulationTime) {
		//update workloads
		Workload.updateAllWorkloads();
		
		//schedule cpu
		MasterCpuScheduler.getMasterCpuScheduler().scheduleCpu();
		
		for (DataCentre dc : datacentres) {
			if (this.isRecordingMetrics())
				dc.updateMetrics();
			dc.logInfo();
		}
		
		if (this.isRecordingMetrics())
			Host.updateGlobalMetrics(this);
		
		//finalize workloads (print logs, calculate stats)
		//Workload.logAllWorkloads();
	}

	@Override
	public void completeSimulation(long duration) {
		logger.info("DCSim2 Simulation Complete");
		
		double simTime = this.getDuration();
		double recordedTime = this.getRecordingDuration();
		String simUnits = "ms";
		if (simTime >= 864000000) { //>= 10 days
			simTime = simTime / 86400000;
			recordedTime = recordedTime / 86400000;
			simUnits = " days";
		} else if (simTime >= 7200000) { //>= 2 hours
			simTime = simTime / 3600000;
			recordedTime = recordedTime / 3600000;
			simUnits = "hrs";
		} else if (simTime >= 600000) { //>= 2 minutes
			simTime = simTime / 60000d;
			recordedTime = recordedTime / 60000d;
			simUnits = "mins";
		} else if (simTime >= 10000) { //>= 10 seconds
			simTime = simTime / 1000d;
			recordedTime = recordedTime / 1000d;
			simUnits = "s";
		}
		logger.info("Simulation Time: " + simTime + simUnits);
		logger.info("Recorded Time: " + recordedTime + simUnits);
		
		logger.info("Total Power [" + Utility.roundDouble((Host.getGlobalPowerConsumed() / 3600000d), 3) + "kWh]");
		logger.info("Average CPU Utilization [" + Utility.roundDouble((Host.getGlobalAverageUtilization() * 100), 3) + "]");
		logger.info("Host-Hours [" + Utility.roundDouble((Host.getGlobalTimeActive() / 3600000d), 3) + "]");
		logger.info("Average Hosts [" + Utility.roundDouble(((double)Host.getGlobalTimeActive() / (double)this.getRecordingDuration()), 3) + "]");
		logger.info("Min Hosts [" + Host.getMinActiveHosts() + "]");
		logger.info("Max Hosts [" + Host.getMaxActiveHosts() + "]");
		
		double underProvision;
		underProvision = (Application.getGlobalResourceDemand().getCpu() - Application.getGlobalResourceUsed().getCpu()) / Application.getGlobalResourceDemand().getCpu();
		logger.info("CPU Underprovision [" + Utility.roundDouble((underProvision * 100), 3) + "%]");
//		underProvision = (Application.getGlobalResourceDemand().getBandwidth() - Application.getGlobalResourceUsed().getBandwidth()) / Application.getGlobalResourceDemand().getBandwidth();
//		logger.info("BW Underprovision [" + Utility.roundDouble((underProvision * 100), 3) + "%]");
		
		for (SimulationEventListener simEntity : MigrationAction.getMigrationCount().keySet()) {
			logger.info(simEntity.getClass().getSimpleName() + " migrations: " + MigrationAction.getMigrationCount().get(simEntity));
		}
		
		for (SimulationEventListener simEntity : ReplicateAction.getReplicateCount().keySet()) {
			logger.info(simEntity.getClass().getSimpleName() + " replications: " + ReplicateAction.getReplicateCount().get(simEntity));
		}
		
		for (SimulationEventListener simEntity : ShutdownVmAction.getShutdownCount().keySet()) {
			logger.info(simEntity.getClass().getSimpleName() + " shutdown vm: " + ShutdownVmAction.getShutdownCount().get(simEntity));
		}
		
		//logger.info("Total Work [" + Utility.roundDouble(Workload.getGlobalCompletedWork(), 3) + "/" + Utility.roundDouble(Workload.getGlobalTotalWork(), 3) + "]"); //WARNING: this metric is only meaningful if each incoming work unit is identical!
		logger.info("Total Work Missed [" + Utility.roundDouble((1 - (Workload.getGlobalCompletedWork() / Workload.getGlobalTotalWork())) * 100, 3) + "%]"); //WARNING: this metric is only meaningful if each incoming work unit is identical!
		logger.info("SLA Violation: " + Application.getGlobalSLAViolation() + "%");
	
	}

}
