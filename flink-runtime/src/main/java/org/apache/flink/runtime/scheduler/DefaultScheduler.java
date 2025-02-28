/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.runtime.scheduler;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.checkpoint.CheckpointRecoveryFactory;
import org.apache.flink.runtime.checkpoint.CheckpointsCleaner;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.executiongraph.TaskExecutionStateTransition;
import org.apache.flink.runtime.executiongraph.failover.flip1.ExecutionFailureHandler;
import org.apache.flink.runtime.executiongraph.failover.flip1.FailoverStrategy;
import org.apache.flink.runtime.executiongraph.failover.flip1.FailureHandlingResult;
import org.apache.flink.runtime.executiongraph.failover.flip1.RestartBackoffTimeStrategy;
import org.apache.flink.runtime.io.network.partition.PartitionException;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.operators.coordination.OperatorCoordinatorHolder;
import org.apache.flink.runtime.scheduler.exceptionhistory.FailureHandlingResultSnapshot;
import org.apache.flink.runtime.scheduler.strategy.ExecutionVertexID;
import org.apache.flink.runtime.scheduler.strategy.SchedulingStrategy;
import org.apache.flink.runtime.scheduler.strategy.SchedulingStrategyFactory;
import org.apache.flink.runtime.scheduler.strategy.SchedulingTopology;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.runtime.topology.Vertex;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.IterableUtils;
import org.apache.flink.util.concurrent.FutureUtils;
import org.apache.flink.util.concurrent.ScheduledExecutor;

import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/** The future default scheduler. */
public class DefaultScheduler extends SchedulerBase implements SchedulerOperations {

    protected final Logger log;

    private final ClassLoader userCodeLoader;

    private final ExecutionSlotAllocator executionSlotAllocator;

    private final ExecutionFailureHandler executionFailureHandler;

    private final ScheduledExecutor delayExecutor;

    protected final SchedulingStrategy schedulingStrategy;

    private final ExecutionVertexOperations executionVertexOperations;

    private final Set<ExecutionVertexID> verticesWaitingForRestart;

    private final ShuffleMaster<?> shuffleMaster;

    private final Time rpcTimeout;

    private final Map<AllocationID, Long> reservedAllocationRefCounters;

    // once an execution vertex is assigned an allocation/slot, it will reserve the allocation
    // until it is assigned a new allocation, or it finishes and does not need the allocation
    // anymore. The reserved allocation information is needed for local recovery.
    private final Map<ExecutionVertexID, AllocationID> reservedAllocationByExecutionVertex;

    DefaultScheduler(
            final Logger log,
            final JobGraph jobGraph,
            final Executor ioExecutor,
            final Configuration jobMasterConfiguration,
            final Consumer<ComponentMainThreadExecutor> startUpAction,
            final ScheduledExecutor delayExecutor,
            final ClassLoader userCodeLoader,
            final CheckpointsCleaner checkpointsCleaner,
            final CheckpointRecoveryFactory checkpointRecoveryFactory,
            final JobManagerJobMetricGroup jobManagerJobMetricGroup,
            final SchedulingStrategyFactory schedulingStrategyFactory,
            final FailoverStrategy.Factory failoverStrategyFactory,
            final RestartBackoffTimeStrategy restartBackoffTimeStrategy,
            final ExecutionVertexOperations executionVertexOperations,
            final ExecutionVertexVersioner executionVertexVersioner,
            final ExecutionSlotAllocatorFactory executionSlotAllocatorFactory,
            long initializationTimestamp,
            final ComponentMainThreadExecutor mainThreadExecutor,
            final JobStatusListener jobStatusListener,
            final ExecutionGraphFactory executionGraphFactory,
            final ShuffleMaster<?> shuffleMaster,
            final Time rpcTimeout)
            throws Exception {
        this(
                log,
                jobGraph,
                ioExecutor,
                jobMasterConfiguration,
                startUpAction,
                delayExecutor,
                userCodeLoader,
                checkpointsCleaner,
                checkpointRecoveryFactory,
                jobManagerJobMetricGroup,
                schedulingStrategyFactory,
                failoverStrategyFactory,
                restartBackoffTimeStrategy,
                executionVertexOperations,
                executionVertexVersioner,
                executionSlotAllocatorFactory,
                initializationTimestamp,
                mainThreadExecutor,
                jobStatusListener,
                executionGraphFactory,
                shuffleMaster,
                rpcTimeout,
                computeVertexParallelismStore(jobGraph));
    }

    protected DefaultScheduler(
            final Logger log,
            final JobGraph jobGraph,
            final Executor ioExecutor,
            final Configuration jobMasterConfiguration,
            final Consumer<ComponentMainThreadExecutor> startUpAction,
            final ScheduledExecutor delayExecutor,
            final ClassLoader userCodeLoader,
            final CheckpointsCleaner checkpointsCleaner,
            final CheckpointRecoveryFactory checkpointRecoveryFactory,
            final JobManagerJobMetricGroup jobManagerJobMetricGroup,
            final SchedulingStrategyFactory schedulingStrategyFactory,
            final FailoverStrategy.Factory failoverStrategyFactory,
            final RestartBackoffTimeStrategy restartBackoffTimeStrategy,
            final ExecutionVertexOperations executionVertexOperations,
            final ExecutionVertexVersioner executionVertexVersioner,
            final ExecutionSlotAllocatorFactory executionSlotAllocatorFactory,
            long initializationTimestamp,
            final ComponentMainThreadExecutor mainThreadExecutor,
            final JobStatusListener jobStatusListener,
            final ExecutionGraphFactory executionGraphFactory,
            final ShuffleMaster<?> shuffleMaster,
            final Time rpcTimeout,
            final VertexParallelismStore vertexParallelismStore)
            throws Exception {

        super(
                log,
                jobGraph,
                ioExecutor,
                jobMasterConfiguration,
                checkpointsCleaner,
                checkpointRecoveryFactory,
                jobManagerJobMetricGroup,
                executionVertexVersioner,
                initializationTimestamp,
                mainThreadExecutor,
                jobStatusListener,
                executionGraphFactory,
                vertexParallelismStore);

        this.log = log;

        this.delayExecutor = checkNotNull(delayExecutor);
        this.userCodeLoader = checkNotNull(userCodeLoader);
        this.executionVertexOperations = checkNotNull(executionVertexOperations);
        this.shuffleMaster = checkNotNull(shuffleMaster);
        this.rpcTimeout = checkNotNull(rpcTimeout);

        this.reservedAllocationRefCounters = new HashMap<>();
        this.reservedAllocationByExecutionVertex = new HashMap<>();

        final FailoverStrategy failoverStrategy =
                failoverStrategyFactory.create(
                        getSchedulingTopology(), getResultPartitionAvailabilityChecker());
        log.info(
                "Using failover strategy {} for {} ({}).",
                failoverStrategy,
                jobGraph.getName(),
                jobGraph.getJobID());

        this.executionFailureHandler =
                new ExecutionFailureHandler(
                        getSchedulingTopology(), failoverStrategy, restartBackoffTimeStrategy);
        this.schedulingStrategy =
                schedulingStrategyFactory.createInstance(this, getSchedulingTopology());

        this.executionSlotAllocator =
                checkNotNull(executionSlotAllocatorFactory)
                        .createInstance(new DefaultExecutionSlotAllocationContext());

        this.verticesWaitingForRestart = new HashSet<>();
        startUpAction.accept(mainThreadExecutor);
    }

    // ------------------------------------------------------------------------
    // SchedulerNG
    // ------------------------------------------------------------------------

    @Override
    protected long getNumberOfRestarts() {
        return executionFailureHandler.getNumberOfRestarts();
    }

    @Override
    protected void cancelAllPendingSlotRequestsInternal() {
        IterableUtils.toStream(getSchedulingTopology().getVertices())
                .map(Vertex::getId)
                .forEach(executionSlotAllocator::cancel);
    }

    @Override
    protected void startSchedulingInternal() {
        log.info(
                "Starting scheduling with scheduling strategy [{}]",
                schedulingStrategy.getClass().getName());
        transitionToRunning();
        schedulingStrategy.startScheduling();
    }

    @Override
    protected void updateTaskExecutionStateInternal(
            final ExecutionVertexID executionVertexId,
            final TaskExecutionStateTransition taskExecutionState) {

        // once a task finishes, it will release the assigned allocation/slot and no longer
        // needs it. Therefore, it should stop reserving the slot so that other tasks are
        // possible to use the slot. Ideally, the `stopReserveAllocation` should happen
        // along with the release slot process. However, that process is hidden in the depth
        // of the ExecutionGraph, so we currently do it in DefaultScheduler after that process
        // is done.
        if (taskExecutionState.getExecutionState() == ExecutionState.FINISHED) {
            stopReserveAllocation(executionVertexId);
        }

        schedulingStrategy.onExecutionStateChange(
                executionVertexId, taskExecutionState.getExecutionState());
        maybeHandleTaskFailure(taskExecutionState, executionVertexId);
    }

    private void maybeHandleTaskFailure(
            final TaskExecutionStateTransition taskExecutionState,
            final ExecutionVertexID executionVertexId) {

        if (taskExecutionState.getExecutionState() == ExecutionState.FAILED) {
            final Throwable error = taskExecutionState.getError(userCodeLoader);
            handleTaskFailure(executionVertexId, error);
        }
    }

    private void handleTaskFailure(
            final ExecutionVertexID executionVertexId, @Nullable final Throwable error) {
        Throwable revisedError =
                maybeTranslateToCachedIntermediateDataSetException(error, executionVertexId);
        final long timestamp = System.currentTimeMillis();
        setGlobalFailureCause(revisedError, timestamp);
        notifyCoordinatorsAboutTaskFailure(executionVertexId, revisedError);
        final FailureHandlingResult failureHandlingResult =
                executionFailureHandler.getFailureHandlingResult(
                        executionVertexId, revisedError, timestamp);
        maybeRestartTasks(failureHandlingResult);
    }

    private Throwable maybeTranslateToCachedIntermediateDataSetException(
            @Nullable Throwable cause, ExecutionVertexID failedVertex) {
        if (!(cause instanceof PartitionException)) {
            return cause;
        }

        final List<IntermediateDataSetID> intermediateDataSetIdsToConsume =
                getExecutionJobVertex(failedVertex.getJobVertexId())
                        .getJobVertex()
                        .getIntermediateDataSetIdsToConsume();
        final IntermediateResultPartitionID failedPartitionId =
                ((PartitionException) cause).getPartitionId().getPartitionId();

        if (!intermediateDataSetIdsToConsume.contains(
                failedPartitionId.getIntermediateDataSetID())) {
            return cause;
        }

        return new CachedIntermediateDataSetCorruptedException(
                cause, Collections.singletonList(failedPartitionId.getIntermediateDataSetID()));
    }

    private void notifyCoordinatorsAboutTaskFailure(
            final ExecutionVertexID executionVertexId, @Nullable final Throwable error) {
        final ExecutionJobVertex jobVertex =
                getExecutionJobVertex(executionVertexId.getJobVertexId());
        final int subtaskIndex = executionVertexId.getSubtaskIndex();

        jobVertex.getOperatorCoordinators().forEach(c -> c.subtaskFailed(subtaskIndex, error));
    }

    @Override
    public void handleGlobalFailure(final Throwable error) {
        final long timestamp = System.currentTimeMillis();
        setGlobalFailureCause(error, timestamp);

        log.info("Trying to recover from a global failure.", error);
        final FailureHandlingResult failureHandlingResult =
                executionFailureHandler.getGlobalFailureHandlingResult(error, timestamp);
        maybeRestartTasks(failureHandlingResult);
    }

    private void maybeRestartTasks(final FailureHandlingResult failureHandlingResult) {
        if (failureHandlingResult.canRestart()) {
            restartTasksWithDelay(failureHandlingResult);
        } else {
            failJob(failureHandlingResult.getError(), failureHandlingResult.getTimestamp());
        }
    }

    private void restartTasksWithDelay(final FailureHandlingResult failureHandlingResult) {
        final Set<ExecutionVertexID> verticesToRestart =
                failureHandlingResult.getVerticesToRestart();

        final Set<ExecutionVertexVersion> executionVertexVersions =
                new HashSet<>(
                        executionVertexVersioner
                                .recordVertexModifications(verticesToRestart)
                                .values());
        final boolean globalRecovery = failureHandlingResult.isGlobalFailure();

        addVerticesToRestartPending(verticesToRestart);

        final CompletableFuture<?> cancelFuture = cancelTasksAsync(verticesToRestart);

        final FailureHandlingResultSnapshot failureHandlingResultSnapshot =
                FailureHandlingResultSnapshot.create(
                        failureHandlingResult,
                        id -> this.getExecutionVertex(id).getCurrentExecutionAttempt());
        delayExecutor.schedule(
                () ->
                        FutureUtils.assertNoException(
                                cancelFuture.thenRunAsync(
                                        () -> {
                                            archiveFromFailureHandlingResult(
                                                    failureHandlingResultSnapshot);
                                            restartTasks(executionVertexVersions, globalRecovery);
                                        },
                                        getMainThreadExecutor())),
                failureHandlingResult.getRestartDelayMS(),
                TimeUnit.MILLISECONDS);
    }

    private void addVerticesToRestartPending(final Set<ExecutionVertexID> verticesToRestart) {
        verticesWaitingForRestart.addAll(verticesToRestart);
        transitionExecutionGraphState(JobStatus.RUNNING, JobStatus.RESTARTING);
    }

    private void removeVerticesFromRestartPending(final Set<ExecutionVertexID> verticesToRestart) {
        verticesWaitingForRestart.removeAll(verticesToRestart);
        if (verticesWaitingForRestart.isEmpty()) {
            transitionExecutionGraphState(JobStatus.RESTARTING, JobStatus.RUNNING);
        }
    }

    private void restartTasks(
            final Set<ExecutionVertexVersion> executionVertexVersions,
            final boolean isGlobalRecovery) {
        final Set<ExecutionVertexID> verticesToRestart =
                executionVertexVersioner.getUnmodifiedExecutionVertices(executionVertexVersions);

        removeVerticesFromRestartPending(verticesToRestart);

        resetForNewExecutions(verticesToRestart);

        try {
            restoreState(verticesToRestart, isGlobalRecovery);
        } catch (Throwable t) {
            handleGlobalFailure(t);
            return;
        }

        schedulingStrategy.restartTasks(verticesToRestart);
    }

    private CompletableFuture<?> cancelTasksAsync(final Set<ExecutionVertexID> verticesToRestart) {
        // clean up all the related pending requests to avoid that immediately returned slot
        // is used to fulfill the pending requests of these tasks
        verticesToRestart.stream().forEach(executionSlotAllocator::cancel);

        final List<CompletableFuture<?>> cancelFutures =
                verticesToRestart.stream()
                        .map(this::cancelExecutionVertex)
                        .collect(Collectors.toList());

        return FutureUtils.combineAll(cancelFutures);
    }

    private CompletableFuture<?> cancelExecutionVertex(final ExecutionVertexID executionVertexId) {
        final ExecutionVertex vertex = getExecutionVertex(executionVertexId);

        notifyCoordinatorOfCancellation(vertex);

        return executionVertexOperations.cancel(vertex);
    }

    // ------------------------------------------------------------------------
    // SchedulerOperations
    // ------------------------------------------------------------------------

    @Override
    public void allocateSlotsAndDeploy(final List<ExecutionVertexID> verticesToDeploy) {
        validateDeploymentOptions(verticesToDeploy);

        final Map<ExecutionVertexID, ExecutionVertexVersion> requiredVersionByVertex =
                executionVertexVersioner.recordVertexModifications(verticesToDeploy);

        transitionToScheduled(verticesToDeploy);

        final List<SlotExecutionVertexAssignment> slotExecutionVertexAssignments =
                allocateSlots(verticesToDeploy);

        final List<DeploymentHandle> deploymentHandles =
                createDeploymentHandles(requiredVersionByVertex, slotExecutionVertexAssignments);

        waitForAllSlotsAndDeploy(deploymentHandles);
    }

    private void validateDeploymentOptions(final Collection<ExecutionVertexID> verticesToDeploy) {
        verticesToDeploy.stream()
                .map(this::getExecutionVertex)
                .forEach(
                        v ->
                                checkState(
                                        v.getExecutionState() == ExecutionState.CREATED,
                                        "expected vertex %s to be in CREATED state, was: %s",
                                        v.getID(),
                                        v.getExecutionState()));
    }

    private List<SlotExecutionVertexAssignment> allocateSlots(
            final List<ExecutionVertexID> verticesToDeploy) {
        return executionSlotAllocator.allocateSlotsFor(verticesToDeploy);
    }

    private static List<DeploymentHandle> createDeploymentHandles(
            final Map<ExecutionVertexID, ExecutionVertexVersion> requiredVersionByVertex,
            final List<SlotExecutionVertexAssignment> slotExecutionVertexAssignments) {

        return slotExecutionVertexAssignments.stream()
                .map(
                        slotExecutionVertexAssignment -> {
                            final ExecutionVertexID executionVertexId =
                                    slotExecutionVertexAssignment.getExecutionVertexId();
                            return new DeploymentHandle(
                                    requiredVersionByVertex.get(executionVertexId),
                                    slotExecutionVertexAssignment);
                        })
                .collect(Collectors.toList());
    }

    private void waitForAllSlotsAndDeploy(final List<DeploymentHandle> deploymentHandles) {
        FutureUtils.assertNoException(
                assignAllResourcesAndRegisterProducedPartitions(deploymentHandles)
                        .handle(deployAll(deploymentHandles)));
    }

    private CompletableFuture<Void> assignAllResourcesAndRegisterProducedPartitions(
            final List<DeploymentHandle> deploymentHandles) {
        final List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        for (DeploymentHandle deploymentHandle : deploymentHandles) {
            final CompletableFuture<Void> resultFuture =
                    deploymentHandle
                            .getSlotExecutionVertexAssignment()
                            .getLogicalSlotFuture()
                            .handle(assignResource(deploymentHandle))
                            .thenCompose(registerProducedPartitions(deploymentHandle))
                            .handle(
                                    (ignore, throwable) -> {
                                        if (throwable != null) {
                                            handleTaskDeploymentFailure(
                                                    deploymentHandle.getExecutionVertexId(),
                                                    throwable);
                                        }
                                        return null;
                                    });

            resultFutures.add(resultFuture);
        }
        return FutureUtils.waitForAll(resultFutures);
    }

    private BiFunction<Void, Throwable, Void> deployAll(
            final List<DeploymentHandle> deploymentHandles) {
        return (ignored, throwable) -> {
            propagateIfNonNull(throwable);
            for (final DeploymentHandle deploymentHandle : deploymentHandles) {
                final SlotExecutionVertexAssignment slotExecutionVertexAssignment =
                        deploymentHandle.getSlotExecutionVertexAssignment();
                final CompletableFuture<LogicalSlot> slotAssigned =
                        slotExecutionVertexAssignment.getLogicalSlotFuture();
                checkState(slotAssigned.isDone());

                FutureUtils.assertNoException(
                        slotAssigned.handle(deployOrHandleError(deploymentHandle)));
            }
            return null;
        };
    }

    private static void propagateIfNonNull(final Throwable throwable) {
        if (throwable != null) {
            throw new CompletionException(throwable);
        }
    }

    private BiFunction<LogicalSlot, Throwable, LogicalSlot> assignResource(
            final DeploymentHandle deploymentHandle) {
        final ExecutionVertexVersion requiredVertexVersion =
                deploymentHandle.getRequiredVertexVersion();
        final ExecutionVertexID executionVertexId = deploymentHandle.getExecutionVertexId();

        return (logicalSlot, throwable) -> {
            if (executionVertexVersioner.isModified(requiredVertexVersion)) {
                if (throwable == null) {
                    log.debug(
                            "Refusing to assign slot to execution vertex {} because this deployment was "
                                    + "superseded by another deployment",
                            executionVertexId);
                    releaseSlotIfPresent(logicalSlot);
                }
                return null;
            }

            // throw exception only if the execution version is not outdated.
            // this ensures that canceling a pending slot request does not fail
            // a task which is about to cancel in #restartTasksWithDelay(...)
            if (throwable != null) {
                throw new CompletionException(maybeWrapWithNoResourceAvailableException(throwable));
            }

            final ExecutionVertex executionVertex = getExecutionVertex(executionVertexId);
            executionVertex.tryAssignResource(logicalSlot);

            startReserveAllocation(executionVertexId, logicalSlot.getAllocationId());

            return logicalSlot;
        };
    }

    private void startReserveAllocation(
            ExecutionVertexID executionVertexId, AllocationID newAllocation) {

        // stop the previous allocation reservation if there is one
        stopReserveAllocation(executionVertexId);

        reservedAllocationByExecutionVertex.put(executionVertexId, newAllocation);
        reservedAllocationRefCounters.compute(
                newAllocation, (ignored, oldCount) -> oldCount == null ? 1 : oldCount + 1);
    }

    private void stopReserveAllocation(ExecutionVertexID executionVertexId) {
        final AllocationID priorAllocation =
                reservedAllocationByExecutionVertex.remove(executionVertexId);
        if (priorAllocation != null) {
            reservedAllocationRefCounters.compute(
                    priorAllocation, (ignored, oldCount) -> oldCount > 1 ? oldCount - 1 : null);
        }
    }

    private Function<LogicalSlot, CompletableFuture<Void>> registerProducedPartitions(
            final DeploymentHandle deploymentHandle) {
        final ExecutionVertexID executionVertexId = deploymentHandle.getExecutionVertexId();

        return logicalSlot -> {
            // a null logicalSlot means the slot assignment is skipped, in which case
            // the produced partition registration process can be skipped as well
            if (logicalSlot != null) {
                final ExecutionVertex executionVertex = getExecutionVertex(executionVertexId);

                final CompletableFuture<Void> partitionRegistrationFuture =
                        executionVertex
                                .getCurrentExecutionAttempt()
                                .registerProducedPartitions(logicalSlot.getTaskManagerLocation());

                return FutureUtils.orTimeout(
                        partitionRegistrationFuture,
                        rpcTimeout.toMilliseconds(),
                        TimeUnit.MILLISECONDS,
                        getMainThreadExecutor());
            } else {
                return FutureUtils.completedVoidFuture();
            }
        };
    }

    private void releaseSlotIfPresent(@Nullable final LogicalSlot logicalSlot) {
        if (logicalSlot != null) {
            logicalSlot.releaseSlot(null);
        }
    }

    private void handleTaskDeploymentFailure(
            final ExecutionVertexID executionVertexId, final Throwable error) {
        executionVertexOperations.markFailed(getExecutionVertex(executionVertexId), error);
    }

    private static Throwable maybeWrapWithNoResourceAvailableException(final Throwable failure) {
        final Throwable strippedThrowable = ExceptionUtils.stripCompletionException(failure);
        if (strippedThrowable instanceof TimeoutException) {
            return new NoResourceAvailableException(
                    "Could not allocate the required slot within slot request timeout. "
                            + "Please make sure that the cluster has enough resources.",
                    failure);
        } else {
            return failure;
        }
    }

    private BiFunction<Object, Throwable, Void> deployOrHandleError(
            final DeploymentHandle deploymentHandle) {
        final ExecutionVertexVersion requiredVertexVersion =
                deploymentHandle.getRequiredVertexVersion();
        final ExecutionVertexID executionVertexId = requiredVertexVersion.getExecutionVertexId();

        return (ignored, throwable) -> {
            if (executionVertexVersioner.isModified(requiredVertexVersion)) {
                log.debug(
                        "Refusing to deploy execution vertex {} because this deployment was "
                                + "superseded by another deployment",
                        executionVertexId);
                return null;
            }

            if (throwable == null) {
                deployTaskSafe(executionVertexId);
            } else {
                handleTaskDeploymentFailure(executionVertexId, throwable);
            }
            return null;
        };
    }

    private void deployTaskSafe(final ExecutionVertexID executionVertexId) {
        try {
            final ExecutionVertex executionVertex = getExecutionVertex(executionVertexId);
            executionVertexOperations.deploy(executionVertex);
        } catch (Throwable e) {
            handleTaskDeploymentFailure(executionVertexId, e);
        }
    }

    private void notifyCoordinatorOfCancellation(ExecutionVertex vertex) {
        // this method makes a best effort to filter out duplicate notifications, meaning cases
        // where
        // the coordinator was already notified for that specific task
        // we don't notify if the task is already FAILED, CANCELLING, or CANCELED

        final ExecutionState currentState = vertex.getExecutionState();
        if (currentState == ExecutionState.FAILED
                || currentState == ExecutionState.CANCELING
                || currentState == ExecutionState.CANCELED) {
            return;
        }

        for (OperatorCoordinatorHolder coordinator :
                vertex.getJobVertex().getOperatorCoordinators()) {
            coordinator.subtaskFailed(vertex.getParallelSubtaskIndex(), null);
        }
    }

    private class DefaultExecutionSlotAllocationContext implements ExecutionSlotAllocationContext {

        @Override
        public ResourceProfile getResourceProfile(final ExecutionVertexID executionVertexId) {
            return getExecutionVertex(executionVertexId).getResourceProfile();
        }

        @Override
        public Optional<AllocationID> findPriorAllocationId(
                final ExecutionVertexID executionVertexId) {
            return getExecutionVertex(executionVertexId).findLastAllocation();
        }

        @Override
        public SchedulingTopology getSchedulingTopology() {
            return DefaultScheduler.this.getSchedulingTopology();
        }

        @Override
        public Set<SlotSharingGroup> getLogicalSlotSharingGroups() {
            return getJobGraph().getSlotSharingGroups();
        }

        @Override
        public Set<CoLocationGroup> getCoLocationGroups() {
            return getJobGraph().getCoLocationGroups();
        }

        @Override
        public Collection<Collection<ExecutionVertexID>> getConsumedResultPartitionsProducers(
                ExecutionVertexID executionVertexId) {
            return inputsLocationsRetriever.getConsumedResultPartitionsProducers(executionVertexId);
        }

        @Override
        public Optional<CompletableFuture<TaskManagerLocation>> getTaskManagerLocation(
                ExecutionVertexID executionVertexId) {
            return inputsLocationsRetriever.getTaskManagerLocation(executionVertexId);
        }

        @Override
        public Optional<TaskManagerLocation> getStateLocation(ExecutionVertexID executionVertexId) {
            return stateLocationRetriever.getStateLocation(executionVertexId);
        }

        @Override
        public Set<AllocationID> getReservedAllocations() {
            return reservedAllocationRefCounters.keySet();
        }
    }
}
