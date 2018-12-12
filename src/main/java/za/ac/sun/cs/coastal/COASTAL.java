package za.ac.sun.cs.coastal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ConfigurationUtils;
import org.apache.commons.configuration2.ImmutableConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.builder.BasicConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileHandler;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import za.ac.sun.cs.coastal.Conversion.ConfigCombiner;
import za.ac.sun.cs.coastal.Reporter.Reportable;
import za.ac.sun.cs.coastal.TaskFactory.TaskManager;
import za.ac.sun.cs.coastal.TaskFactory.Task;
import za.ac.sun.cs.coastal.diver.DiverFactory;
import za.ac.sun.cs.coastal.diver.DiverFactory.DiverManager;
import za.ac.sun.cs.coastal.diver.SegmentedPC;
import za.ac.sun.cs.coastal.diver.SymbolicState;
import za.ac.sun.cs.coastal.instrument.InstrumentationClassManager;
import za.ac.sun.cs.coastal.messages.Broker;
import za.ac.sun.cs.coastal.messages.Tuple;
import za.ac.sun.cs.coastal.observers.ObserverFactory;
import za.ac.sun.cs.coastal.observers.ObserverFactory.ObserverManager;
import za.ac.sun.cs.coastal.pathtree.PathTree;
import za.ac.sun.cs.coastal.strategy.StrategyFactory;
import za.ac.sun.cs.coastal.surfer.SurferFactory;
// import za.ac.sun.cs.coastal.surfer.SurferFactory.SurferManager;
import za.ac.sun.cs.coastal.surfer.Trace;
import za.ac.sun.cs.coastal.symbolic.Model;
import za.ac.sun.cs.green.expr.Constant;

/**
 * A COASTAL analysis run. The main function (or some outside client) constructs
 * an instance of this class to execute one analysis run of the system.
 */
public class COASTAL {

	/**
	 * The logger for this analysis run. This is not created but set by the
	 * outside world.
	 */
	private final Logger log;

	/**
	 * The configuration for this run.
	 */
	private final ImmutableConfiguration config;

	/**
	 * The single broker that will manage all messages for this analysis run.
	 */
	private final Broker broker;

	/**
	 * The single reporter that collects information about the analysis run to
	 * display at the end.
	 */
	private final Reporter reporter;

	/**
	 * The shared path tree for all strategies, divers, and surfers.
	 */
	private final PathTree pathTree;

	/**
	 * The manager of all classes loaded during the analysis run.
	 */
	private final InstrumentationClassManager classManager;

	// ======================================================================
	//
	// TARGET INFORMATION
	//
	// ======================================================================

	/**
	 * A list of all prefixes of classes that will be instrumented.
	 */
	private final List<String> prefixes = new ArrayList<>();

	/**
	 * A list of all triggers that switch on symbolic execution.
	 */
	private final List<Trigger> triggers = new ArrayList<>();

	/**
	 * Mapping from parameter names (of all triggers) to their types.
	 */
	private final Map<String, Class<?>> parameters = new HashMap<>();

	// ======================================================================
	//
	// VARIABLE BOUNDS
	//
	// ======================================================================

	/**
	 * The default minimum bound for various types.
	 */
	private final Map<Class<?>, Object> defaultMinBounds = new HashMap<>();

	/**
	 * The default maximum bound for various types.
	 */
	private final Map<Class<?>, Object> defaultMaxBounds = new HashMap<>();

	/**
	 * A map from variable names to their lower bounds.
	 */
	private final Map<String, Object> minBounds = new HashMap<>();

	/**
	 * A map from variable names to their lower bounds.
	 */
	private final Map<String, Object> maxBounds = new HashMap<>();

	// ======================================================================
	//
	// DIVERS, SURFERS, STRATEGIES
	//
	// ======================================================================

	public static class TaskInfo {

		private final TaskFactory factory;

		private final TaskManager manager;

		private final int initThreads;

		private final int minThreads;

		private final int maxThreads;

		private int threadCount = 0;

		TaskInfo(final COASTAL coastal, final TaskFactory factory, final int initThreads, final int minThreads,
				final int maxThreads) {
			this.factory = factory;
			this.manager = factory.createManager(coastal);
			this.initThreads = initThreads;
			this.minThreads = minThreads;
			this.maxThreads = maxThreads;
		}

		public Task create(COASTAL coastal) {
			threadCount++;
			return factory.createTask(coastal, manager);
		}

		public TaskManager getManager() {
			return manager;
		}

		public int getInitThreads() {
			return initThreads;
		}

		public int getMinThreads() {
			return minThreads;
		}

		public int getMaxThreads() {
			return maxThreads;
		}

		public int getThreadCount() {
			return threadCount;
		}

	}

	private final List<TaskInfo> tasks = new ArrayList<>();

	private DiverManager diverManager;

	// private SurferManager surferManager;

	// ======================================================================
	//
	// OBSERVERS
	//
	// ======================================================================

	/**
	 * A list of all observer factories and managers.
	 */
	private final List<Tuple> allObservers = new ArrayList<>();

	/**
	 * A list of observer factories and managers that must be started once per
	 * run.
	 */
	private final List<Tuple> observersPerRun = new ArrayList<>();

	/**
	 * A list of observer factories and managers that must be started once per
	 * task.
	 */
	private final List<Tuple> observersPerTask = new ArrayList<>();

	/**
	 * A list of observer factories and managers that must be started once for
	 * each diver.
	 */
	private final List<Tuple> observersPerDiver = new ArrayList<>();

	/**
	 * A list of observer factories and managers that must be started once for
	 * each surfer.
	 */
	private final List<Tuple> observersPerSurfer = new ArrayList<>();

	// ======================================================================
	//
	// DELEGATES
	//
	// ======================================================================

	/**
	 * A map from method names to delegate objects (which are instances of the
	 * modelling classes).
	 */
	private final Map<String, Object> delegates = new HashMap<>();

	// ======================================================================
	//
	// STANDARD OUT AND ERR
	//
	// ======================================================================

	/**
	 * The normal standard output.
	 */
	private final PrintStream systemOut = System.out;

	/**
	 * The normal standard error.
	 */
	private final PrintStream systemErr = System.err;

	/**
	 * A null printstream for suppressing output and error.
	 */
	private static final PrintStream NUL = new PrintStream(new OutputStream() {
		@Override
		public void write(int b) throws IOException {
			// do nothing
		}
	});

	// ======================================================================
	//
	// QUEUES
	//
	// ======================================================================

	/**
	 * A queue of models produced by strategies and consumed by divers.
	 */
	private final BlockingQueue<Model> diverModelQueue;

	/**
	 * A queue of models produced by strategies and consumed by surfers.
	 */
	private final BlockingQueue<Model> surferModelQueue;

	/**
	 * A queue of path conditions produced by divers and consumed by strategies.
	 */
	private final BlockingQueue<SegmentedPC> pcQueue;

	/**
	 * A queue of traces produced by surfers and consumed by strategies.
	 */
	private final BlockingQueue<Trace> traceQueue;

	// ======================================================================
	//
	// TIMING INFORMATION
	//
	// ======================================================================

	/**
	 * The wall-clock-time that the analysis run was started.
	 */
	private Calendar startingTime;

	/**
	 * The wall-clock-time that the analysis run was stopped.
	 */
	private Calendar stoppingTime;

	/**
	 * The number of milliseconds of elapsed time when we next write a console
	 * update.
	 */
	private long nextReportingTime = 0;

	/**
	 * The maximum number of SECONDS the coastal run is allowed to execute.
	 */
	private final long timeLimit;

	// ======================================================================
	//
	// TASK MANAGEMENT
	//
	// ======================================================================

	/**
	 * The maximum number of threads to use for divers and strategies.
	 */
	private final int maxThreads;

	/**
	 * The task manager for concurrent divers and strategies.
	 */
	private final ExecutorService executor;

	/**
	 * The task completion manager that collects the "results" from divers and
	 * strategies. In this case, there are no actual results; instead, all
	 * products are either consumed or collected by the reporter.
	 */
	private final CompletionService<Void> completionService;

	/**
	 * A list of outstanding tasks.
	 */
	private final List<Future<Void>> futures;

	// ======================================================================
	//
	// SHARED VARIABLES
	//
	// ======================================================================

	/**
	 * The outstanding number of models that have not been processed by divers,
	 * or whose resulting path conditions have not been processed by a strategy.
	 * This is not merely the number of items in the models queue or the pcs
	 * queue: work may also be presented by a model of path condition currently
	 * being processed. The strategy adjusts the work after it has processed a
	 * path condition.
	 */
	private final AtomicLong work = new AtomicLong(0);

	/**
	 * A flag to indicate that either (1) all work is done, or (2) symbolic
	 * execution must stop because a "stop" point was reached.
	 */
	private final AtomicBoolean workDone = new AtomicBoolean(false);

	// ======================================================================
	//
	// CONSTRUCTOR
	//
	// ======================================================================

	/* @formatter:off
	 * 
	 * private final Logger log;
	 * private final ImmutableConfiguration config;
	 * private final Broker broker;
	 * private final Reporter reporter;
	 * private final PathTree pathTree;
	 * private final InstrumentationClassManager classManager;
	 * 
	 * // TARGET INFORMATION
	 * 
	 * private final List<String> prefixes = new ArrayList<>();
	 * private final List<Trigger> triggers = new ArrayList<>();
	 * 
	 * // VARIABLE BOUNDS
	 * 
	 * private final Map<Class<?>, Object> defaultMinValue = new HashMap<>();
	 * private final Map<Class<?>, Object> defaultMaxValue = new HashMap<>();
	 * private final Map<String, Integer> minBounds = new HashMap<>();
	 * private final Map<String, Integer> maxBounds = new HashMap<>();
	 * 
	 * // DIVERS, SURFERS, STRATEGIES
	 *
	 * private final List<TaskInfo> tasks = new ArrayList<>();
	 * private DiverManager diverManager;
	 *
	 * // OBSERVERS
	 * 
	 * private final List<Tuple> observersPerRun = new ArrayList<>();
	 * private final List<Tuple> observersPerTask = new ArrayList<>();
	 * private final List<Tuple> observersPerDiver = new ArrayList<>();
	 * private final List<Tuple> observersPerSurfer = new ArrayList<>();
	 * 
	 * // DELEGATES
	 * 
	 * private final Map<String, Object> delegates = new HashMap<>();
	 * 
	 * // STANDARD OUT AND ERR
	 * 
	 * private final PrintStream systemOut = System.out;
	 * private final PrintStream systemErr = System.err;
	 * private static final PrintStream NUL = new PrintStream(...)
	 * 
	 * // QUEUES
	 * 
	 * private final BlockingQueue<Model> diverModelQueue;
	 * private final BlockingQueue<Model> surferModelQueue;
	 * private final BlockingQueue<SegmentedPC> pcQueue;
	 * private final BlockingQueue<Trace> traceQueue;
	 * 
	 * // TIMING INFORMATION
	 * 
	 * private Calendar startingTime;
	 * private Calendar stoppingTime;
	 * private long nextReportingTime = 0;
	 * private final long timeLimit;
	 * 
	 * // TASK MANAGEMENT
	 * 
	 * private final int maxThreads;
	 * private final ExecutorService executor;
	 * private final CompletionService<Void> completionService;
	 * private final List<Future<Void>> futures;
	 * 
	 * // SHARED VARIABLES
	 * 
	 * private final AtomicLong work = new AtomicLong(0);
	 * private final AtomicBoolean workDone = new AtomicBoolean(false);
	 *
	 * @formatter:on
	 */

	/**
	 * Initialize the final fields for this analysis run of COASTAL.
	 * 
	 * @param log
	 *            the logger to use for this analysis run
	 * @param config
	 *            the configuration to use for this analysis run
	 */
	public COASTAL(Logger log, ImmutableConfiguration config) {
		this.log = log;
		this.config = config;
		broker = new Broker();
		broker.subscribe("coastal-stop", this::report);
		reporter = new Reporter(this);
		pathTree = new PathTree(this);
		classManager = new InstrumentationClassManager(this, System.getProperty("java.class.path"));
		parseConfig();
		// QUEUES
		diverModelQueue = new PriorityBlockingQueue<>(50, (Model m1, Model m2) -> m1.getPriority() - m2.getPriority());
		surferModelQueue = new PriorityBlockingQueue<>(50, (Model m1, Model m2) -> m1.getPriority() - m2.getPriority());
		pcQueue = new LinkedBlockingQueue<>();
		traceQueue = new LinkedBlockingQueue<>();
		// TIMING INFORMATION
		timeLimit = Conversion.limitLong(getConfig(), "coastal.settings.time-limit");
		// TASK MANAGEMENT
		maxThreads = Conversion.minmax(getConfig().getInt("coastal.settings.max-threads", 2), 2, Short.MAX_VALUE);
		executor = Executors.newCachedThreadPool();
		completionService = new ExecutorCompletionService<Void>(executor);
		futures = new ArrayList<Future<Void>>(maxThreads);
	}

	/**
	 * Parse the COASTAL configuration and extract the targets, triggers,
	 * delegates, bounds, and observers.
	 */
	private void parseConfig() {
		// TARGET INFORMATION
		parseConfigTarget();
		// VARIABLE BOUNDS
		parseConfigBounds();
		// STRATEGIES
		parseConfigStrategies();
		// OBSERVERS
		parseConfigObservers();
		// DELEGATES
		parseConfigDelegates();
	}

	/**
	 * Parse the COASTAL configuration to extract the target information.
	 */
	private void parseConfigTarget() {
		prefixes.addAll(getConfig().getList(String.class, "coastal.target.instrument"));
		String[] triggerNames = getConfig().getStringArray("coastal.target.trigger");
		for (int i = 0; i < triggerNames.length; i++) {
			triggers.add(Trigger.createTrigger(parameters, triggerNames[i].trim()));
		}
	}

	/**
	 * Parse the COASTAL configuration to extract the type and variable bounds.
	 */
	private void parseConfigBounds() {
		defaultMinBounds.put(boolean.class, 0);
		defaultMinBounds.put(byte.class, Byte.MIN_VALUE);
		defaultMinBounds.put(short.class, Short.MIN_VALUE);
		defaultMinBounds.put(char.class, Character.MIN_VALUE);
		defaultMinBounds.put(int.class, Integer.MIN_VALUE + 1);
		defaultMinBounds.put(long.class, Integer.MIN_VALUE + 1L);
		defaultMinBounds.put(boolean[].class, 0);
		defaultMinBounds.put(byte[].class, Byte.MIN_VALUE);
		defaultMinBounds.put(short[].class, Short.MIN_VALUE);
		defaultMinBounds.put(char[].class, Character.MIN_VALUE);
		defaultMinBounds.put(int[].class, Integer.MIN_VALUE + 1);
		defaultMinBounds.put(long[].class, Integer.MIN_VALUE + 1L);
		defaultMaxBounds.put(boolean.class, 1);
		defaultMaxBounds.put(byte.class, Byte.MAX_VALUE);
		defaultMaxBounds.put(short.class, Short.MAX_VALUE);
		defaultMaxBounds.put(char.class, Character.MAX_VALUE);
		defaultMaxBounds.put(int.class, Integer.MAX_VALUE);
		defaultMaxBounds.put(long.class, (long) Integer.MAX_VALUE);
		defaultMaxBounds.put(boolean[].class, 1);
		defaultMaxBounds.put(byte[].class, Byte.MAX_VALUE);
		defaultMaxBounds.put(short[].class, Short.MAX_VALUE);
		defaultMaxBounds.put(char[].class, Character.MAX_VALUE);
		defaultMaxBounds.put(int[].class, Integer.MAX_VALUE);
		defaultMaxBounds.put(long[].class, (long) Integer.MAX_VALUE);
		for (int i = 0; true; i++) {
			String key = "coastal.bounds.bound(" + i + ")";
			String var = getConfig().getString(key + "[@name]");
			if (var == null) {
				break;
			} else if (var.equals("boolean")) {
				int l = (Integer) defaultMinBounds.get(boolean.class);
				int u = (Integer) defaultMaxBounds.get(boolean.class);
				defaultMinBounds.put(boolean.class, new Integer(getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(boolean.class, new Integer(getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("boolean[]")) {
				int l = (Integer) defaultMinBounds.get(boolean[].class);
				int u = (Integer) defaultMaxBounds.get(boolean[].class);
				defaultMinBounds.put(boolean[].class, new Integer(getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(boolean[].class, new Integer(getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("byte")) {
				byte l = (Byte) defaultMinBounds.get(byte.class);
				byte u = (Byte) defaultMaxBounds.get(byte.class);
				defaultMinBounds.put(byte.class, new Byte((byte) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(byte.class, new Byte((byte) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("byte[]")) {
				byte l = (Byte) defaultMinBounds.get(byte[].class);
				byte u = (Byte) defaultMaxBounds.get(byte[].class);
				defaultMinBounds.put(byte[].class, new Byte((byte) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(byte[].class, new Byte((byte) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("short")) {
				short l = (Short) defaultMinBounds.get(short.class);
				short u = (Short) defaultMaxBounds.get(short.class);
				defaultMinBounds.put(short.class, new Short((short) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(short.class, new Short((short) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("short[]")) {
				short l = (Short) defaultMinBounds.get(short[].class);
				short u = (Short) defaultMaxBounds.get(short[].class);
				defaultMinBounds.put(short[].class, new Short((short) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(short[].class, new Short((short) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("char")) {
				char l = (Character) defaultMinBounds.get(char.class);
				char u = (Character) defaultMaxBounds.get(char.class);
				defaultMinBounds.put(char.class, new Character((char) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(char.class, new Character((char) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("char[]")) {
				char l = (Character) defaultMinBounds.get(char[].class);
				char u = (Character) defaultMaxBounds.get(char[].class);
				defaultMinBounds.put(char[].class, new Character((char) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(char[].class, new Character((char) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("int")) {
				int l = (Integer) defaultMinBounds.get(int.class);
				int u = (Integer) defaultMaxBounds.get(int.class);
				defaultMinBounds.put(int.class, new Integer((int) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(int.class, new Integer((int) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("int[]")) {
				int l = (Integer) defaultMinBounds.get(int[].class);
				int u = (Integer) defaultMaxBounds.get(int[].class);
				defaultMinBounds.put(int[].class, new Integer((int) getConfig().getInt(key + "[@min]", l)));
				defaultMaxBounds.put(int[].class, new Integer((int) getConfig().getInt(key + "[@max]", u)));
			} else if (var.equals("long")) {
				long l = (Long) defaultMinBounds.get(long.class);
				long u = (Long) defaultMaxBounds.get(long.class);
				defaultMinBounds.put(long.class, new Long((long) getConfig().getLong(key + "[@min]", l)));
				defaultMaxBounds.put(long.class, new Long((long) getConfig().getLong(key + "[@max]", u)));
			} else if (var.equals("long[]")) {
				long l = (Long) defaultMinBounds.get(long[].class);
				long u = (Long) defaultMaxBounds.get(long[].class);
				defaultMinBounds.put(long[].class, new Long((long) getConfig().getLong(key + "[@min]", l)));
				defaultMaxBounds.put(long[].class, new Long((long) getConfig().getLong(key + "[@max]", u)));
			} else {
				addBound(minBounds, key + "[@min]", var);
				addBound(maxBounds, key + "[@max]", var);
			}
		}
	}

	private void addBound(Map<String, Object> bounds, String key, String var) {
		if (getConfig().containsKey(key)) {
			Class<?> type = parameters.get(var);
			long value = getConfig().getLong(key);
			if (type == boolean.class) {
				bounds.put(var, (int) value);
			} else if (type == byte.class) {
				bounds.put(var, (byte) value);
			} else if (type == short.class) {
				bounds.put(var, (short) value);
			} else if (type == char.class) {
				bounds.put(var, (char) value);
			} else if (type == int.class) {
				bounds.put(var, (int) value);
			} else if (type == long.class) {
				bounds.put(var, value);
			} else {
				bounds.put(var, (int) value);
			}
		}
	}

	/**
	 * Parse the COASTAL configuration to extract the strategies.
	 */
	private void parseConfigStrategies() {
		DiverFactory diverFactory = new DiverFactory();
		int dt = getConfig().getInt("coastal.divers[@threads]", 0);
		int dl = getConfig().getInt("coastal.divers[@min-threads]", 0);
		int du = getConfig().getInt("coastal.divers[@max-threads]", 128);
		SurferFactory surferFactory = new SurferFactory();
		int st = getConfig().getInt("coastal.surfers[@threads]", 0);
		int sl = getConfig().getInt("coastal.surfers[@min-threads]", 0);
		int su = getConfig().getInt("coastal.surfers[@max-threads]", 128);
		if (dt + st == 0) {
			dt = 1;
		}
		TaskInfo dti = new TaskInfo(this, diverFactory, dt, dl, du);
		diverManager = (DiverManager) dti.getManager();
		tasks.add(dti);
		TaskInfo sti = new TaskInfo(this, surferFactory, st, sl, su);
		// surferManager = (SurferManager) sti.getManager();
		tasks.add(sti);
		int sfCount = 0;
		for (int i = 0; true; i++) {
			String key = "coastal.strategies.strategy(" + i + ")";
			String sfClass = getConfig().getString(key);
			if (sfClass == null) {
				break;
			}
			Object sfObject = Conversion.createInstance(this, sfClass);
			if ((sfObject == null) || !(sfObject instanceof StrategyFactory)) {
				Banner bn = new Banner('@');
				bn.println("UNKNOWN STRATEGY IGNORED:\n" + sfClass);
				bn.display(log);
				continue;
			}
			int sft = getConfig().getInt(key + "[@threads]", 0);
			int sfl = getConfig().getInt(key + "[@min-threads]", 0);
			int sfu = getConfig().getInt(key + "[@max-threads]", 128);
			StrategyFactory sf = (StrategyFactory) sfObject;
			tasks.add(new TaskInfo(this, sf, sft, sfl, sfu));
			sfCount += sft;
		}
		if (sfCount == 0) {
			log.fatal("NO STRATEGY SPECIFIED -- TERMINATING");
			System.exit(1);
		}
	}

	/**
	 * Parse the COASTAL configuration to extract the observers.
	 */
	private void parseConfigObservers() {
		for (int i = 0; true; i++) {
			String key = "coastal.observers.observer(" + i + ")";
			String observerName = getConfig().getString(key);
			if (observerName == null) {
				break;
			}
			Object observerFactory = Conversion.createInstance(this, observerName.trim());
			if ((observerFactory != null) && (observerFactory instanceof ObserverFactory)) {
				ObserverFactory factory = (ObserverFactory) observerFactory;
				ObserverManager manager = ((ObserverFactory) observerFactory).createManager(this);
				Tuple fm = new Tuple(observerFactory, manager);
				allObservers.add(fm);
				switch (factory.getFrequency()) {
				case ObserverFactory.ONCE_PER_RUN:
					observersPerRun.add(fm);
					break;
				case ObserverFactory.ONCE_PER_TASK:
					observersPerTask.add(fm);
					break;
				case ObserverFactory.ONCE_PER_DIVER:
					observersPerDiver.add(fm);
					break;
				default:
					observersPerSurfer.add(fm);
					break;
				}
			}
		}
	}

	/**
	 * Parse the COASTAL configuration to extract the delegates.
	 */
	private void parseConfigDelegates() {
		for (int i = 0; true; i++) {
			String key = "coastal.delegates.delegate(" + i + ")";
			String target = getConfig().getString(key + ".for");
			if (target == null) {
				break;
			}
			String model = getConfig().getString(key + ".model");
			Object modelObject = Conversion.createInstance(this, model.trim());
			if (modelObject != null) {
				delegates.put(target.trim(), modelObject);
			}
		}
	}

	// ======================================================================
	//
	// GETTERS
	//
	// ======================================================================

	/**
	 * Return the logger for this run of COASTAL.
	 * 
	 * @return the one and only logger for this analysis run
	 */
	public Logger getLog() {
		return log;
	}

	/**
	 * Return the configuration for this analysis of COASTAL. This configuration
	 * is immutable.
	 * 
	 * @return the configuration
	 */
	public ImmutableConfiguration getConfig() {
		return config;
	}

	/**
	 * Return the message broker for this analysis run of COASTAL.
	 * 
	 * @return the message broker
	 */
	public Broker getBroker() {
		return broker;
	}

	/**
	 * Return the reporter for this analysis run of COASTAL. This is used mainly
	 * for testing purposes.
	 * 
	 * @return the reporter
	 */
	public Reporter getReporter() {
		return reporter;
	}

	/**
	 * Return the path tree for this analysis run of COASTAL.
	 * 
	 * @return the path tree
	 */
	public PathTree getPathTree() {
		return pathTree;
	}

	public Reportable getPathTreeReportable() {
		return new Reportable() {

			private final String[] propertyNames = new String[] { "#inserted", "#revisited", "#infeasible" };

			@Override
			public Object[] getPropertyValues() {
				Object[] propertyValues = new Object[3];
				propertyValues[0] = pathTree.getInsertedCount();
				propertyValues[1] = pathTree.getRevisitCount();
				propertyValues[2] = pathTree.getInfeasibleCount();
				return propertyValues;
			}

			@Override
			public String[] getPropertyNames() {
				return propertyNames;
			}

			@Override
			public String getName() {
				return "PathTree";
			}
		};
	}

	public Reportable getCoastalReportable() {
		return new Reportable() {

			private final String[] propertyNames = new String[] { "#elapsed", "diverModelQueue", "surferModelQueue",
					"pcQueue", "traceQueue" };

			@Override
			public Object[] getPropertyValues() {
				Object[] propertyValues = new Object[5];
				propertyValues[0] = System.currentTimeMillis() - startingTime.getTimeInMillis();
				propertyValues[1] = diverModelQueue.size();
				propertyValues[2] = surferModelQueue.size();
				propertyValues[3] = pcQueue.size();
				propertyValues[4] = traceQueue.size();
				return propertyValues;
			}

			@Override
			public String[] getPropertyNames() {
				return propertyNames;
			}

			@Override
			public String getName() {
				return "COASTAL";
			}
		};
	}

	/**
	 * Return the starting time of the analysis run in milliseconds.
	 * 
	 * @return the starting time in milliseconds
	 */
	public long getStartingTime() {
		return startingTime.getTimeInMillis();
	}

	/**
	 * Return the class manager for this analysis run of COASTAL.
	 * 
	 * @return the class manager
	 */
	public InstrumentationClassManager getClassManager() {
		return classManager;
	}

	/**
	 * Check is a potential target is an actual target. The potential target is
	 * simply a class name that is compared to all known targets to see if any
	 * are prefixes of the potential target.
	 * 
	 * @param potentialTarget
	 *            the name of class
	 * @return true if and only if the potential target is prefixed by a known
	 *         target
	 */
	public boolean isTarget(String potentialTarget) {
		for (String target : prefixes) {
			if (potentialTarget.startsWith(target)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Find the index of the trigger with the corresponding name and signature.
	 * If no such trigger exists, return -1.
	 * 
	 * @param name
	 *            the method name of the (potential) trigger
	 * @param signature
	 *            the signature of the (potential) trigger
	 * @return the index of the trigger in the list of triggers, or -1
	 */
	public int findTrigger(String name, String signature) {
		int index = 0;
		for (Trigger t : triggers) {
			if (t.match(name, signature)) {
				return index;
			}
			index++;
		}
		return -1;
	}

	/**
	 * Return the trigger with a specified index.
	 * 
	 * @param index
	 *            the index we are searching for
	 * @return the corresponding trigger or {@code null} if there is no such
	 *         trigger
	 */
	public Trigger getTrigger(int index) {
		return triggers.get(index);
	}

	/**
	 * Find a delegate for a specified class name.
	 * 
	 * @param className
	 *            the name of the class to search for
	 * @return the delegate object or {@code null} if there is none
	 */
	public Object findDelegate(String className) {
		return delegates.get(className);
	}

	/**
	 * A convenient constant for use in
	 * {@link #findDelegate(String, String, String)}. This represents the empty
	 * list of parameters.
	 */
	private static final Class<?>[] EMPTY_PARAMETERS = new Class<?>[0];

	/**
	 * Find a delegate method.
	 * 
	 * @param owner
	 *            the method class name
	 * @param name
	 *            the method name
	 * @param descriptor
	 *            the method signature
	 * @return a Java reflection of the method or {@code null} if it was not
	 *         found
	 */
	public Method findDelegate(String owner, String name, String descriptor) {
		Object delegate = findDelegate(owner);
		if (delegate == null) {
			return null;
		}
		String methodName = name + SymbolicState.getAsciiSignature(descriptor);
		Method delegateMethod = null;
		try {
			delegateMethod = delegate.getClass().getDeclaredMethod(methodName, EMPTY_PARAMETERS);
		} catch (NoSuchMethodException | SecurityException e) {
			return null;
		}
		assert delegateMethod != null;
		return delegateMethod;
	}

	// ======================================================================
	//
	// OBSERVERS
	//
	// ======================================================================

	public Iterable<Tuple> getObserversPerRun() {
		return observersPerRun;
	}

	public Iterable<Tuple> getObserversPerTask() {
		return observersPerTask;
	}

	public Iterable<Tuple> getObserversPerDiver() {
		return observersPerDiver;
	}

	public Iterable<Tuple> getObserversPerSurfer() {
		return observersPerSurfer;
	}

	public Iterable<Tuple> getAllObservers() {
		return allObservers;
	}

	// ======================================================================
	//
	// VARIABLE BOUNDS
	//
	// ======================================================================

	/**
	 * Return the lower bound for symbolic variables without an explicit bound
	 * of their own.
	 * 
	 * @param type
	 *            the type of the variable
	 * @return the lower bound for symbolic variables
	 */
	public Object getDefaultMinValue(Class<?> type) {
		return defaultMinBounds.get(type);
	}

	/**
	 * Return the lower bound for the specified symbolic variable.
	 * 
	 * @param variable
	 *            the name of the variable
	 * @param type
	 *            the type of the variable
	 * @return the lower bound for the variable
	 */
	public Object getMinBound(String variable, Class<?> type) {
		return getMinBound(variable, getDefaultMinValue(type));
	}

	/**
	 * Return the lower bound for a specific variable, or -- if there is no
	 * explicit bound -- for another variable. If there is no explicit bound,
	 * the specified default value is returned.
	 * 
	 * This is used for array where the specific variable is the array index and
	 * the more general variable is the array as a whole.
	 * 
	 * @param variable1
	 *            the name of the specific variable
	 * @param variable2
	 *            the name of the more general variable
	 * @param type
	 *            the type of the variables
	 * @return the lower bound for either variable
	 */
	public Object getMinBound(String variable1, String variable2, Class<?> type) {
		return getMinBound(variable1, getMinBound(variable2, type));
	}

	/**
	 * Return the lower bound for the specified symbolic variable. If there is
	 * no explicit bound, the specified default value is returned.
	 * 
	 * @param variable
	 *            the name of the variable
	 * @param defaultValue
	 *            the default lower bound
	 * @return the lower bound for the variable
	 */
	public Object getMinBound(String variable, Object defaultValue) {
		Object min = minBounds.get(variable);
		if (min == null) {
			min = defaultValue;
		}
		return min;
	}

	/**
	 * Return the upper bound for symbolic variables without an explicit bound
	 * of their own.
	 * 
	 * @param type
	 *            the type of the variable
	 * @return the upper bound for symbolic variables
	 */
	public Object getDefaultMaxValue(Class<?> type) {
		return defaultMaxBounds.get(type);
	}

	/**
	 * Return the upper bound for the specified symbolic variable.
	 * 
	 * @param variable
	 *            the name of the variable
	 * @param type
	 *            the type of the variable
	 * @return the upper bound for the variable
	 */
	public Object getMaxBound(String variable, Class<?> type) {
		return getMaxBound(variable, getDefaultMaxValue(type));
	}

	/**
	 * Return the upper bound for a specific variable, or -- if there is no
	 * explicit bound -- for another variable. If there is no explicit bound,
	 * the specified default value is returned.
	 * 
	 * This is used for array where the specific variable is the array index and
	 * the more general variable is the array as a whole.
	 * 
	 * @param variable1
	 *            the name of the specific variable
	 * @param variable2
	 *            the name of the more general variable
	 * @param type
	 *            the type of the variables
	 * @return the upper bound for either variable
	 */
	public Object getMaxBound(String variable1, String variable2, Class<?> type) {
		return getMaxBound(variable1, getMaxBound(variable2, type));
	}

	/**
	 * Return the upper bound for the specified symbolic integer variable. If
	 * there is no explicit bound, the specified default value is returned.
	 * 
	 * @param variable
	 *            the name of the variable
	 * @param defaultValue
	 *            the default upper bound
	 * @return the upper bound for the variable
	 */
	public Object getMaxBound(String variable, Object defaultValue) {
		Object max = maxBounds.get(variable);
		if (max == null) {
			max = defaultValue;
		}
		return max;
	}

	// ======================================================================
	//
	// TASKS
	//
	// ======================================================================

	public List<TaskManager> getTasks() {
		return tasks.stream().map(t -> t.getManager()).collect(Collectors.toList());
	}

	// ======================================================================
	//
	// STANDARD OUT AND ERR
	//
	// ======================================================================

	/**
	 * Return the normal standard output. This is recorded at the start of the
	 * run and (possibly) set to null to suppress the program's normal output.
	 * 
	 * @return the pre-recorded standard output
	 */
	public PrintStream getSystemOut() {
		return systemOut;
	}

	/**
	 * Return the normal standard error. This is recorded at the start of the
	 * run and (possibly) set to null to suppress the program's normal output.
	 * 
	 * @return the pre-recorded standard error
	 */
	public PrintStream getSystemErr() {
		return systemErr;
	}

	// ======================================================================
	//
	// QUEUES
	//
	// ======================================================================

	// DIVER MODEL QUEUE

	/**
	 * Return the length of the diver model queue.
	 * 
	 * @return the length of the diver model queue
	 */
	public int getDiverModelQueueLength() {
		return diverModelQueue.size();
	}

	/**
	 * Add a list of models to the diver model queue.
	 * 
	 * @param mdls
	 *            the list of models to add
	 */
	public void addDiverModels(List<Model> mdls) {
		mdls.forEach(m -> {
			try {
				diverModelQueue.put(m);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Return the next available diver model.
	 * 
	 * @return the model as a variable-value mapping
	 * @throws InterruptedException
	 *             if the action of removing the model was interrupted
	 */
	public Map<String, Constant> getNextDiverModel() throws InterruptedException {
		return diverModelQueue.take().getConcreteValues();
	}

	/**
	 * Add the first model to the queue of models. This kicks off the analysis
	 * run.
	 * 
	 * THIS METHOD IS A PART OF THE DESIGN THAT NEEDS TO BE REFACTORED!!
	 * 
	 * @param firstModel
	 *            the very first model to add
	 */
	public void addFirstModel(Model firstModel) {
		try {
			diverModelQueue.put(firstModel);
			work.incrementAndGet();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
	}

	// SURFER MODEL QUEUE

	/**
	 * Return the length of the surfer model queue.
	 * 
	 * @return the length of the surfer model queue
	 */
	public int getSurferModelQueueLength() {
		return surferModelQueue.size();
	}

	/**
	 * Add a list of models to the surfer model queue.
	 * 
	 * @param mdls
	 *            the list of models to add
	 */
	public void addSurferModels(List<Model> mdls) {
		mdls.forEach(m -> {
			try {
				surferModelQueue.put(m);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Return the next available surfer model.
	 * 
	 * @return the model as a variable-value mapping
	 * @throws InterruptedException
	 *             if the action of removing the model was interrupted
	 */
	public Map<String, Constant> getNextSurferModel() throws InterruptedException {
		return surferModelQueue.take().getConcreteValues();
	}

	// PATH CONDITION QUEUE

	/**
	 * Return the length of the path condition queue.
	 * 
	 * @return the length of the path condition queue
	 */
	public int getPcQueueLength() {
		return pcQueue.size();
	}

	/**
	 * Add a new entry to the queue of path conditions.
	 * 
	 * @param spc
	 *            the path condition to add
	 */
	public void addPc(SegmentedPC spc) {
		try {
			if (spc == null) {
				pcQueue.put(SegmentedPC.NULL);
			} else {
				pcQueue.put(spc);
			}
		} catch (InterruptedException e) {
			// ignore silently
		}
	}

	/**
	 * Return the next available path condition.
	 * 
	 * @return the next path condition
	 * @throws InterruptedException
	 *             if the action of removing the path condition was interrupted
	 */
	public SegmentedPC getNextPc() throws InterruptedException {
		return pcQueue.take();
	}

	// TRACE QUEUE

	/**
	 * Return the length of the trace queue.
	 * 
	 * @return the length of the trace queue
	 */
	public int getTraceQueueLength() {
		return traceQueue.size();
	}

	/**
	 * Add a new entry to the queue of traces.
	 * 
	 * @param spc
	 *            the trace to add
	 */
	public void addTrace(Trace trace) {
		try {
			if (trace == null) {
				traceQueue.put(Trace.NULL);
			} else {
				traceQueue.put(trace);
			}
		} catch (InterruptedException e) {
			// ignore silently
		}
	}

	/**
	 * Return the next available trace.
	 * 
	 * @return the next trace
	 * @throws InterruptedException
	 *             if the action of removing the trace was interrupted
	 */
	public Trace getNextTrace() throws InterruptedException {
		return traceQueue.take();
	}

	// ======================================================================
	//
	// TIMING INFORMATION
	//
	// ======================================================================

	/**
	 * Wait for a change in the status of the workDone flag or until a specified
	 * time has elapsed.
	 * 
	 * @param delay
	 *            time to wait in milliseconds
	 */
	public void idle(long delay) throws InterruptedException {
		if ((System.currentTimeMillis() - startingTime.getTimeInMillis()) / 1000 > timeLimit) {
			log.warn("time limit reached");
			stopWork();
		} else {
			synchronized (workDone) {
				workDone.wait(delay);
			}
		}
	}

	/**
	 * Update the count of outstanding work items by a given amount.
	 * 
	 * @param delta
	 *            how much to add to the number of work items
	 */
	public void updateWork(long delta) {
		long w = work.addAndGet(delta);
		if (w == 0) {
			stopWork();
		}
	}

	/**
	 * Set the flag to indicate that the analysis run must stop.
	 */
	public void stopWork() {
		workDone.set(true);
		synchronized (workDone) {
			workDone.notifyAll();
		}
	}

	/**
	 * Stop the still-executing tasks and the taks manager itself.
	 */
	public void shutdown() {
		futures.forEach(f -> f.cancel(true));
		executor.shutdownNow();
	}

	/**
	 * Start the analysis run, showing all banners by default.
	 */
	public void start() {
		start(true);
	}

	/**
	 * Start the analysis run, and show banners if and only the parameter flag
	 * is true.
	 * 
	 * @param showBanner
	 *            a flag to tell whether or not to show banners
	 */
	public void start(boolean showBanner) {
		startingTime = Calendar.getInstance();
		getBroker().publish("coastal-init", this);
		if (showBanner) {
			new Banner('~').println("COASTAL version " + Version.read()).display(log);
		}
		// Dump the configuration
		config.getKeys().forEachRemaining(k -> log.info("{} = {}", k, config.getString(k)));
		// Now we can start by creating run-level observers
		for (Tuple observer : getObserversPerRun()) {
			ObserverFactory observerFactory = (ObserverFactory) observer.get(0);
			ObserverManager observerManager = (ObserverManager) observer.get(1);
			observerFactory.createObserver(this, observerManager);
		}
		// Redirect System.out/System.err
		if (!getConfig().getBoolean("coastal.settings.echo-output", false)) {
			System.setOut(NUL);
			System.setErr(NUL);
		}
		getBroker().publish("coastal-start", this);
		getBroker().subscribe("tick", this::tick);
		addFirstModel(new Model(0, null));
		try {
			startTasks();
			while (!workDone.get()) {
				idle(500);
				getBroker().publish("tick", this);
				// TO DO ----> balance the threads
			}
		} catch (InterruptedException e) {
			log.info(Banner.getBannerLine("main thread interrupted", '!'));
		} finally {
			getBroker().publish("tock", this);
			shutdown();
		}
		stoppingTime = Calendar.getInstance();
		getBroker().publish("coastal-stop", this);
		getBroker().publish("coastal-report", this);
		System.setOut(getSystemOut());
		System.setErr(getSystemErr());
		if (diverManager.getDiveCount() < 2) {
			Banner bn = new Banner('@');
			bn.println("ONLY A SINGLE RUN EXECUTED\n");
			bn.println("CHECK YOUR SETTINGS -- THERE MIGHT BE A PROBLEM SOMEWHERE");
			bn.display(log);
		}
		if (showBanner) {
			new Banner('~').println("COASTAL DONE").display(log);
		}
	}

	private void startTasks() {
		for (TaskInfo task : tasks) {
			for (int i = 0; i < task.getInitThreads(); i++) {
				futures.add(completionService.submit(task.create(this)));
			}
		}
	}

	public void tick(Object object) {
		long elapsedTime = System.currentTimeMillis() - getStartingTime();
		if (elapsedTime > nextReportingTime) {
			String time = Banner.getElapsed(this);
			String dives = String.format("dives:%d", diverManager.getDiveCount());
			String queue = String.format("[models:%d pcs:%d]", diverModelQueue.size(), pcQueue.size());
			log.info("{} {} {}", time, dives, queue);
			if (elapsedTime > 3600000) {
				// After 1 hour, we report every 5 minutes
				nextReportingTime = elapsedTime + 300000;
			} else if (elapsedTime > 900000) {
				// After 15 minutes, we report every minute
				nextReportingTime = elapsedTime + 60000;
			} else if (elapsedTime > 300000) {
				// After 5 minutes, we report every 30 seconds
				nextReportingTime = elapsedTime + 30000;
			} else if (elapsedTime > 60000) {
				// After 1 minute, we report every 15 seconds
				nextReportingTime = elapsedTime + 15000;
			} else {
				// Otherwise we report every five seconds
				nextReportingTime = elapsedTime + 5000;
			}
		}
	}

	public void report(Object object) {
		getBroker().publish("report", new Tuple("COASTAL.start", startingTime));
		getBroker().publish("report", new Tuple("COASTAL.stop", stoppingTime));
		long duration = stoppingTime.getTimeInMillis() - startingTime.getTimeInMillis();
		getBroker().publish("report", new Tuple("COASTAL.time", duration));
	}

	// ======================================================================
	//
	// MAIN FUNCTION
	//
	// ======================================================================

	/**
	 * The main function and entry point for COASTAL.
	 * 
	 * @param args
	 *            the command-line arguments
	 */
	public static void main(String[] args) {
		final Logger log = LogManager.getLogger("COASTAL");
		new Banner('~').println("COASTAL version " + Version.read()).display(log);
		ImmutableConfiguration config = loadConfiguration(log, args);
		if (config != null) {
			new COASTAL(log, config).start(false);
		}
		new Banner('~').println("COASTAL DONE (" + config.getString("run-name", "?") + ")").display(log);
	}

	/**
	 * The name of COASTAL configuration file, both the resource (part of the
	 * project, providing sensible defaults), and the user's own configuration
	 * file (providing overriding personalizations).
	 */
	private static final String COASTAL_CONFIGURATION = "coastal.xml";

	/**
	 * The subdirectory in the user's home directory where the personal coastal
	 * file is searched for.
	 */
	private static final String COASTAL_DIRECTORY = ".coastal";

	/**
	 * The user's home directory.
	 */
	private static final String HOME_DIRECTORY = System.getProperty("user.home");

	/**
	 * The full name of the subdirectory where the personal file is searched
	 * for.
	 */
	private static final String HOME_COASTAL_DIRECTORY = HOME_DIRECTORY + File.separator + COASTAL_DIRECTORY;

	/**
	 * The full name of the personal configuration file.
	 */
	private static final String HOME_CONFIGURATION = HOME_COASTAL_DIRECTORY + File.separator + COASTAL_CONFIGURATION;

	/**
	 * Load the COASTAL configuration. Three sources are consulted:
	 * 
	 * <ul>
	 * <li>a project resource</li>
	 * <li>the user's personal configuration file</li>
	 * <li>the configuration file specified on the command line</li>
	 * </ul>
	 * 
	 * The second source overrides the first, and the third source overrides the
	 * first two sources.
	 * 
	 * @param log
	 *            the logger to which to report
	 * @param args
	 *            the command-line arguments
	 * @return an immutable configuration
	 */
	public static ImmutableConfiguration loadConfiguration(Logger log, String[] args) {
		return loadConfiguration(log, args, null);
	}

	public static ImmutableConfiguration loadConfiguration(Logger log, String[] args, String extra) {
		String runName = (args.length > 0) ? FilenameUtils.getName(args[0]) : null;
		return loadConfiguration(log, runName, args, extra);
	}

	public static ImmutableConfiguration loadConfiguration(Logger log, String runName, String[] args, String extra) {
		if (runName != null) {
			String runNameSetting = "<run-name>" + runName + "</run-name>";
			if (extra == null) {
				extra = runNameSetting;
			} else {
				extra += runNameSetting;
			}
		}
		Configuration cfg1 = loadConfigFromResource(log, COASTAL_CONFIGURATION);
		Configuration cfg2 = loadConfigFromFile(log, HOME_CONFIGURATION);
		if (args.length < 1) {
			Banner bn = new Banner('@');
			bn.println("MISSING PROPERTIES FILE\n");
			bn.println("USAGE: coastal <properties file>");
			bn.display(log);
			return null;
		}
		Configuration[] cfg3 = new Configuration[args.length];
		int cfgIndex = -1;
		for (String arg : args) {
			String filename = arg;
			if (filename.endsWith(".java")) {
				filename = filename.substring(0, filename.length() - 4) + "xml";
			}
			cfg3[++cfgIndex] = loadConfigFromFile(log, filename);
			if (cfg3[cfgIndex] == null) {
				cfg3[cfgIndex] = loadConfigFromResource(log, filename);
			}
			if (cfg3[cfgIndex] == null) {
				Banner bn = new Banner('@');
				bn.println("COASTAL PROBLEM\n");
				bn.println("COULD NOT READ CONFIGURATION FILE \"" + filename + "\"");
				bn.display(log);
			}
		}
		Configuration cfg4 = loadConfigFromString(log, extra);
		ConfigCombiner combiner = new ConfigCombiner();
		combiner.addJoinNode("bounds", "name");
		CombinedConfiguration config = new CombinedConfiguration(combiner);
		if (cfg4 != null) {
			config.addConfiguration(cfg4);
		}
		for (Configuration cfg : cfg3) {
			if (cfg != null) {
				config.addConfiguration(cfg);
			}
		}
		if (cfg2 != null) {
			config.addConfiguration(cfg2);
		}
		if (cfg1 != null) {
			config.addConfiguration(cfg1);
		}
		if (config.getString("coastal.target.trigger") == null) {
			Banner bn = new Banner('@');
			bn.println("SUSPICIOUS PROPERTIES FILE\n");
			bn.println("ARE YOU SURE THAT THE ARGUMENT IS A .xml FILE?");
			bn.display(log);
			return null;
		}
		return ConfigurationUtils.unmodifiableConfiguration(config);
	}

	/**
	 * Load a COASTAL configuration from a file.
	 * 
	 * @param log
	 *            the logger to which to report
	 * @param filename
	 *            the name of the file
	 * @return an immutable configuration or {@code null} if the file was not
	 *         found
	 */
	private static Configuration loadConfigFromFile(Logger log, String filename) {
		try {
			InputStream inputStream = new FileInputStream(filename);
			Configuration cfg = loadConfigFromStream(log, inputStream);
			log.trace("loaded configuration from {}", filename);
			return cfg;
		} catch (ConfigurationException x) {
			if (x.getCause() != null) {
				log.trace("configuration error: " + x.getCause().getMessage());
			}
		} catch (FileNotFoundException x) {
			log.trace("failed to load configuration from {}", filename);
		}
		return null;
	}

	/**
	 * Load a COASTAL configuration from a Java resource.
	 * 
	 * @param log
	 *            the logger to which to report
	 * @param resourceName
	 *            the name of the resource
	 * @return an immutable configuration or {@code null} if the resource was
	 *         not found
	 */
	private static Configuration loadConfigFromResource(Logger log, String resourceName) {
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream resourceStream = loader.getResourceAsStream(resourceName)) {
			if (resourceStream != null) {
				Configuration cfg = loadConfigFromStream(log, resourceStream);
				log.trace("loaded configuration from {}", resourceName);
				return cfg;
			}
		} catch (ConfigurationException x) {
			if (x.getCause() != null) {
				log.trace("configuration error: " + x.getCause().getMessage());
			}
		} catch (IOException x) {
			log.trace("failed to load configuration from {}", resourceName);
		}
		return null;
	}

	/**
	 * Load a COASTAL configuration from an input stream.
	 * 
	 * @param log
	 *            the logger to which to report
	 * @param inputStream
	 *            the stream from which to read
	 * @return an immutable configuration
	 * @throws ConfigurationException
	 *             if anything went wrong during the loading of the
	 *             configuration
	 */
	private static Configuration loadConfigFromStream(Logger log, InputStream inputStream)
			throws ConfigurationException {
		//		XMLConfiguration cfg = new BasicConfigurationBuilder<>(XMLConfiguration.class)
		//				.configure(new Parameters().xml().setValidating(true)).getConfiguration();
		XMLConfiguration cfg = new BasicConfigurationBuilder<>(XMLConfiguration.class).configure(new Parameters().xml())
				.getConfiguration();
		FileHandler fh = new FileHandler(cfg);
		fh.load(inputStream);
		return cfg;
	}

	/**
	 * Load a COASTAL configuration from a string.
	 * 
	 * @param log
	 *            the logger to which to report
	 * @param configString
	 *            the stirng that contains the configuration
	 * @return an immutable configuration
	 */
	private static Configuration loadConfigFromString(Logger log, String configString) {
		if (configString == null) {
			return null;
		}
		try {
			String finalString = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\" ?>"
					+ "<!DOCTYPE configuration PUBLIC \"-//DEEPSEA//COASTAL configuration//EN\" "
					+ "\"https://deepseaplatform.github.io/coastal/coastal.dtd\">" + "<configuration>" + configString
					+ "</configuration>";
			InputStream in = new ByteArrayInputStream(finalString.getBytes());
			return loadConfigFromStream(log, in);
		} catch (ConfigurationException x) {
			// ignore
		}
		return null;
	}

}
