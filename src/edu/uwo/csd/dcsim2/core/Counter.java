package edu.uwo.csd.dcsim2.core;

public class Counter {

	private int val = 0;
	
	public int getValue() {
		return val;
	}
	
	public void increment() {
		++val;
	}
	
	public void add(int val){
		this.val += val;
	}
	
	public void reset() {
		val = 0;
	}
	
}
