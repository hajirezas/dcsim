package edu.uwo.csd.dcsim2.application;

import edu.uwo.csd.dcsim2.core.Simulation;

public class StaticWorkload extends Workload {

	int workPerSecond;
	
	public StaticWorkload(int workPerSecond) {
		super();
		
		this.workPerSecond = workPerSecond;
	}

	@Override
	protected int retrievePendingWork(long lastUpdateTime) {
		return workPerSecond * (int)((Simulation.getSimulation().getSimulationTime() - lastUpdateTime) / 1000);
	}

	@Override
	protected long updateWorkLevel() {
		//do nothing, static workload never changes
		return 0;
	}

	

}
