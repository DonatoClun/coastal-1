package za.ac.sun.cs.coastal.observers;

import org.apache.logging.log4j.Logger;

import za.ac.sun.cs.coastal.Banner;
import za.ac.sun.cs.coastal.COASTAL;
import za.ac.sun.cs.coastal.messages.Broker;
import za.ac.sun.cs.coastal.messages.Tuple;

public class AssertControllerFactory implements ObserverFactory {

	public AssertControllerFactory(COASTAL coastal) {
	}

	public int getFrequency() {
		return ObserverFactory.ONCE_PER_RUN;
	}

	@Override
	public ObserverManager createManager(COASTAL coastal) {
		return new AssertManager(coastal);
	}

	@Override
	public Observer createObserver(COASTAL coastal, ObserverManager manager) {
		return null;
	}

	// ======================================================================
	//
	// MANAGER FOR STOP CONTROL
	//
	// ======================================================================

	private static class AssertManager implements ObserverManager {

		private final Logger log;

		private final COASTAL coastal;

		private final Broker broker;

		private boolean wasStopped = false;

		private String stopMessage = null;

		AssertManager(COASTAL coastal) {
			log = coastal.getLog();
			this.coastal = coastal;
			broker = coastal.getBroker();
			broker.subscribe("assert-failed", this::stop);
			broker.subscribe("coastal-stop", this::report);
		}
		
		public void report(Object object) {
			broker.publish("report", new Tuple("AssertController.assert-failed", wasStopped));
		}

		public void stop(Object object) {
			Tuple tuple = (Tuple) object;
			coastal.stopWork();
			stopMessage = (String) tuple.get(1);
			if (stopMessage == null) {
				new Banner('!').println("PROGRAM TERMINATION POINT REACHED").display(log);
			} else {
				new Banner('!').println("PROGRAM TERMINATION POINT REACHED").println(stopMessage).display(log);
			}
			wasStopped = true;
		}

		@Override
		public String getName() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getPropertyNames() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Object[] getPropertyValues() {
			// TODO Auto-generated method stub
			return null;
		}

	}

	@Override
	public int getFrequencyflags() {
		// TODO Auto-generated method stub
		return 0;
	}

}
