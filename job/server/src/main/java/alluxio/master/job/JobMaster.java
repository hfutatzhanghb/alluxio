/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.job;

import alluxio.ClientContext;
import alluxio.Constants;
import alluxio.RuntimeConstants;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemContext;
import alluxio.clock.SystemClock;
import alluxio.collections.IndexDefinition;
import alluxio.collections.IndexedSet;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AccessControlException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.JobDoesNotExistException;
import alluxio.exception.status.NotFoundException;
import alluxio.exception.status.ResourceExhaustedException;
import alluxio.grpc.BuildVersion;
import alluxio.grpc.GrpcService;
import alluxio.grpc.JobCommand;
import alluxio.grpc.JobMasterHeartbeatPOptions;
import alluxio.grpc.JobMasterMetaCommand;
import alluxio.grpc.JobMasterStatus;
import alluxio.grpc.ListAllPOptions;
import alluxio.grpc.RegisterCommand;
import alluxio.grpc.RegisterJobMasterPOptions;
import alluxio.grpc.ServiceType;
import alluxio.heartbeat.FixedIntervalSupplier;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatExecutor;
import alluxio.heartbeat.HeartbeatThread;
import alluxio.job.CmdConfig;
import alluxio.job.JobConfig;
import alluxio.job.JobServerContext;
import alluxio.job.MasterWorkerInfo;
import alluxio.job.meta.JobIdGenerator;
import alluxio.job.plan.PlanConfig;
import alluxio.job.wire.CmdStatusBlock;
import alluxio.job.wire.JobInfo;
import alluxio.job.wire.JobServiceSummary;
import alluxio.job.wire.JobWorkerHealth;
import alluxio.job.wire.Status;
import alluxio.job.wire.TaskInfo;
import alluxio.job.wire.WorkflowInfo;
import alluxio.job.workflow.WorkflowConfig;
import alluxio.master.AbstractMaster;
import alluxio.master.MasterContext;
import alluxio.master.audit.AsyncUserAccessAuditLogWriter;
import alluxio.master.audit.AuditContext;
import alluxio.master.job.command.CommandManager;
import alluxio.master.job.plan.PlanCoordinator;
import alluxio.master.job.plan.PlanTracker;
import alluxio.master.job.tracker.CmdJobTracker;
import alluxio.master.job.workflow.WorkflowTracker;
import alluxio.master.journal.NoopJournaled;
import alluxio.master.meta.JobMasterInfo;
import alluxio.master.meta.JobMasterMasterServiceHandler;
import alluxio.master.meta.JobMasterSync;
import alluxio.master.meta.RetryHandlingJobMasterMasterClient;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.resource.LockResource;
import alluxio.security.authentication.AuthType;
import alluxio.security.authentication.AuthenticatedClientUser;
import alluxio.security.authentication.ClientContextServerInjector;
import alluxio.underfs.UfsManager;
import alluxio.util.CommonUtils;
import alluxio.util.ConfigurationUtils;
import alluxio.util.IdUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.wire.Address;
import alluxio.wire.WorkerInfo;
import alluxio.wire.WorkerNetAddress;
import alluxio.worker.job.JobMasterClientContext;

import com.codahale.metrics.Gauge;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.Context;
import io.grpc.ServerInterceptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The master that handles all job managing operations.
 */
@ThreadSafe
public class JobMaster extends AbstractMaster implements NoopJournaled {
  private static final Logger LOG = LoggerFactory.getLogger(JobMaster.class);

  // Worker metadata management.
  private final IndexDefinition<MasterWorkerInfo, Long> mIdIndex =
      IndexDefinition.ofUnique(MasterWorkerInfo::getId);

  private final IndexDefinition<MasterWorkerInfo, WorkerNetAddress> mAddressIndex =
      IndexDefinition.ofUnique(MasterWorkerInfo::getWorkerAddress);

  // Job master metadata management.
  private static final IndexDefinition<JobMasterInfo, Long> ID_INDEX =
      IndexDefinition.ofUnique(JobMasterInfo::getId);
  private static final IndexDefinition<JobMasterInfo, Address> ADDRESS_INDEX =
      IndexDefinition.ofUnique(JobMasterInfo::getAddress);

  /** Keeps track of standby job masters which are in communication with the primary. */
  private final IndexedSet<JobMasterInfo> mJobMasters =
      new IndexedSet<>(ID_INDEX, ADDRESS_INDEX);
  /** Keeps track of standby job masters which are no longer in communication with the primary. */
  private final IndexedSet<JobMasterInfo> mLostJobMasters =
      new IndexedSet<>(ID_INDEX, ADDRESS_INDEX);

  /** The connect address for the rpc server. */
  private final InetSocketAddress mRpcConnectAddress =
      NetworkAddressUtils.getConnectAddress(NetworkAddressUtils.ServiceType.JOB_MASTER_RPC,
          Configuration.global());
  private final int mPort;
  /** The address of this master. */
  private final Address mJobMasterAddress;

  /**
   * The Filesystem context that the job master uses for its client.
   */
  private final JobServerContext mJobServerContext;

  /*
   * All worker information. Access must be controlled on mWorkers using the RW lock(mWorkerRWLock).
   */
  @GuardedBy("mWorkerRWLock")
  private final IndexedSet<MasterWorkerInfo> mWorkers = new IndexedSet<>(mIdIndex, mAddressIndex);

  private final ConcurrentHashMap<Long, JobWorkerHealth> mWorkerHealth;

  private final ReentrantReadWriteLock mWorkerRWLock = new ReentrantReadWriteLock(true);

  private final AtomicLong mNextWorkerId = new AtomicLong(CommonUtils.getCurrentMs());

  // Manager for worker tasks.
  private final CommandManager mCommandManager;

  // Manager for adding and removing plans.
  private final PlanTracker mPlanTracker;

  // Manager for adding and removing workflows.s
  private final WorkflowTracker mWorkflowTracker;

  private final JobIdGenerator mJobIdGenerator;

  private AsyncUserAccessAuditLogWriter mAsyncAuditLogWriter;

  /** Distributed command job tracker. */
  private final CmdJobTracker mCmdJobTracker;

  /**
   * Creates a new instance of {@link JobMaster}.
   *
   * @param masterContext the context for Alluxio master
   * @param filesystem    the Alluxio filesystem client the job master uses to communicate
   * @param fsContext     the filesystem client's underlying context
   * @param ufsManager    the ufs manager
   */
  public JobMaster(MasterContext masterContext, FileSystem filesystem,
      FileSystemContext fsContext, UfsManager ufsManager) {
    super(masterContext, new SystemClock(),
        ExecutorServiceFactories.cachedThreadPool(Constants.JOB_MASTER_NAME));
    mJobServerContext = new JobServerContext(filesystem, fsContext, ufsManager);
    mCommandManager = new CommandManager();
    mJobIdGenerator = new JobIdGenerator();
    mWorkflowTracker = new WorkflowTracker(this);

    mPort = NetworkAddressUtils.getPort(NetworkAddressUtils.ServiceType.JOB_MASTER_RPC,
        Configuration.global());
    mJobMasterAddress = new Address().setHost(NetworkAddressUtils
            .getConnectHost(NetworkAddressUtils.ServiceType.JOB_MASTER_RPC, Configuration.global()))
        .setRpcPort(mPort);

    mPlanTracker = new PlanTracker(
        Configuration.getLong(PropertyKey.JOB_MASTER_JOB_CAPACITY),
        Configuration.getMs(PropertyKey.JOB_MASTER_FINISHED_JOB_RETENTION_TIME),
        Configuration.getLong(PropertyKey.JOB_MASTER_FINISHED_JOB_PURGE_COUNT),
        mWorkflowTracker);

    mWorkerHealth = new ConcurrentHashMap<>();

    mCmdJobTracker = new CmdJobTracker(
            fsContext, this, mPlanTracker);

    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_JOB_COUNT.getName(),
        () -> MetricsSystem.counter(MetricKey.MASTER_JOB_CANCELED.getName()).getCount()
            + MetricsSystem.counter(MetricKey.MASTER_JOB_COMPLETED.getName()).getCount()
            + MetricsSystem.counter(MetricKey.MASTER_JOB_CREATED.getName()).getCount()
            + MetricsSystem.counter(MetricKey.MASTER_JOB_FAILED.getName()).getCount()
            + MetricsSystem.counter(MetricKey.MASTER_JOB_RUNNING.getName()).getCount());
  }

  /**
   * @return new job id
   */
  public long getNewJobId() {
    return mJobIdGenerator.getNewJobId();
  }

  @Override
  public void start(Boolean isLeader) throws IOException {
    LOG.info("Job master starting with state {}", isLeader ? "primary" : "standby");
    super.start(isLeader);

    // Start serving metrics system, this will not block
    MetricsSystem.startSinks(Configuration.getString(PropertyKey.METRICS_CONF_FILE));

    // Fail any jobs that were still running when the last job master stopped.
    LOG.info("Updating job statuses");
    for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
      if (!planCoordinator.isJobFinished()) {
        planCoordinator.setJobAsFailed("JobMasterShutdown",
            "Job failed: Job master shut down during execution");
      }
    }
    if (isLeader) {
      LOG.info("Starting job master as primary");
      getExecutorService()
          .submit(new HeartbeatThread(HeartbeatContext.JOB_MASTER_LOST_WORKER_DETECTION,
              new LostWorkerDetectionHeartbeatExecutor(),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.JOB_MASTER_LOST_WORKER_INTERVAL)),
              Configuration.global(), mMasterContext.getUserState()));
      getExecutorService()
          .submit(new HeartbeatThread(HeartbeatContext.JOB_MASTER_LOST_MASTER_DETECTION,
              new LostMasterDetectionHeartbeatExecutor(),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.JOB_MASTER_LOST_MASTER_INTERVAL)),
              Configuration.global(), mMasterContext.getUserState()));
      /**
       * The audit logger will be running all the time, and an operation checks whether
       * to enable audit logs in {@link #createAuditContext}. So audit log can be turned on/off
       * at runtime by updating the property key.
       */
      mAsyncAuditLogWriter = new AsyncUserAccessAuditLogWriter("JOB_MASTER_AUDIT_LOG");
      mAsyncAuditLogWriter.start();
      MetricsSystem.registerGaugeIfAbsent(
          MetricKey.MASTER_AUDIT_LOG_ENTRIES_SIZE.getName(),
          () -> mAsyncAuditLogWriter != null
              ? mAsyncAuditLogWriter.getAuditLogEntriesSize() : -1);
    } else {
      LOG.info("Starting job master as standby");
      if (ConfigurationUtils.isHaMode(Configuration.global())) {
        // Standby master should setup MetaMasterSync to communicate with the leader master
        RetryHandlingJobMasterMasterClient jobMasterClient =
                new RetryHandlingJobMasterMasterClient(JobMasterClientContext
                        .newBuilder(ClientContext.create(Configuration.global())).build());
        getExecutorService().submit(new HeartbeatThread(HeartbeatContext.JOB_MASTER_SYNC,
            new JobMasterSync(mJobMasterAddress, jobMasterClient),
            () -> new FixedIntervalSupplier(
                Configuration.getMs(PropertyKey.JOB_MASTER_MASTER_HEARTBEAT_INTERVAL)),
            Configuration.global(), mMasterContext.getUserState()));
        LOG.info("Standby job master with address {} starts sending heartbeat to the primary.",
            mJobMasterAddress);
      }
    }
  }

  @Override
  public void stop() throws IOException {
    if (mAsyncAuditLogWriter != null) {
      mAsyncAuditLogWriter.stop();
      mAsyncAuditLogWriter = null;
    }
    super.stop();
  }

  @Override
  public Map<ServiceType, GrpcService> getServices() {
    Map<ServiceType, GrpcService> services = Maps.newHashMap();
    services.put(ServiceType.JOB_MASTER_CLIENT_SERVICE,
        new GrpcService(ServerInterceptors
            .intercept(new JobMasterClientServiceHandler(this),
                new ClientContextServerInjector())));
    services.put(ServiceType.JOB_MASTER_WORKER_SERVICE,
        new GrpcService(new JobMasterWorkerServiceHandler(this)));
    services.put(ServiceType.JOB_MASTER_MASTER_SERVICE,
        new GrpcService(new JobMasterMasterServiceHandler(this)));
    return services;
  }

  @Override
  public String getName() {
    return Constants.JOB_MASTER_NAME;
  }

  /**
   * Runs a job with the given configuration.
   *
   * @param jobConfig the job configuration
   * @return the job id tracking the progress
   * @throws JobDoesNotExistException   when the job doesn't exist
   * @throws ResourceExhaustedException if the job master is too busy to run the job
   */
  public synchronized long run(JobConfig jobConfig)
      throws JobDoesNotExistException, ResourceExhaustedException {
    long jobId = getNewJobId();
    run(jobConfig, jobId);
    return jobId;
  }

  /**
   * Runs a job with the given configuration and job id.
   *
   * @param jobConfig the job configuration
   * @param jobId the job id
   * @throws JobDoesNotExistException when the job doesn't exist
   * @throws ResourceExhaustedException if the job master is too busy to run the job
   */
  public synchronized void run(JobConfig jobConfig, long jobId)
      throws JobDoesNotExistException, ResourceExhaustedException {
    // This RPC service implementation triggers another RPC.
    // Run the implementation under forked context to avoid interference.
    // Then restore the current context at the end.
    Context forkedCtx = Context.current().fork();
    Context prevCtx = forkedCtx.attach();
    try (JobMasterAuditContext auditContext =
        createAuditContext("run")) {
      auditContext.setJobId(jobId);
      auditContext.setJobName(jobConfig.getName());
      if (jobConfig instanceof PlanConfig) {
        mPlanTracker.run((PlanConfig) jobConfig, mCommandManager, mJobServerContext,
            getWorkerInfoList(), jobId);
        auditContext.setSucceeded(true);
        return;
      } else if (jobConfig instanceof WorkflowConfig) {
        mWorkflowTracker.run((WorkflowConfig) jobConfig, jobId);
        auditContext.setSucceeded(true);
        return;
      }
      throw new JobDoesNotExistException(
          ExceptionMessage.JOB_DEFINITION_DOES_NOT_EXIST.getMessage(jobConfig.getName()));
    } finally {
      forkedCtx.detach(prevCtx);
    }
  }

  /**
   * Submit a job with the given configuration.
   *
   * @param cmdConfig the CMD configuration
   * @return the job control id tracking the progress
   * @throws JobDoesNotExistException   when the job doesn't exist
   * @throws ResourceExhaustedException if the job master is too busy to run the job
   */
  public synchronized long submit(CmdConfig cmdConfig)
      throws JobDoesNotExistException, IOException {
    long jobControlId = getNewJobId();
    // This RPC service implementation triggers another RPC.
    // Run the implementation under forked context to avoid interference.
    // Then restore the current context at the end.
    Context forkedCtx = Context.current().fork();
    Context prevCtx = forkedCtx.attach();
    try (JobMasterAuditContext auditContext =
         createAuditContext("run")) {
      auditContext.setJobId(jobControlId);
      mCmdJobTracker.run(cmdConfig, jobControlId);
    } finally {
      forkedCtx.detach(prevCtx);
    }

    return jobControlId;
  }

  /**
   * Cancels a job.
   *
   * @param jobId the id of the job
   * @throws JobDoesNotExistException when the job does not exist
   */
  public void cancel(long jobId) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
             createAuditContext("cancel")) {
      auditContext.setJobId(jobId);
      PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(jobId);
      if (planCoordinator == null) {
        if (!mWorkflowTracker.cancel(jobId)) {
          throw new JobDoesNotExistException(jobId);
        }
        return;
      }
      planCoordinator.cancel();
      auditContext.setSucceeded(true);
    }
  }

  /**
   * Get command status.
   * @param jobControlId
   * @return status of a distributed commmand
   */
  public Status getCmdStatus(long jobControlId) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("getCmdStatus")) {
      auditContext.setJobId(jobControlId);
      return mCmdJobTracker.getCmdStatus(jobControlId);
    }
  }

  /**
   * @return list of all job ids
   * @param options listing options
   */
  public List<Long> list(ListAllPOptions options) {
    try (JobMasterAuditContext auditContext =
             createAuditContext("list")) {
      List<Long> ids = new ArrayList<>();
      ids.addAll(mPlanTracker.findJobs(options.getName(),
          options.getStatusList().stream()
              .map(status -> Status.valueOf(status.name()))
              .collect(Collectors.toList())));
      ids.addAll(mWorkflowTracker.findJobs(options.getName(),
          options.getStatusList().stream()
              .map(status -> Status.valueOf(status.name()))
              .collect(Collectors.toList())));
      Collections.sort(ids);
      auditContext.setSucceeded(true);
      return ids;
    }
  }

  /**
   * @return list of all command ids
   * @param options listing options (using existing options)
   */
  public List<Long> listCmds(ListAllPOptions options) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("listCmds")) {
      List<Long> ids = new ArrayList<>();
      ids.addAll(mCmdJobTracker.findCmdIds(
              options.getStatusList().stream()
                      .map(status -> Status.valueOf(status.name()))
                      .collect(Collectors.toList())));
      Collections.sort(ids);
      auditContext.setSucceeded(true);
      return ids;
    }
  }

  /**
   * @return get a detailed status information for a command
   * @param jobControlId job control ID of a command
   */
  public CmdStatusBlock getCmdStatusDetailed(long jobControlId) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("getCmdStatusDetailed")) {
      return mCmdJobTracker.getCmdStatusBlock(jobControlId);
    }
  }

  /**
   * @return all failed paths
   */
  public Set<String> getAllFailedPaths() {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("getAllFailedPaths")) {
      Set<String> ids = new HashSet<>();
      ids.addAll(mCmdJobTracker.findAllFailedPaths());
      auditContext.setSucceeded(true);
      return ids;
    }
  }

  /**
   * @return get failed paths for a command
   * @param jobControlId job control id
   */
  public Set<String> getFailedPaths(long jobControlId) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("getFailedPaths")) {
      Set<String> ids = new HashSet<>();
      ids.addAll(mCmdJobTracker.findFailedPaths(jobControlId));
      auditContext.setSucceeded(true);
      return ids;
    }
  }

  /**
   * @return list of all job infos
   */
  public List<JobInfo> listDetailed() {
    try (JobMasterAuditContext auditContext =
             createAuditContext("listDetailed")) {
      List<JobInfo> jobInfos = new ArrayList<>();

      for (PlanCoordinator coordinator : mPlanTracker.coordinators()) {
        jobInfos.add(coordinator.getPlanInfoWire(false));
      }

      jobInfos.addAll(mWorkflowTracker.getAllInfo());

      jobInfos.sort(Comparator.comparingLong(JobInfo::getId));
      auditContext.setSucceeded(true);
      return jobInfos;
    }
  }

  /**
   * @param limit maximum number of jobInfos to return
   * @param before filters out on or after this timestamp (in ms) (-1 to disable)
   * @param after filter out on or before this timestamp (in ms) (-1 to disable)
   * @return list of all failed job infos ordered by when it failed (recently failed first)
   */
  public List<JobInfo> failed(int limit, long before, long after) {
    List<JobInfo> jobInfos = new ArrayList<>();
    mPlanTracker.failed()
        .filter((planInfoMeta) -> {
          final long lastStatusChangeMs = planInfoMeta.getLastStatusChangeMs();
          if (before >= 0 && before <= lastStatusChangeMs) {
            return false;
          }
          return after < lastStatusChangeMs;
        }).filter((planInfoMeta) -> planInfoMeta.getLastStatusChangeMs() > after)
        .limit(limit)
        .forEachOrdered((planInfoMeta) ->
            jobInfos.add(new alluxio.job.wire.PlanInfo(planInfoMeta, false)));
    return jobInfos;
  }

  /**
   * Gets information of the given job id (verbose = True).
   *
   * @param jobId the id of the job
   * @return the job information
   * @throws JobDoesNotExistException if the job does not exist
   */
  public JobInfo getStatus(long jobId) throws JobDoesNotExistException {
    try (JobMasterAuditContext auditContext =
             createAuditContext("getStatus")) {
      auditContext.setJobId(jobId);
      JobInfo jobInfo = getStatus(jobId, true);
      if (jobInfo != null) {
        auditContext.setJobName(jobInfo.getName());
        auditContext.setSucceeded(true);
      }
      return jobInfo;
    }
  }

  /**
   * Gets information of the given job id.
   *
   * @param jobId the id of the job
   * @param verbose whether the job info should be verbose
   * @return the job information
   * @throws JobDoesNotExistException if the job does not exist
   */
  public JobInfo getStatus(long jobId, boolean verbose) throws JobDoesNotExistException {
    PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(jobId);
    if (planCoordinator == null) {

      WorkflowInfo status = mWorkflowTracker.getStatus(jobId, verbose);

      if (status == null) {
        throw new JobDoesNotExistException(jobId);
      }
      return status;
    }
    return planCoordinator.getPlanInfoWire(verbose);
  }

  /**
   * Gets summary of the job service.
   *
   * @return {@link JobServiceSummary}
   */
  public alluxio.job.wire.JobServiceSummary getSummary() {
    return new JobServiceSummary(listDetailed());
  }

  /**
   * @return health metrics for each of the job workers
   */
  public List<JobWorkerHealth> getAllWorkerHealth() {
    try (JobMasterAuditContext auditContext =
             createAuditContext("getAllWorkerHealth")) {
      ArrayList<JobWorkerHealth> result =
          Lists.newArrayList(mWorkerHealth.values());
      result.sort(Comparator.comparingLong(JobWorkerHealth::getWorkerId));
      auditContext.setSucceeded(true);
      return result;
    }
  }

  /**
   * Lists the status of all job masters in the cluster.
   *
   * @return the list
   */
  public List<JobMasterStatus> getAllJobMasterStatus() {
    try (JobMasterAuditContext auditContext =
                 createAuditContext("getAllMasterStatus")) {
      ArrayList<JobMasterStatus> result = new ArrayList<>();
      final Map<String, Gauge> gauges = MetricsSystem.METRIC_REGISTRY.getGauges();
      Gauge startTimeGauge = gauges.get(MetricKey.MASTER_START_TIME.getName());
      JobMasterStatus primaryStatus = JobMasterStatus.newBuilder()
              .setMasterAddress(mJobMasterAddress.toProto())
              .setState("PRIMARY")
              .setStartTime((long) startTimeGauge.getValue())
              .setVersion(RuntimeConstants.CURRENT_VERSION_INFO).build();
      result.add(primaryStatus);

      for (JobMasterInfo standbyJobMaster : mJobMasters) {
        JobMasterStatus status = JobMasterStatus.newBuilder()
            .setMasterAddress(standbyJobMaster.getAddress().toProto())
            .setState("STANDBY")
            .setStartTime(standbyJobMaster.getStartTimeMs())
            .setVersion(standbyJobMaster.getVersion())
            .build();
        result.add(status);
      }
      for (JobMasterInfo standbyJobMaster : mLostJobMasters) {
        JobMasterStatus status = JobMasterStatus.newBuilder()
            .setMasterAddress(standbyJobMaster.getAddress().toProto())
            .setState("LOST")
            .setStartTime(standbyJobMaster.getStartTimeMs())
            .setVersion(standbyJobMaster.getVersion())
            .build();
        result.add(status);
      }
      auditContext.setSucceeded(true);
      return result;
    }
  }

  /**
   * Returns a worker id for the given worker.
   *
   * @param workerNetAddress the worker {@link WorkerNetAddress}
   * @param version the version info of the job worker
   * @return the worker id for this worker
   */
  public long registerWorker(WorkerNetAddress workerNetAddress, BuildVersion version) {
    // Run under exclusive lock for mWorkers
    try (LockResource workersLockExclusive = new LockResource(mWorkerRWLock.writeLock())) {
      // Check if worker has already been registered with this job master
      if (mWorkers.contains(mAddressIndex, workerNetAddress)) {
        // If the worker is trying to re-register, it must have died and been restarted. We need to
        // clean up the dead worker.
        LOG.info(
            "Worker at address {} is re-registering. Failing tasks for previous worker at that "
                + "address",
            workerNetAddress);
        MasterWorkerInfo deadWorker = mWorkers.getFirstByField(mAddressIndex, workerNetAddress);
        for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
          planCoordinator.failTasksForWorker(deadWorker.getId());
        }
        mWorkerHealth.remove(deadWorker.getId());
        mWorkers.remove(deadWorker);
      }
      // Generate a new worker id.
      long workerId = mNextWorkerId.getAndIncrement();
      mWorkers.add(new MasterWorkerInfo(workerId, workerNetAddress, version));
      LOG.info("registerWorker(): WorkerNetAddress: {} id: {} version-revision: {}-{}",
          workerNetAddress, workerId, version.getVersion(), version.getRevision());
      return workerId;
    }
  }

  /**
   * @return a list of {@link WorkerInfo} objects representing the workers in Alluxio
   */
  public List<WorkerInfo> getWorkerInfoList() {
    List<WorkerInfo> workerInfoList = new ArrayList<>(mWorkers.size());
    // Run under shared lock for mWorkers
    try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
      for (MasterWorkerInfo masterWorkerInfo : mWorkers) {
        workerInfoList.add(masterWorkerInfo.generateClientWorkerInfo());
      }
    }
    return workerInfoList;
  }

  /**
   * @param taskPoolSize the task pool size for the job workers
   */
  public void setTaskPoolSize(int taskPoolSize) {
    try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
      for (MasterWorkerInfo worker : mWorkers) {
        mCommandManager.submitSetTaskPoolSizeCommand(worker.getId(), taskPoolSize);
      }
    }
  }

  /**
   * Updates the tasks' status when a worker periodically heartbeats with the master, and sends the
   * commands for the worker to execute.
   *
   * @param jobWorkerHealth the job worker health info
   * @param taskInfoList the list of the task information
   * @return the list of {@link JobCommand} to the worker
   */
  public List<JobCommand> workerHeartbeat(JobWorkerHealth jobWorkerHealth,
      List<TaskInfo> taskInfoList) {

    long workerId = jobWorkerHealth.getWorkerId();

    String hostname;
    // Run under shared lock for mWorkers
    try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
      MasterWorkerInfo worker = mWorkers.getFirstByField(mIdIndex, workerId);
      if (worker == null) {
        return Collections.singletonList(JobCommand.newBuilder()
            .setRegisterCommand(RegisterCommand.getDefaultInstance()).build());
      }
      hostname = worker.getWorkerAddress().getHost();
      // Update last-update-time of this particular worker under lock
      // to prevent lost worker detector clearing it under race
      worker.updateLastUpdatedTimeMs();
    }
    mWorkerHealth.put(workerId, jobWorkerHealth);

    // Update task infos for all jobs involved
    Map<Long, List<TaskInfo>> taskInfosPerJob = new HashMap<>();
    for (TaskInfo taskInfo : taskInfoList) {
      taskInfo.setWorkerHost(hostname);
      if (!taskInfosPerJob.containsKey(taskInfo.getJobId())) {
        taskInfosPerJob.put(taskInfo.getJobId(), new ArrayList());
      }
      taskInfosPerJob.get(taskInfo.getJobId()).add(taskInfo);
    }
    for (Map.Entry<Long, List<TaskInfo>> taskInfosPair : taskInfosPerJob.entrySet()) {
      PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(taskInfosPair.getKey());
      if (planCoordinator != null) {
        planCoordinator.updateTasks(taskInfosPair.getValue());
      }
    }
    return mCommandManager.pollAllPendingCommands(workerId);
  }

  /**
   * Handles a heartbeat from a standby job master.
   *
   * @param masterId the job master id allocated by the primary job master
   * @param options extra options
   * @return the command to the standby job master
   */
  public JobMasterMetaCommand jobMasterHeartbeat(
      long masterId, JobMasterHeartbeatPOptions options) {
    JobMasterInfo master = mJobMasters.getFirstByField(ID_INDEX, masterId);
    if (master == null) {
      LOG.warn("Could not find master id: {} for heartbeat. Instructed to register", masterId);
      return JobMasterMetaCommand.MetaCommand_Register;
    }

    master.updateLastUpdatedTimeMs();
    return JobMasterMetaCommand.MetaCommand_Nothing;
  }

  /**
   * Handles the register request from a standby job master.
   *
   * @param masterId the job master id
   * @param options extra options
   */
  public void jobMasterRegister(long masterId, RegisterJobMasterPOptions options)
      throws NotFoundException {
    LOG.info("Job master {} attempts to register", masterId);
    JobMasterInfo master = mJobMasters.getFirstByField(ID_INDEX, masterId);
    if (master == null) {
      throw new NotFoundException(
          MessageFormat.format("No master with masterId {0,number,#} is found", masterId));
    }

    master.updateLastUpdatedTimeMs();
    if (options.hasStartTimeMs()) {
      master.setStartTimeMs(options.getStartTimeMs());
    }
    if (options.hasLosePrimacyTimeMs()) {
      master.setLosePrimacyTimeMs(options.getLosePrimacyTimeMs());
    }
    if (options.hasVersion()) {
      master.setVersion(options.getVersion());
    } else {
      master.setVersion(RuntimeConstants.UNKNOWN_VERSION_INFO);
    }
    LOG.info("registerMaster(): master: {}", master);
  }

  /**
   * Allocates an ID for the job master from the target address.
   *
   * @param address the address
   * @return an allocated id
   */
  public long getMasterId(Address address) {
    JobMasterInfo existingMaster = mJobMasters.getFirstByField(ADDRESS_INDEX, address);
    if (existingMaster != null) {
      // This master address is already mapped to a master id.
      long oldMasterId = existingMaster.getId();
      LOG.warn("The master {} already exists as id {}.", address, oldMasterId);
      return oldMasterId;
    }

    JobMasterInfo lostMaster = mLostJobMasters.getFirstByField(ADDRESS_INDEX, address);
    if (lostMaster != null) {
      // This is one of the lost masters
      synchronized (lostMaster) {
        final long lostMasterId = lostMaster.getId();
        LOG.warn("A lost master {} has requested its old id {}.", address, lostMasterId);

        // Update the timestamp of the master before it is considered an active master.
        lostMaster.updateLastUpdatedTimeMs();
        mJobMasters.add(lostMaster);
        mLostJobMasters.remove(lostMaster);
        return lostMasterId;
      }
    }

    // Generate a new master id.
    long masterId = IdUtils.getRandomNonNegativeLong();
    while (!mJobMasters.add(new JobMasterInfo(masterId, address))) {
      masterId = IdUtils.getRandomNonNegativeLong();
    }

    LOG.info("getMasterId(): MasterAddress: {} id: {}", address, masterId);
    return masterId;
  }

  /**
   * Creates a {@link JobMasterAuditContext} instance.
   *
   * @param command the command to be logged by this {@link AuditContext}
   * @return newly-created {@link JobMasterAuditContext} instance
   */
  private JobMasterAuditContext createAuditContext(String command) {
    // Audit log may be enabled during runtime
    AsyncUserAccessAuditLogWriter auditLogWriter = null;
    if (Configuration.getBoolean(PropertyKey.MASTER_AUDIT_LOGGING_ENABLED)) {
      auditLogWriter = mAsyncAuditLogWriter;
    }
    JobMasterAuditContext auditContext =
        new JobMasterAuditContext(auditLogWriter);
    if (auditLogWriter != null) {
      String user = null;
      String ugi = "";
      try {
        user = AuthenticatedClientUser.getClientUser(Configuration.global());
      } catch (AccessControlException e) {
        ugi = "N/A";
      }
      if (user != null) {
        try {
          String primaryGroup = CommonUtils.getPrimaryGroupName(user, Configuration.global());
          ugi = user + "," + primaryGroup;
        } catch (IOException e) {
          LOG.debug("Failed to get primary group for user {}.", user);
          ugi = user + ",N/A";
        }
      }
      AuthType authType =
          Configuration.getEnum(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.class);
      auditContext.setUgi(ugi)
          .setAuthType(authType)
          .setIp(ClientContextServerInjector.getIpAddress())
          .setClientVersion(ClientContextServerInjector.getClientVersion())
          .setCommand(command)
          .setAllowed(true)
          .setCreationTimeNs(System.nanoTime());
    }
    return auditContext;
  }

  /**
   * Lost job master periodic check.
   */
  private final class LostMasterDetectionHeartbeatExecutor implements HeartbeatExecutor {

    /**
     * Constructs a new {@link LostMasterDetectionHeartbeatExecutor}.
     */
    public LostMasterDetectionHeartbeatExecutor() {
    }

    @Override
    public void heartbeat(long timeout) {
      long masterTimeoutMs = Configuration.getMs(PropertyKey.JOB_MASTER_MASTER_TIMEOUT);
      for (JobMasterInfo master : mJobMasters) {
        synchronized (master) {
          final long lastUpdate = mClock.millis() - master.getLastUpdatedTimeMs();
          if (lastUpdate > masterTimeoutMs) {
            LOG.error("A standby job master {}({}) timed out after {}ms without a heartbeat!",
                master.getId(), master.getAddress(), lastUpdate);
            mLostJobMasters.add(master);
            mJobMasters.remove(master);
          }
        }
      }
    }

    @Override
    public void close() {
      // Nothing to clean up
    }
  }

  /**
   * Lost worker periodic check.
   */
  private final class LostWorkerDetectionHeartbeatExecutor implements HeartbeatExecutor {

    /**
     * Constructs a new {@link LostWorkerDetectionHeartbeatExecutor}.
     */
    public LostWorkerDetectionHeartbeatExecutor() {}

    @Override
    public void heartbeat(long timeLimitMs) {
      int masterWorkerTimeoutMs = (int) Configuration
          .getMs(PropertyKey.JOB_MASTER_WORKER_TIMEOUT);
      List<MasterWorkerInfo> lostWorkers = new ArrayList<>();
      // Run under shared lock for mWorkers
      try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
        for (MasterWorkerInfo worker : mWorkers) {
          final long lastUpdate = mClock.millis() - worker.getLastUpdatedTimeMs();
          if (lastUpdate > masterWorkerTimeoutMs) {
            LOG.warn("The worker {} timed out after {}ms without a heartbeat!", worker, lastUpdate);
            lostWorkers.add(worker);
            for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
              planCoordinator.failTasksForWorker(worker.getId());
            }
          }
        }
      }
      // Remove lost workers
      if (!lostWorkers.isEmpty()) {
        // Run under exclusive lock for mWorkers
        try (LockResource workersLockExclusive = new LockResource(mWorkerRWLock.writeLock())) {
          for (MasterWorkerInfo lostWorker : lostWorkers) {
            // Check last update time for lost workers again as it could have been changed while
            // waiting for exclusive lock.
            final long lastUpdate = mClock.millis() - lostWorker.getLastUpdatedTimeMs();
            if (lastUpdate > masterWorkerTimeoutMs) {
              mWorkerHealth.remove(lostWorker.getId());
              mWorkers.remove(lostWorker);
            }
          }
        }
      }
    }

    @Override
    public void close() {
      // Nothing to clean up
    }
  }
}
