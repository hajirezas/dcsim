package edu.uwo.csd.dcsim2.core;

import java.util.Comparator;

public class EventComparator implements Comparator<Event> {

	@Override
	public int compare(Event e1, Event e2) {
		return (int)(e1.getTime() - e2.getTime());
	}

	
}
