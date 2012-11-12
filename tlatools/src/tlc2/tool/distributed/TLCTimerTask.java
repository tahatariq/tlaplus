package tlc2.tool.distributed;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import tlc2.output.EC;
import tlc2.output.MP;
import tlc2.tool.distributed.TLCWorker.TLCWorkerRunnable;

/**
 * Periodically checks if the server is still alive and exits the worker otherwise
 */
public class TLCTimerTask extends TimerTask {
	private static final Logger LOGGER = Logger.getLogger(TLCTimerTask.class.getName());

	private static final int TIMEOUT = Integer.getInteger(TLCTimerTask.class.getName() + ".timeout", 60) * 1000;

	private final String serverUrl;
	private final TLCWorkerRunnable[] runnables;
	private final Timer timer;

	public TLCTimerTask(final Timer keepAliveTimer, final TLCWorkerRunnable[] runnables, final String anUrl) {
		this.timer = keepAliveTimer;
		this.runnables = runnables;
		this.serverUrl = anUrl;
	}

	/* (non-Javadoc)
	 * @see java.util.TimerTask#run()
	 */
	public void run() {
		if(noActivityWithin(TIMEOUT)) {
			try {
				final TLCServerRMI server = (TLCServerRMI) Naming.lookup(serverUrl);
				if(server.isDone()) {
					exitWorker(null);
				}
			} catch (MalformedURLException e) {
				// not expected to happen
				LOGGER.log(Level.FINEST, "Failed to exit worker", e);
			} catch (RemoteException e) {
				exitWorker(e);
			} catch (NotBoundException e) {
				exitWorker(e);
			}
		}
	}
	
	private boolean noActivityWithin(int timeout) {
		long lastInvocation = getMostRecentInvocation();
		if (lastInvocation == -1) {
			return false;
		} else {
			long now = new Date().getTime();
			return lastInvocation == 0 || (now - lastInvocation) > timeout;
		}
	}

	private long getMostRecentInvocation() {
		long minInvocation = 0L;
		for (int i = 0; i < runnables.length; i++) {
			TLCWorker tlcWorker = runnables[i].getTLCWorker();
			/*
			 * If one of the TLCworker threads is computing states, we assume
			 * the TLCMaster/TLCServer to still be alive. At least we definitely
			 * want to finish the computation first. Under the condition of a
			 * server crash, all workers will eventually converge into a
			 * non-computing state though. Then, lastInvocation is going to
			 * matter. The same is the case, when the master is slow to request
			 * states from workers.
			 */
			if (tlcWorker.isComputing()) {
				return -1L;
			}
			long lastInvocation = tlcWorker.getLastInvocation();
			minInvocation = Math.max(minInvocation, lastInvocation);
		}
		return minInvocation;
	}

	private void exitWorker(Throwable e) {
		if (e != null) {
			MP.printError(EC.TLC_DISTRIBUTED_SERVER_NOT_RUNNING, e);
		} else {
			MP.printError(EC.TLC_DISTRIBUTED_SERVER_FINISHED);
		}
		for (int i = 0; i < runnables.length; i++) {
			try {
				TLCWorkerRunnable runnable = runnables[i];
				runnable.getTLCWorker().exit();
			} catch (NoSuchObjectException ex) {
				// not expected to happen
				LOGGER.log(Level.FINEST, "Failed to exit worker", ex);
			}
		}
		// Cancel this time after having exited the worker. Otherwise we keep on
		// going forever.
		this.timer.cancel();
	}
}
