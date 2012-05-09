package edu.uwo.csd.dcsim2.host;

import java.util.ArrayList;
import java.util.HashSet;

import org.apache.log4j.Logger;

import edu.uwo.csd.dcsim2.application.*;
import edu.uwo.csd.dcsim2.core.*;
import edu.uwo.csd.dcsim2.core.metrics.AggregateMetric;
import edu.uwo.csd.dcsim2.core.metrics.AverageMetric;
import edu.uwo.csd.dcsim2.core.metrics.MaxMetric;
import edu.uwo.csd.dcsim2.core.metrics.MinMetric;
import edu.uwo.csd.dcsim2.core.metrics.WeightedAverageMetric;
import edu.uwo.csd.dcsim2.host.resourcemanager.*;
import edu.uwo.csd.dcsim2.host.scheduler.CpuScheduler;
import edu.uwo.csd.dcsim2.vm.*;
import edu.uwo.csd.dcsim2.host.power.*;

public class Host implements SimulationEventListener {

	private static Logger logger = Logger.getLogger(Host.class);
	
	/**
	 * Event types for events that Host receives
	 */
	public static final int HOST_SUBMIT_VM_EVENT = 1;
	
	public static final int HOST_POWER_ON_EVENT = 2;
	public static final int HOST_POWER_OFF_EVENT = 3;
	public static final int HOST_SUSPEND_EVENT = 4;
	public static final int HOST_COMPLETE_POWER_ON_EVENT = 5;
	public static final int HOST_COMPLETE_SUSPEND_EVENT = 6;
	public static final int HOST_COMPLETE_POWER_OFF_EVENT = 7;
	
	public static final int HOST_MIGRATE_EVENT = 8;
	public static final int HOST_MIGRATE_COMPLETE_EVENT = 9;
	
	public static final String AVERAGE_ACTIVE_METRIC = "avgActiveHosts";
	public static final String MIN_ACTIVE_METRIC = "minActiveHosts";
	public static final String MAX_ACTIVE_METRIC = "maxActiveHosts";
	public static final String POWER_CONSUMED_METRIC = "powerConsumed";
	public static final String AVERAGE_UTILIZATION_METRIC = "avgHostUtil";
	public static final String HOST_TIME_METRIC = "hostTime";
	
	private static int nextId = 1;
	
	private Simulation simulation;
	
	private int id;
	private ArrayList<Cpu> cpus;
	private int memory;	//in MB
	private int bandwidth; //in KB
	private long storage; //in MB
	
	private CpuManager cpuManager;
	private MemoryManager memoryManager;
	private BandwidthManager bandwidthManager;
	private StorageManager storageManager;
	private CpuScheduler cpuScheduler;
	private HostPowerModel powerModel;
	
	private ArrayList<VMAllocation> vmAllocations = new ArrayList<VMAllocation>();
	private VMAllocation privDomainAllocation;
	private ArrayList<VMAllocation> migratingIn = new ArrayList<VMAllocation>();
	private ArrayList<VMAllocation> migratingOut = new ArrayList<VMAllocation>();
	private HashSet<VMAllocation> pendingOutgoingMigrations = new HashSet<VMAllocation>();
	
	public enum HostState {ON, SUSPENDED, OFF, POWERING_ON, SUSPENDING, POWERING_OFF, FAILED;}
	private ArrayList<Event> powerOnEventQueue = new ArrayList<Event>();
	private boolean powerOffAfterMigrations = false;
	
	/*
	 * Simulation metrics
	 */
	private long timeActive = 0; //time this host has spent active (ON)
	private double utilizationSum = 0; //used to calculate average utilization
	private double powerConsumed = 0; //total power consumed by the host
	
	private static long currentActiveHosts = 0;
	private static long minActiveHosts = Long.MAX_VALUE;
	private static long maxActiveHosts = 0;
	
	private HostState state;
	
	public Host(Simulation simulation, int nCpu, int nCores, int coreCapacity, int memory, int bandwidth, long storage,
			CpuManager cpuManager, 
			MemoryManager memoryManager, 
			BandwidthManager bandwidthManager,
			StorageManager storageManager, 
			CpuScheduler cpuScheduler,
			HostPowerModel powerModel) {
		
		this(simulation, nCpu, nCores, coreCapacity, memory, bandwidth, storage,
			cpuManager, 
			memoryManager, 
			bandwidthManager,
			storageManager, 
			cpuScheduler,
			powerModel,
			new VmmApplicationFactory());
		
	}
	
	public Host(Simulation simulation, int nCpu, int nCores, int coreCapacity, int memory, int bandwidth, long storage,
			CpuManager cpuManager, 
			MemoryManager memoryManager, 
			BandwidthManager bandwidthManager,
			StorageManager storageManager, 
			CpuScheduler cpuScheduler,
			HostPowerModel powerModel,
			ApplicationFactory vmmApplicationFactory) {
		
		this.simulation = simulation;
		
		cpus = new ArrayList<Cpu>();
		for (int i = 0; i < nCpu; ++i) {
			cpus.add(new Cpu(nCores, coreCapacity));
		}

		this.id = nextId++;
		this.memory = memory;
		this.bandwidth = bandwidth;
		this.storage = storage;
		
		setCpuManager(cpuManager);
		setMemoryManager(memoryManager);
		setBandwidthManager(bandwidthManager);
		setStorageManager(storageManager);
		setCpuScheduler(cpuScheduler);
		setHostPowerModel(powerModel);
					
		/*
		 * Create and allocate privileged domain
		 */
		
		//description allows privileged domain to use any or all of the resources of the host
		VMDescription privDomainDescription = new VMDescription(getCoreCount(), getMaxCoreCapacity(), 0, bandwidth, 0, vmmApplicationFactory);
		
		//create the allocation
		privDomainAllocation = new VMAllocation(privDomainDescription, this);
		
		//request allocations from resource managers. Each manager determines how much resource to allocate
		cpuManager.allocatePrivDomain(privDomainAllocation);
		memoryManager.allocatePrivDomain(privDomainAllocation);
		bandwidthManager.allocatePrivDomain(privDomainAllocation);
		storageManager.allocatePrivDomain(privDomainAllocation);

		PrivDomainVM privVM = new PrivDomainVM(simulation, privDomainDescription, privDomainDescription.getApplicationFactory().createApplication(simulation));
		privDomainAllocation.attachVm(privVM);

		//set default state
		state = HostState.ON;
	}
	
	@Override
	public void handleEvent(Event e) {
		
		/**if the Host is in the process of powering on, queue any received events. This effectively
		 * simulates the event sender retrying until the host has powered on, in a simplified fashion.
		 */
		if (state == Host.HostState.POWERING_ON && e.getType() != HOST_COMPLETE_POWER_ON_EVENT) {
			powerOnEventQueue.add(e);
			
			if (e.getType() == Host.HOST_MIGRATE_EVENT) {
				Host source = (Host)e.getData().get("source");
				VM vm = (VM)e.getData().get("vm");
				source.markVmForMigration(vm);
			}
			
			return;
		}
		
		VMAllocationRequest vmAllocationRequest;
		VMAllocation vmAllocation;
		VM vm;
		Host source;
		
		switch (e.getType()) {
			case Host.HOST_SUBMIT_VM_EVENT:
				vmAllocationRequest = (VMAllocationRequest)e.getData().get("vmAllocationRequest");
				submitVM(vmAllocationRequest);
				break;
			case Host.HOST_POWER_ON_EVENT:
				powerOn();
				break;
			case Host.HOST_COMPLETE_POWER_ON_EVENT:
				completePowerOn();
				break;
			case Host.HOST_POWER_OFF_EVENT:
				powerOff();
				break;
			case Host.HOST_COMPLETE_POWER_OFF_EVENT:
				completePowerOff();
				break;
			case Host.HOST_SUSPEND_EVENT:
				suspend();
				break;
			case Host.HOST_COMPLETE_SUSPEND_EVENT:
				completeSuspend();
				break;
			case Host.HOST_MIGRATE_EVENT:
				vmAllocationRequest = (VMAllocationRequest)e.getData().get("vmAllocationRequest");
				vm = (VM)e.getData().get("vm");
				source = (Host)e.getData().get("source");
				this.migrateIn(vmAllocationRequest, vm, source);
				break;
			case Host.HOST_MIGRATE_COMPLETE_EVENT:
				vmAllocation = (VMAllocation)e.getData().get("vmAllocation");
				vm = (VM)e.getData().get("vm");
				source = (Host)e.getData().get("source");
				this.completeMigrationIn(vmAllocation, vm, source);
				break;
			default:
				throw new RuntimeException("Host #" + getId() + " received unknown event type "+ e.getType());
		}
	}
	
	/*
	 * Host info
	 */
	
	public double getCurrentPowerConsumption() {
		return powerModel.getPowerConsumption(this);
	}
	

	
	/*
	 * VM Allocation
	 */
	
	public void submitVM(VMAllocationRequest vmAllocationRequest) {
		
		VMAllocation newAllocation;
		
		//create new allocation & allocate it resources
		try {
			newAllocation = allocate(vmAllocationRequest);
		} catch (AllocationFailedException e) {
			throw new RuntimeException("Could not allocate submitted VM", e);
		}
		
		//add the allocation to the Host list of allocations
		vmAllocations.add(newAllocation);
		
		//create a new VM in the allocation
		VM newVm = newAllocation.getVMDescription().createVM(simulation);
		newAllocation.setVm(newVm);
		newVm.setVMAllocation(newAllocation);
		
		logger.debug("Host #" + this.getId() + " allocated & created VM #" + newAllocation.getVm().getId());
	}

	
	public boolean isCapable(VMDescription vmDescription) {
		return cpuManager.isCapable(vmDescription) && 
				memoryManager.isCapable(vmDescription) &&	
				bandwidthManager.isCapable(vmDescription) && 
				storageManager.isCapable(vmDescription);
	}
	
	public boolean hasCapacity(VMAllocationRequest vmAllocationRequest) {
		return cpuManager.hasCapacity(vmAllocationRequest) &&
				memoryManager.hasCapacity(vmAllocationRequest) &&
				bandwidthManager.hasCapacity(vmAllocationRequest) &&
				storageManager.hasCapacity(vmAllocationRequest);
	}
	
	public boolean hasCapacity(ArrayList<VMAllocationRequest> vmAllocationRequests) {
		return cpuManager.hasCapacity(vmAllocationRequests) &&
				memoryManager.hasCapacity(vmAllocationRequests) &&
				bandwidthManager.hasCapacity(vmAllocationRequests) &&
				storageManager.hasCapacity(vmAllocationRequests);
	}
	
	public VMAllocation allocate(VMAllocationRequest vmAllocationRequest) throws AllocationFailedException {
		VMAllocation vmAllocation = new VMAllocation(vmAllocationRequest.getVMDescription(), this);
		
		//allocate CPU
		if (!cpuManager.allocateResource(vmAllocationRequest, vmAllocation))
			throw new AllocationFailedException("Allocation on host #" + getId() + " failed on CPU");
		
		//allocate memory
		if (!memoryManager.allocateResource(vmAllocationRequest, vmAllocation))
			throw new AllocationFailedException("Allocation on host #" + getId() + " failed on memory");
		
		//allocate bandwidth
		if (!bandwidthManager.allocateResource(vmAllocationRequest, vmAllocation))
			throw new AllocationFailedException("Allocation on host #" + getId() + " failed on bandwidth");
		
		//allocate storage
		if (!storageManager.allocateResource(vmAllocationRequest, vmAllocation))
			throw new AllocationFailedException("Allocation on host #" + getId() + " failed on storage");
		
		return vmAllocation;
	}
	
	public void deallocate(VMAllocation vmAllocation) {
		cpuManager.deallocateResource(vmAllocation);
		memoryManager.deallocateResource(vmAllocation);
		bandwidthManager.deallocateResource(vmAllocation);
		storageManager.deallocateResource(vmAllocation);
		
		vmAllocations.remove(vmAllocation);
	}
	
	/*
	 * MIGRATION
	 */
	
	/**
	 * A helper function which creates a migration event and send it to this host. To be called by another host or management entity
	 * that wishes to migrate a VM to this host.
	 * @param vmAllocationRequest
	 * @param vm
	 * @param source The host running the VM to be migrated. Note that this may be different than the Event source, since a third entity may trigger the migration.
	 */
	public void sendMigrationEvent(VMAllocationRequest vmAllocationRequest, VM vm, Host source) {
		Event e = new Event(Host.HOST_MIGRATE_EVENT, 
				simulation.getSimulationTime(),
				source, 
				this);
		e.getData().put("vmAllocationRequest", vmAllocationRequest);
		e.getData().put("vm", vm);
		e.getData().put("source", source);
		simulation.sendEvent(e);
	}
	
	public void markVmForMigration(VM vm) {
		if (!vmAllocations.contains(vm.getVMAllocation()))
				throw new RuntimeException("Attempted to mark VM #" + vm.getId() +" for migration from Host #" + getId() + 
						" but it resides on Host #" + vm.getVMAllocation().getHost().getId());
		
		pendingOutgoingMigrations.add(vm.getVMAllocation());
	}
	
	public boolean isMigrating(VM vm) {
		return migratingOut.contains(vm.getVMAllocation());
	}
	
	public boolean isPendingMigration(VM vm) {
		return pendingOutgoingMigrations.contains(vm.getVMAllocation());
	}
	
	/**
	 * Triggered when a migration event is received.
	 * @param vmAllocationRequest
	 * @param vm
	 * @param source
	 */
	private void migrateIn(VMAllocationRequest vmAllocationRequest, VM vm, Host source) {
		
		//verify source
		if (vm.getVMAllocation().getHost() != source)
			throw new RuntimeException("Migration failed: Source (host #" + source.getId() + ") does not match VM (#" + 
					vm.getId() + ") location (host #" + 
					vm.getVMAllocation().getHost().getId() + ").");
		
		//create new allocation & allocate it resources
		
		VMAllocation newAllocation;
		try {
			newAllocation = allocate(vmAllocationRequest);
		} catch (AllocationFailedException e) {
			throw new RuntimeException("Allocation failed on Host # " + this.getId() + 
					" for migrating in VM #" + vm.getId(), e);
		}
		
		//add the allocation to the Host list of allocations
		vmAllocations.add(newAllocation);
		
		//add the allocation to migratingIn list
		migratingIn.add(newAllocation);
		
		//add to VMM
		VmmApplication vmm = (VmmApplication)privDomainAllocation.getVm().getApplication();
		vmm.addMigratingVm(vm);	
		
		//inform the source host that the VM is migrating out
		source.migrateOut(vm);
		
		logger.debug("Host #" + this.getId() + " allocated for incoming VM #" + vm.getId());
		
		//compute time to migrate as (memory / bandwidth) * 1000 (seconds to ms), using privileged domain bandwidth TODO is this correct?
		//long timeToMigrate = (long)Math.ceil((((double)vm.getResourcesInUse().getMemory() * 1024) / (double)privDomainAllocation.getBandwidth()) * 1000);
		if (privDomainAllocation.getBandwidth() == 0)
			throw new RuntimeException("Privileged Domain has no bandwidth available for migration");
		
		//for now, assume 1/4 of bandwidth available to VMM is used for each migration... TODO calculate this properly!
		long timeToMigrate = (long)Math.ceil((((double)vm.getResourcesInUse().getMemory() * 1024) / ((double)privDomainAllocation.getBandwidth() / 4)) * 1000);

		//send migration completion message
		Event e = new Event(Host.HOST_MIGRATE_COMPLETE_EVENT,
				simulation.getSimulationTime() + timeToMigrate,
				this, this);
		e.getData().put("vmAllocation", newAllocation);
		e.getData().put("vm", vm);
		e.getData().put("source", source);
		simulation.sendEvent(e);
	}
	
	public void migrateOut(VM vm) {
		//get the allocation for this vm
		VMAllocation vmAllocation = vm.getVMAllocation();
		
		if (migratingOut.contains(vmAllocation))
			throw new RuntimeException("Migrate out failed: VM #" + vm.getId() + " is already migrating out of Host #" + getId() + ".");
		
		migratingOut.add(vmAllocation);
		
		if (isPendingMigration(vm))
			pendingOutgoingMigrations.remove(vm);
		
		//add to VMM
		VmmApplication vmm = (VmmApplication)privDomainAllocation.getVm().getApplication();
		vmm.addMigratingVm(vm);
		
		logger.debug("Host #" + this.getId() + " migrating out VM #" + vm.getId());
	}
	
	/**
	 * Triggers when a migration complete event is received, to complete a migration.
	 * @param vmAllocation
	 * @param vm
	 * @param source
	 */
	private void completeMigrationIn(VMAllocation vmAllocation, VM vm, Host source) {
		
		//first, inform the source host the the VM has completed migrating out
		source.completeMigrationOut(vm);
		
		//remove from migratingIn list
		migratingIn.remove(vmAllocation);
		
		//remove from VMM
		VmmApplication vmm = (VmmApplication)privDomainAllocation.getVm().getApplication();
		vmm.removeMigratingVm(vm);
		
		//attach VM to allocation
		vmAllocation.setVm(vm);
		vm.setVMAllocation(vmAllocation);
		
		logger.debug("Host #" + this.getId() + " completed migrating incoming VM #" + vm.getId());
		
	}
	
	public void completeMigrationOut(VM vm) {
		//get the allocation for this vm
		VMAllocation vmAllocation = vm.getVMAllocation();
		migratingOut.remove(vmAllocation);
		
		//add to VMM
		VmmApplication vmm = (VmmApplication)privDomainAllocation.getVm().getApplication();
		vmm.removeMigratingVm(vm);
		
		//deallocate the VM
		deallocate(vmAllocation);
				
		logger.debug("Host #" + this.getId() + " deallocated migrating out VM #" + vm.getId());
		
		if (powerOffAfterMigrations && migratingOut.isEmpty() && pendingOutgoingMigrations.isEmpty())
			powerOff();

	}
	
	/*
	 * HOST STATE OPERATIONS
	 */
	
	public void suspend() {
		if (state != HostState.SUSPENDED && state != HostState.SUSPENDING) {
			state = HostState.SUSPENDING;
			long delay = Long.parseLong(simulation.getProperty("hostSuspendDelay"));
			simulation.sendEvent(
					new Event(Host.HOST_COMPLETE_SUSPEND_EVENT,
							simulation.getSimulationTime() + delay,
							this, this));
		}
	}
	
	public void powerOff() {
		if (state != HostState.OFF && state != HostState.POWERING_OFF) {
			
			if (migratingOut.size() != 0) {
				//if migrations are in progress, power off after they are complete
				powerOffAfterMigrations = true;
			} else {
				state = HostState.POWERING_OFF;
				long delay = Long.parseLong(simulation.getProperty("hostPowerOffDelay"));
				simulation.sendEvent(
						new Event(Host.HOST_COMPLETE_POWER_OFF_EVENT,
								simulation.getSimulationTime() + delay,
								this, this));
				powerOffAfterMigrations = false;
			}
		}
	}
	
	public void powerOn() {
		if (state != HostState.ON && state != HostState.POWERING_ON) {
			
			long delay = 0;
			switch (state) {
				case SUSPENDED:
					delay = Long.parseLong(simulation.getProperty("hostPowerOnFromSuspendDelay"));
					break;
				case OFF:
					delay = Long.parseLong(simulation.getProperty("hostPowerOnFromOffDelay"));
					break;
				case FAILED:					
					delay = Long.parseLong(simulation.getProperty("hostPowerOnFromFailedDelay"));
					break;
				case POWERING_OFF:
					delay = Long.parseLong(simulation.getProperty("hostPowerOffDelay"));
					delay += Long.parseLong(simulation.getProperty("hostPowerOnFromOffDelay"));
					break;
				case SUSPENDING:
					delay = Long.parseLong(simulation.getProperty("hostSuspendDelay"));
					delay += Long.parseLong(simulation.getProperty("hostPowerOnFromSuspendDelay"));
					break;
			}
			
			simulation.sendEvent(
				new Event(Host.HOST_COMPLETE_POWER_ON_EVENT,
						simulation.getSimulationTime() + delay,
						this, this));
			
			state = HostState.POWERING_ON;
		}
	}
	
	private void completePowerOn() {
		
		if (state != HostState.ON) {
			state = HostState.ON;
			for (Event e : powerOnEventQueue) {
				handleEvent(e);
			}
			powerOnEventQueue.clear();
		}
	}
	
	private void completePowerOff() {
		state = HostState.OFF;
	}
	
	private void completeSuspend() {
		state = HostState.SUSPENDED;
	}
	
	public void fail() {
		state = HostState.FAILED;
	}
	
	//METRICS & LOGGING
	
	/*
	 * Output Host data to the log
	 */
	public void logInfo() {

		if (getCpuManager().getCpuUtilization() > 1)
			throw new RuntimeException("Host #" + getId() + " reporting CPU utilization of " + (getCpuManager().getCpuUtilization() * 100));
		
		if (state == HostState.ON) {
			logger.debug("Host #" + getId() + 
					" CPU[" + (int)Math.round(cpuManager.getCpuInUse()) + "/" + cpuManager.getAllocatedCpu() + "/" + cpuManager.getTotalCpu() + "] " +
					" BW[" + bandwidthManager.getAllocatedBandwidth() + "/" + bandwidthManager.getTotalBandwidth() + "] " +
					" MEM[" + memoryManager.getAllocatedMemory() + "/" + memoryManager.getTotalMemory() + "] " +
					" STORAGE[" + storageManager.getAllocatedStorage() + "/" + storageManager.getTotalStorage() + "] " +
					"Power[" + Utility.roundDouble(this.getCurrentPowerConsumption(), 2) + "W]");	
			privDomainAllocation.getVm().logInfo();
		} else {
			logger.debug("Host #" + getId() + " " + state);
		}
		
		for (VMAllocation vmAllocation : vmAllocations) {
			if (vmAllocation.getVm() != null) {
				vmAllocation.getVm().logInfo();
			} else {
				logger.debug("Empty Allocation CPU[" + vmAllocation.getCpu() + "]");
			}
		}
	}
	
	public void updateMetrics() {
		
		if (getCpuManager().getCpuUtilization() > 1)
			throw new RuntimeException("Host #" + getId() + " reporting CPU utilization of " + (getCpuManager().getCpuUtilization() * 100));
	
		if (getCpuManager().getCpuUtilization() < 0)
			throw new RuntimeException("Host #" + getId() + " reporting CPU utilization of " + (getCpuManager().getCpuUtilization() * 100));	
		
		
		if (state == HostState.ON) {
			
			
			//Collect Active Host metrics
			++currentActiveHosts;
			
			AverageMetric.getSimulationMetric(simulation, AVERAGE_ACTIVE_METRIC).incrementCounter();
			MinMetric.getSimulationMetric(simulation, MIN_ACTIVE_METRIC).incrementCounter();
			MaxMetric.getSimulationMetric(simulation, MAX_ACTIVE_METRIC).incrementCounter();
			
			//Collect active host time metric
			timeActive += simulation.getElapsedTime();
			
			AggregateMetric.getSimulationMetric(simulation, HOST_TIME_METRIC).addValue(simulation.getElapsedSeconds());

			//Collect average host utilization metric
			utilizationSum += getCpuManager().getCpuUtilization() * simulation.getElapsedTime();

			WeightedAverageMetric.getSimulationMetric(simulation, AVERAGE_UTILIZATION_METRIC).addValue(getCpuManager().getCpuUtilization(), simulation.getElapsedTime());
			
		}
		
		//Collect power consumed metric
		powerConsumed += getCurrentPowerConsumption() * simulation.getElapsedSeconds();
		
		AggregateMetric.getSimulationMetric(simulation, POWER_CONSUMED_METRIC).addValue(getCurrentPowerConsumption() * simulation.getElapsedSeconds());
		
		for (VMAllocation vmAllocation : vmAllocations) {
			if (vmAllocation.getVm() != null)
				vmAllocation.getVm().updateMetrics();
		}
	}
	
	public static void updateGlobalMetrics(Simulation simulation) {
		
		//Collect Active Host metrics
		AverageMetric.getSimulationMetric(simulation, AVERAGE_ACTIVE_METRIC).addCounterAndReset();
		MinMetric.getSimulationMetric(simulation, MIN_ACTIVE_METRIC).addCounterAndReset();
		MaxMetric.getSimulationMetric(simulation, MAX_ACTIVE_METRIC).addCounterAndReset();
		
		
		if (currentActiveHosts < minActiveHosts) {
			minActiveHosts = currentActiveHosts;
		}
		if (currentActiveHosts > maxActiveHosts) {
			maxActiveHosts = currentActiveHosts;
		}
		currentActiveHosts = 0;
	}

	//ACCESSOR & MUTATOR METHODS
	
	public int getId() {
		return id;
	}
	
	public ArrayList<Cpu> getCpus() {
		return cpus;
	}
	
	public int getMaxCoreCapacity() {
		int max = 0;
		for (Cpu cpu : cpus) {
			if (cpu.getCoreCapacity() > max) {
				max = cpu.getCoreCapacity();
			}
		}
		return max;
	}
	
	public int getCoreCount() {
		int cores = 0;
		for (Cpu cpu : cpus) {
			cores += cpu.getCores();
		}
		return cores;
	}
	
	public int getTotalCpu() {
		int total = 0;
		for (Cpu cpu : cpus)
			total += cpu.getCoreCapacity() * cpu.getCores();
		return total;
	}
	
	public int getMemory() {
		return memory;
	}
	
	public int getBandwidth() {
		return bandwidth;
	}
	
	public long getStorage() {
		return storage;
	}
	
	public HostState getState() {
		return state;
	}
	
	public void setState(HostState state) {
		this.state = state;
	}
	
	public CpuManager getCpuManager() {
		return cpuManager;
	}
	
	public void setCpuManager(CpuManager cpuManager) {
		this.cpuManager = cpuManager;
		cpuManager.setHost(this);
	}
	
	public MemoryManager getMemoryManager() {
		return memoryManager;
	}
	
	public void setMemoryManager(MemoryManager memoryManager) {
		this.memoryManager = memoryManager;
		memoryManager.setHost(this);
	}
	
	public BandwidthManager getBandwidthManager() {
		return bandwidthManager;
	}
	
	public void setBandwidthManager(BandwidthManager bandwidthManager) {
		this.bandwidthManager = bandwidthManager;
		bandwidthManager.setHost(this);
	}
	
	public StorageManager getStorageManager() {
		return storageManager;
	}
	
	public void setStorageManager(StorageManager storageManager) {
		this.storageManager = storageManager;
		storageManager.setHost(this);
	}
	
	public CpuScheduler getCpuScheduler() {
		return cpuScheduler;
	}
	
	public void setCpuScheduler(CpuScheduler cpuScheduler) {
		this.cpuScheduler = cpuScheduler;
		cpuScheduler.setHost(this);
	}
	
	public ArrayList<VMAllocation> getVMAllocations() {
		return vmAllocations;
	}
	
	public VMAllocation getPrivDomainAllocation() {
		return privDomainAllocation;
	}
	
	public ArrayList<VMAllocation> getMigratingIn() {
		return migratingIn;
	}
	
	public ArrayList<VMAllocation> getMigratingOut() {
		return migratingOut;
	}
	
	public HostPowerModel getPowerModel() {
		return powerModel;
	}
	
	public void setHostPowerModel(HostPowerModel powerModel) {
		this.powerModel = powerModel;
	}
	
	public long getTimeActive() {
		return timeActive;
	}
	
	public double getPowerConsumed() {
		return powerConsumed;
	}
	
	public double getAverageUtilization() {
		return utilizationSum / timeActive;
	}

}
