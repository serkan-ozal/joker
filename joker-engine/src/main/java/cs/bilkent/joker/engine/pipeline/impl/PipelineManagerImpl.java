package cs.bilkent.joker.engine.pipeline.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import cs.bilkent.joker.engine.FlowStatus;
import cs.bilkent.joker.engine.config.JokerConfig;
import static cs.bilkent.joker.engine.config.JokerConfig.JOKER_THREAD_GROUP_NAME;
import cs.bilkent.joker.engine.exception.InitializationException;
import cs.bilkent.joker.engine.exception.JokerException;
import cs.bilkent.joker.engine.flow.FlowExecutionPlan;
import cs.bilkent.joker.engine.flow.PipelineId;
import cs.bilkent.joker.engine.flow.RegionDef;
import cs.bilkent.joker.engine.flow.RegionExecutionPlan;
import cs.bilkent.joker.engine.metric.PipelineMeter;
import cs.bilkent.joker.engine.partition.PartitionDistribution;
import cs.bilkent.joker.engine.partition.PartitionKeyExtractor;
import cs.bilkent.joker.engine.partition.PartitionKeyExtractorFactory;
import cs.bilkent.joker.engine.partition.PartitionService;
import cs.bilkent.joker.engine.pipeline.DownstreamTupleSender;
import cs.bilkent.joker.engine.pipeline.DownstreamTupleSenderFailureFlag;
import cs.bilkent.joker.engine.pipeline.OperatorReplica;
import cs.bilkent.joker.engine.pipeline.OperatorReplicaStatus;
import static cs.bilkent.joker.engine.pipeline.OperatorReplicaStatus.COMPLETING;
import static cs.bilkent.joker.engine.pipeline.OperatorReplicaStatus.INITIAL;
import static cs.bilkent.joker.engine.pipeline.OperatorReplicaStatus.RUNNING;
import cs.bilkent.joker.engine.pipeline.Pipeline;
import static cs.bilkent.joker.engine.pipeline.Pipeline.createAlreadyRunningPipeline;
import cs.bilkent.joker.engine.pipeline.PipelineManager;
import cs.bilkent.joker.engine.pipeline.PipelineReplica;
import cs.bilkent.joker.engine.pipeline.PipelineReplicaId;
import cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus;
import static cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus.ACTIVE;
import static cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus.CLOSED;
import cs.bilkent.joker.engine.pipeline.UpstreamContext;
import cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender.CompositeDownstreamTupleSender;
import cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender.DownstreamTupleSender1;
import cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender.DownstreamTupleSenderN;
import cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender.PartitionedDownstreamTupleSender1;
import cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender.PartitionedDownstreamTupleSenderN;
import cs.bilkent.joker.engine.region.Region;
import cs.bilkent.joker.engine.region.RegionManager;
import cs.bilkent.joker.engine.supervisor.Supervisor;
import cs.bilkent.joker.engine.tuplequeue.OperatorTupleQueue;
import static cs.bilkent.joker.engine.util.RegionUtil.getFirstOperator;
import cs.bilkent.joker.flow.FlowDef;
import cs.bilkent.joker.flow.Port;
import cs.bilkent.joker.operator.OperatorDef;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import static cs.bilkent.joker.operator.spec.OperatorType.PARTITIONED_STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATELESS;
import cs.bilkent.joker.utils.Pair;
import static java.lang.Integer.compare;
import static java.lang.Math.min;
import static java.util.Collections.reverse;
import static java.util.Collections.singletonList;
import static java.util.Collections.sort;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

@Singleton
@NotThreadSafe
public class PipelineManagerImpl implements PipelineManager
{

    private static final Logger LOGGER = LoggerFactory.getLogger( PipelineManagerImpl.class );

    private static final int DOWNSTREAM_TUPLE_SENDER_CONSTRUCTOR_COUNT = 2;

    private static final int INITIAL_FLOW_VERSION = -1;


    private final JokerConfig jokerConfig;

    private final RegionManager regionManager;

    private final PartitionService partitionService;

    private final PartitionKeyExtractorFactory partitionKeyExtractorFactory;

    private final DownstreamTupleSenderFailureFlag downstreamTupleSenderFailureFlag;

    private final ThreadGroup jokerThreadGroup;

    private final BiFunction<List<Pair<Integer, Integer>>, OperatorTupleQueue, DownstreamTupleSender>[]
            defaultDownstreamTupleSenderConstructors = new BiFunction[ 6 ];

    private final Function6<List<Pair<Integer, Integer>>, Integer, int[], OperatorTupleQueue[], PartitionKeyExtractor,
                                   DownstreamTupleSender>[] partitionedDownstreamTupleSenderConstructors = new Function6[ 6 ];

    private Supervisor supervisor;

    private int flowVersion = INITIAL_FLOW_VERSION;

    private FlowDef flow;

    private final Map<Integer, RegionExecutionPlan> regionExecutionPlans = new HashMap<>();

    private final Map<PipelineId, Pipeline> pipelines = new ConcurrentHashMap<>();

    private volatile FlowStatus status = FlowStatus.INITIAL;

    @Inject
    public PipelineManagerImpl ( final JokerConfig jokerConfig,
                                 final RegionManager regionManager,
                                 final PartitionService partitionService,
                                 final PartitionKeyExtractorFactory partitionKeyExtractorFactory,
                                 final DownstreamTupleSenderFailureFlag downstreamTupleSenderFailureFlag,
                                 @Named( JOKER_THREAD_GROUP_NAME ) final ThreadGroup jokerThreadGroup )
    {
        this.jokerConfig = jokerConfig;
        this.regionManager = regionManager;
        this.partitionService = partitionService;
        this.partitionKeyExtractorFactory = partitionKeyExtractorFactory;
        this.downstreamTupleSenderFailureFlag = downstreamTupleSenderFailureFlag;
        this.jokerThreadGroup = jokerThreadGroup;
        createDownstreamTupleSenderFactories();
    }

    @Inject
    public void setSupervisor ( final Supervisor supervisor )
    {
        this.supervisor = supervisor;
    }

    private void createDownstreamTupleSenderFactories ()
    {
        defaultDownstreamTupleSenderConstructors[ 1 ] = ( pairs, tupleQueue ) ->
        {
            final Pair<Integer, Integer> pair1 = pairs.get( 0 );
            return new DownstreamTupleSender1( downstreamTupleSenderFailureFlag, pair1._1, pair1._2, tupleQueue );
        };
        defaultDownstreamTupleSenderConstructors[ 2 ] = ( pairs, tupleQueue ) ->
        {
            final int[] sourcePorts = new int[ pairs.size() ];
            final int[] destinationPorts = new int[ pairs.size() ];
            copyPorts( pairs, sourcePorts, destinationPorts );
            return new DownstreamTupleSenderN( downstreamTupleSenderFailureFlag, sourcePorts, destinationPorts, tupleQueue );
        };
        partitionedDownstreamTupleSenderConstructors[ 1 ] = ( pairs, partitionCount, partitionDistribution, tupleQueues,
                                                              partitionKeyFunction ) ->
        {
            final Pair<Integer, Integer> pair1 = pairs.get( 0 );
            return new PartitionedDownstreamTupleSender1( downstreamTupleSenderFailureFlag,
                                                          pair1._1,
                                                          pair1._2,
                                                          partitionCount,
                                                          partitionDistribution,
                                                          tupleQueues,
                                                          partitionKeyFunction );
        };
        partitionedDownstreamTupleSenderConstructors[ 2 ] = ( pairs, partitionCount, partitionDistribution, tupleQueues,
                                                              partitionKeyFunction ) ->
        {
            final int[] sourcePorts = new int[ pairs.size() ];
            final int[] destinationPorts = new int[ pairs.size() ];
            copyPorts( pairs, sourcePorts, destinationPorts );
            return new PartitionedDownstreamTupleSenderN( downstreamTupleSenderFailureFlag,
                                                          sourcePorts,
                                                          destinationPorts,
                                                          partitionCount,
                                                          partitionDistribution,
                                                          tupleQueues,
                                                          partitionKeyFunction );
        };
    }

    public List<Pipeline> getPipelines ()
    {
        return new ArrayList<>( pipelines.values() );
    }

    List<Pipeline> createPipelines ( final FlowDef flow, final List<RegionExecutionPlan> regionExecutionPlans )
    {
        final List<Pipeline> pipelines = createRegions( flow, regionExecutionPlans );
        createDownstreamTupleSenders( flow );
        createUpstreamContexts( flow );

        return pipelines;
    }

    @Override
    public void start ( final FlowDef flow, final List<RegionExecutionPlan> regionExecutionPlans )
    {
        try
        {
            checkState( status == FlowStatus.INITIAL, "cannot start pipeline replica runner threads since status is %s", status );
            this.flow = flow;
            createPipelines( flow, regionExecutionPlans );
            initPipelines();
            startPipelineReplicaRunners( supervisor );
            status = FlowStatus.RUNNING;
            incrementFlowVersion();
        }
        catch ( Exception e )
        {
            status = FlowStatus.INITIALIZATION_FAILED;
            throw new InitializationException( "Flow start failed", e );
        }
    }

    @Override
    public FlowExecutionPlan getFlowExecutionPlan ()
    {
        final FlowStatus status = this.status;
        checkState( status == FlowStatus.RUNNING || status == FlowStatus.SHUTTING_DOWN, "cannot get flow execution plan since status is %s",
                    status );
        return new FlowExecutionPlan( flowVersion, flow, regionExecutionPlans.values() );
    }

    @Override
    public List<PipelineMeter> getAllPipelineMetersOrFail ()
    {
        final List<PipelineMeter> pipelineMeters = pipelines.values().stream().map( Pipeline::getPipelineMeter ).collect( toList() );
        checkState( pipelineMeters.size() > 0, "there is no pipeline meters!" );
        return pipelineMeters;
    }

    @Override
    public List<PipelineMeter> getRegionPipelineMetersOrFail ( final int regionId )
    {
        final List<PipelineMeter> meters = pipelines.values()
                                                    .stream()
                                                    .filter( p -> p.getRegionDef().getRegionId() == regionId )
                                                    .map( Pipeline::getPipelineMeter )
                                                    .collect( toList() );
        checkArgument( meters.size() > 0, "No pipelines found for regionId=%s", regionId );
        return meters;
    }

    @Override
    public PipelineMeter getPipelineMeterOrFail ( final PipelineId pipelineId )
    {
        final Pipeline pipeline = pipelines.get( pipelineId );
        checkArgument( pipeline != null, "Pipeline %s not found", pipelineId );

        return pipeline.getPipelineMeter();
    }

    private void initPipelines ()
    {
        final List<Pipeline> initialized = new ArrayList<>();
        try
        {
            for ( Pipeline pipeline : pipelines.values() )
            {
                initialized.add( pipeline );
                pipeline.init();
            }
        }
        catch ( Exception e1 )
        {
            LOGGER.error( "Pipeline initialization failed.", e1 );
            reverse( initialized );
            LOGGER.error( "Shutting down {} pipelines.", initialized.size() );
            for ( Pipeline pipeline : initialized )
            {
                try
                {
                    LOGGER.info( "Shutting down Pipeline {}", pipeline.getId() );
                    pipeline.shutdown();
                }
                catch ( Exception e2 )
                {
                    LOGGER.error( "Shutdown of Pipeline " + pipeline.getId() + " failed", e2 );
                }
            }

            releaseRegions();

            throw e1;
        }
    }

    @Override
    public UpstreamContext getUpstreamContext ( final PipelineReplicaId id )
    {
        final Pipeline pipeline = getPipelineOrFail( id.pipelineId );
        return pipeline.getUpstreamContext();
    }

    @Override
    public DownstreamTupleSender getDownstreamTupleSender ( final PipelineReplicaId id )
    {
        final Pipeline pipeline = getPipelineOrFail( id.pipelineId );
        return pipeline.getDownstreamTupleSender( id.replicaIndex );
    }

    @Override
    public boolean handlePipelineReplicaCompleted ( final PipelineReplicaId id )
    {
        checkState( status == FlowStatus.SHUTTING_DOWN, "cannot notify Pipeline Replica %s completion since status is %s", id, status );

        final Pipeline pipeline = getPipelineOrFail( id.pipelineId );

        if ( pipeline.handlePipelineReplicaCompleted( id.replicaIndex ) )
        {
            for ( Pipeline downstreamPipeline : getDownstreamPipelines( pipeline ) )
            {
                final UpstreamContext updatedUpstreamContext = getUpdatedUpstreamContext( downstreamPipeline );
                if ( updatedUpstreamContext != null )
                {
                    downstreamPipeline.handleUpstreamContextUpdated( updatedUpstreamContext );
                }
                else
                {
                    LOGGER.info( "Upstream Pipeline {} is completed but {} of Downstream Pipeline {} is same.",
                                 id,
                                 downstreamPipeline.getUpstreamContext(),
                                 downstreamPipeline.getId() );
                }
            }
        }

        return gracefullyShutdownIfAllPipelinesCompleted();
    }

    private Collection<Pipeline> getDownstreamPipelines ( final Pipeline upstreamPipeline )
    {
        return flow.getOutboundConnections( upstreamPipeline.getLastOperatorDef().getId() )
                   .values()
                   .stream()
                   .flatMap( ports -> ports.stream().map( Port::getOperatorId ) )
                   .distinct()
                   .map( flow::getOperator )
                   .map( this::getPipelineByFirstOperatorOrFail )
                   .collect( toList() );
    }

    private UpstreamContext getUpdatedUpstreamContext ( final Pipeline pipeline )
    {
        final UpstreamConnectionStatus[] upstreamConnectionStatuses = getUpstreamConnectionStatuses( pipeline );
        final UpstreamContext current = pipeline.getUpstreamContext();
        boolean update = false;
        for ( int i = 0; i < upstreamConnectionStatuses.length; i++ )
        {
            if ( upstreamConnectionStatuses[ i ] != current.getUpstreamConnectionStatus( i ) )
            {
                update = true;
                break;
            }
        }

        UpstreamContext upstreamContext = null;
        if ( update )
        {
            upstreamContext = new UpstreamContext( current.getVersion() + 1, upstreamConnectionStatuses );
        }

        return upstreamContext;
    }

    private UpstreamConnectionStatus[] getUpstreamConnectionStatuses ( final Pipeline pipeline )
    {
        final OperatorDef firstOperator = pipeline.getFirstOperatorDef();
        final UpstreamConnectionStatus[] statuses = new UpstreamConnectionStatus[ firstOperator.getInputPortCount() ];
        for ( Entry<Port, Set<Port>> entry : flow.getInboundConnections( firstOperator.getId() ).entrySet() )
        {
            final Collection<Port> upstreamPorts = entry.getValue();
            final UpstreamConnectionStatus status;
            if ( upstreamPorts.isEmpty() )
            {
                status = CLOSED;
            }
            else
            {
                final List<Pair<OperatorDef, Pipeline>> upstream = upstreamPorts.stream().map( p -> flow.getOperator( p.getOperatorId() ) )
                                                                                .map( o -> Pair.of( o, getPipelineOrFail( o ) ) )
                                                                                .collect( toList() );

                upstream.forEach( p -> checkState( p._2.getOperatorIndex( p._1 ) == p._2.getOperatorCount() - 1 ) );

                final boolean aliveConnPresent = upstream.stream().anyMatch( p ->
                                                                             {
                                                                                 final OperatorReplicaStatus s = p._2.getPipelineStatus();
                                                                                 return s == OperatorReplicaStatus.INITIAL
                                                                                        || s == OperatorReplicaStatus.RUNNING
                                                                                        || s == OperatorReplicaStatus.COMPLETING;
                                                                             } );
                status = aliveConnPresent ? UpstreamConnectionStatus.ACTIVE : UpstreamConnectionStatus.CLOSED;
            }

            statuses[ entry.getKey().getPortIndex() ] = status;
        }

        return statuses;
    }

    private boolean gracefullyShutdownIfAllPipelinesCompleted ()
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            if ( pipeline.getPipelineStatus() != OperatorReplicaStatus.COMPLETED )
            {
                return false;
            }
        }

        LOGGER.info( "All pipelines completed." );
        shutdownGracefully( null );

        return true;
    }

    @Override
    public void triggerShutdown ()
    {
        checkState( status == FlowStatus.RUNNING, "cannot trigger shutdown since status is %s", status );
        LOGGER.info( "Shutdown request is being handled." );

        status = FlowStatus.SHUTTING_DOWN;

        for ( Pipeline pipeline : pipelines.values() )
        {
            final OperatorDef operatorDef = pipeline.getFirstOperatorDef();
            if ( flow.getInboundConnections( operatorDef.getId() ).isEmpty() )
            {
                pipeline.handleUpstreamContextUpdated( new UpstreamContext( 1, new UpstreamConnectionStatus[] {} ) );
            }
        }
    }

    @Override
    public void mergePipelines ( final int flowVersion, final List<PipelineId> pipelineIds )
    {
        checkArgument( flowVersion == this.flowVersion,
                       "cannot merge pipelines %s since given flow version %s is not equal to the flow version: %s",
                       pipelineIds,
                       flowVersion,
                       this.flowVersion );
        checkState( status == FlowStatus.RUNNING,
                    "cannot merge pipelines %s with flow version %s since status is %s",
                    pipelineIds,
                    flowVersion,
                    status );

        LOGGER.info( "Will try to merge pipelines {} with flow version: {}", pipelineIds, flowVersion );

        regionManager.validatePipelineMergeParameters( pipelineIds );

        final PipelineId firstPipelineId = pipelineIds.get( 0 );
        final Pipeline firstPipeline = pipelines.get( firstPipelineId );
        final SchedulingStrategy initialSchedulingStrategy = firstPipeline.getInitialSchedulingStrategy();
        final UpstreamContext upstreamContext = firstPipeline.getUpstreamContext();

        stopAndReleasePipelines( pipelineIds );

        try
        {
            final Region region = regionManager.mergePipelines( pipelineIds );
            regionExecutionPlans.put( region.getRegionId(), region.getExecutionPlan() );
            final PipelineReplica[] pipelineReplicas = region.getPipelineReplicasByPipelineId( firstPipelineId );
            final Pipeline pipeline = createAlreadyRunningPipeline( firstPipelineId,
                                                                    region.getExecutionPlan(),
                                                                    pipelineReplicas,
                                                                    initialSchedulingStrategy,
                                                                    upstreamContext );
            addPipeline( pipeline );
            createDownstreamTupleSenders( flow, pipeline );
            pipeline.startPipelineReplicaRunners( jokerConfig, supervisor, jokerThreadGroup );
            incrementFlowVersion();
        }
        catch ( Exception e )
        {
            throw new JokerException( "Failed during merging pipelines: " + pipelineIds + " with flow version: " + flowVersion, e );
        }
    }

    @Override
    public void splitPipeline ( final int flowVersion, final PipelineId pipelineIdToSplit, final List<Integer> pipelineOperatorIndices )
    {
        checkArgument( flowVersion == this.flowVersion,
                       "cannot split pipeline %s to %s since given flow version %s is not equal to the flow version: %s",
                       pipelineIdToSplit,
                       pipelineOperatorIndices,
                       flowVersion,
                       this.flowVersion );
        checkState( status == FlowStatus.RUNNING,
                    "cannot split pipeline %s into %s with flow version %s since status is %s",
                    pipelineIdToSplit,
                    pipelineOperatorIndices,
                    flowVersion,
                    status );

        LOGGER.info( "Will try to split pipeline: {} into: {} with flow version {}",
                     pipelineIdToSplit,
                     pipelineOperatorIndices,
                     flowVersion );

        regionManager.validatePipelineSplitParameters( pipelineIdToSplit, pipelineOperatorIndices );

        stopAndReleasePipelines( singletonList( pipelineIdToSplit ) );

        try
        {
            final Region region = regionManager.splitPipeline( pipelineIdToSplit, pipelineOperatorIndices );
            regionExecutionPlans.put( region.getRegionId(), region.getExecutionPlan() );
            final List<Pipeline> newPipelines = new ArrayList<>();
            for ( int pipelineIndex = 0; pipelineIndex < region.getExecutionPlan().getPipelineCount(); pipelineIndex++ )
            {
                final PipelineReplica[] pipelineReplicas = region.getPipelineReplicas( pipelineIndex );
                final PipelineReplica pipelineReplica = pipelineReplicas[ 0 ];
                final PipelineId pipelineId = pipelineReplica.id().pipelineId;
                if ( !pipelines.containsKey( pipelineId ) )
                {
                    final Pipeline pipeline = createAlreadyRunningPipeline( pipelineId,
                                                                            region.getExecutionPlan(),
                                                                            pipelineReplicas,
                                                                            pipelineReplica.getOperator( 0 ).getInitialSchedulingStrategy(),
                                                                            pipelineReplica.getPipelineUpstreamContext() );
                    addPipeline( pipeline );
                    newPipelines.add( pipeline );
                }
            }

            for ( Pipeline pipeline : newPipelines )
            {
                createDownstreamTupleSenders( flow, pipeline );
            }

            for ( Pipeline pipeline : newPipelines )
            {
                LOGGER.info( "Starting new pipeline {}", pipeline.getId() );
                pipeline.startPipelineReplicaRunners( jokerConfig, supervisor, jokerThreadGroup );
            }

            incrementFlowVersion();
        }
        catch ( Exception e )
        {
            throw new JokerException( "Failed during splitting pipeline: " + pipelineIdToSplit + " into: " + pipelineOperatorIndices
                                      + " with flow version: " + flowVersion, e );
        }
    }

    private void incrementFlowVersion ()
    {
        flowVersion++;
        LOGGER.info( "Flow version is updated to {}", flowVersion );
    }

    @Override
    public void rebalanceRegion ( final int flowVersion, final int regionId, final int newReplicaCount )
    {
        checkArgument( flowVersion == this.flowVersion,
                       "cannot rebalance region %s to %s replicas since given flow version %s is not equal to the flow version: %s",
                       regionId,
                       newReplicaCount,
                       flowVersion,
                       this.flowVersion );
        checkState( status == FlowStatus.RUNNING,
                    "cannot rebalance region %s to %s replicas with flow version %s since status is %s",
                    regionId,
                    newReplicaCount,
                    flowVersion,
                    status );
        final RegionDef regionDef = getRegionDefOrFail( regionId );
        checkArgument( newReplicaCount > 0,
                       "cannot rebalance region %s with flow version %s since new replica count is %s",
                       regionId,
                       flowVersion,
                       newReplicaCount );
        checkArgument( regionDef.getRegionType() == PARTITIONED_STATEFUL,
                       "cannot rebalance region %s to new replica count %s with flow version %s since region is %s",
                       regionId,
                       newReplicaCount,
                       flowVersion,
                       regionDef.getRegionType() );

        LOGGER.info( "Will try to rebalance region: {} new replica count: {} with flow version {}",
                     regionId,
                     newReplicaCount,
                     flowVersion );

        final Collection<Pipeline> upstreamPipelines = pauseUpstreamPipelines( regionDef );
        stopAndReleasePipelines( regionDef );

        try
        {
            final List<Pipeline> newPipelines = new ArrayList<>();
            final Region region = regionManager.rebalanceRegion( flow, regionDef.getRegionId(), newReplicaCount );
            regionExecutionPlans.put( region.getRegionId(), region.getExecutionPlan() );

            for ( int pipelineIndex = 0; pipelineIndex < region.getExecutionPlan().getPipelineCount(); pipelineIndex++ )
            {
                final PipelineReplica[] pipelineReplicas = region.getPipelineReplicas( pipelineIndex );
                final PipelineReplica pipelineReplica = pipelineReplicas[ 0 ];
                final PipelineId pipelineId = pipelineReplica.id().pipelineId;
                final Pipeline pipeline = createAlreadyRunningPipeline( pipelineId,
                                                                        region.getExecutionPlan(),
                                                                        pipelineReplicas,
                                                                        pipelineReplica.getOperator( 0 ).getInitialSchedulingStrategy(),
                                                                        pipelineReplica.getPipelineUpstreamContext() );
                addPipeline( pipeline );
                newPipelines.add( pipeline );
            }

            for ( Pipeline pipeline : newPipelines )
            {
                createDownstreamTupleSenders( flow, pipeline );
            }

            for ( Pipeline pipeline : newPipelines )
            {
                LOGGER.info( "Starting new pipeline {}", pipeline.getId() );
                pipeline.startPipelineReplicaRunners( jokerConfig, supervisor, jokerThreadGroup );
            }

            for ( Pipeline pausedPipeline : upstreamPipelines )
            {
                createDownstreamTupleSenders( flow, pausedPipeline );
            }

            incrementFlowVersion();

            resumePipelines( upstreamPipelines );
        }
        catch ( Exception e )
        {
            throw new JokerException( "Failed during rebalancing region: " + regionId + " to new replica count: " + newReplicaCount
                                      + " with flow version: " + flowVersion, e );
        }
    }

    private RegionDef getRegionDefOrFail ( final int regionId )
    {
        final RegionExecutionPlan regionExecutionPlan = regionExecutionPlans.get( regionId );
        checkArgument( regionExecutionPlan != null, " region: %s not found!", regionId );
        return regionExecutionPlan.getRegionDef();
    }

    private void addPipeline ( final Pipeline pipeline )
    {
        checkState( this.pipelines.put( pipeline.getId(), pipeline ) == null,
                    "there are multiple pipelines with same id: %s",
                    pipeline.getId() );
    }

    private void stopAndReleasePipelines ( final List<PipelineId> pipelineIds )
    {
        for ( PipelineId pipelineId : pipelineIds )
        {
            checkArgument( pipelines.containsKey( pipelineId ), "pipeline not found for pipeline id %s to merge", pipelineId );
        }

        for ( final PipelineId pipelineId : pipelineIds )
        {
            LOGGER.info( "Stopping pipeline {}...", pipelineId );

            final Pipeline pipeline = pipelines.get( pipelineId );

            final long runnerStopTimeoutInMillis = jokerConfig.getPipelineManagerConfig().getRunnerCommandTimeoutInMillis();
            final List<Exception> failures = pipeline.stopPipelineReplicaRunners( runnerStopTimeoutInMillis );
            pipelines.remove( pipelineId );
            LOGGER.info( "Pipeline {} is released...", pipelineId );
            if ( !failures.isEmpty() )
            {
                throw new JokerException( "Failed during stopping pipeline " + pipelineId + " replica runners!" );
            }
        }
    }

    private void stopAndReleasePipelines ( final RegionDef regionDef )
    {
        final List<PipelineId> pipelineIds = new ArrayList<>();
        for ( Pipeline pipeline : this.pipelines.values() )
        {
            if ( regionDef.getRegionId() == pipeline.getId().getRegionId() )
            {
                pipelineIds.add( pipeline.getId() );
            }
        }

        pipelineIds.sort( PipelineId::compareTo );
        stopAndReleasePipelines( pipelineIds );
    }

    private Collection<Pipeline> pauseUpstreamPipelines ( final RegionDef regionDef )
    {
        final Collection<Pipeline> upstream = new ArrayList<>();
        final Set<String> upstreamOperatorIds = new HashSet<>();
        for ( Collection<Port> c : flow.getInboundConnections( getFirstOperator( regionDef ).getId() ).values() )
        {
            for ( Port p : c )
            {
                upstreamOperatorIds.add( p.getOperatorId() );
            }
        }

        for ( Pipeline pipeline : pipelines.values() )
        {
            if ( upstreamOperatorIds.contains( pipeline.getLastOperatorDef().getId() ) )
            {
                final long runnerStopTimeoutInMillis = jokerConfig.getPipelineManagerConfig().getRunnerCommandTimeoutInMillis();
                final List<Exception> failures = pipeline.pausePipelineReplicaRunners( runnerStopTimeoutInMillis );
                if ( failures.isEmpty() )
                {
                    upstream.add( pipeline );
                }
                else
                {
                    throw new JokerException( "Failed during pausing pipeline " + pipeline.getId() + " replica runners!" );
                }
            }
        }

        return upstream;
    }

    private void resumePipelines ( final Collection<Pipeline> pipelines )
    {
        for ( Pipeline pipeline : pipelines )
        {
            final long runnerStopTimeoutInMillis = jokerConfig.getPipelineManagerConfig().getRunnerCommandTimeoutInMillis();
            final List<Exception> failures = pipeline.resumePipelineReplicaRunners( runnerStopTimeoutInMillis );
            if ( !failures.isEmpty() )
            {
                throw new JokerException( "Failed during resuming pipeline " + pipeline.getId() + " replica runners!" );
            }
        }
    }

    private void releaseRegions ()
    {
        final Multimap<Integer, PipelineId> regionIds = HashMultimap.create();
        for ( PipelineId id : pipelines.keySet() )
        {
            regionIds.put( id.getRegionId(), id );
        }

        for ( Integer regionId : new ArrayList<>( regionIds.keySet() ) )
        {
            for ( PipelineId pipelineId : regionIds.removeAll( regionId ) )
            {
                LOGGER.info( "Removing Pipeline {}", pipelineId );
                pipelines.remove( pipelineId );
            }

            regionManager.releaseRegion( regionId );
        }
    }

    private List<Pipeline> createRegions ( final FlowDef flow, final Collection<RegionExecutionPlan> regionExecutionPlans )
    {
        final List<Pipeline> pipelines = new ArrayList<>();
        for ( RegionExecutionPlan regionExecutionPlan : regionExecutionPlans )
        {
            LOGGER.info( "Initializing regionId={} with {} pipelines ( {} ) and {} replicas",
                         regionExecutionPlan.getRegionId(),
                         regionExecutionPlan.getPipelineCount(),
                         regionExecutionPlan.getPipelineStartIndices(),
                         regionExecutionPlan.getReplicaCount() );

            final Region region = regionManager.createRegion( flow, regionExecutionPlan );
            this.regionExecutionPlans.put( region.getRegionId(), regionExecutionPlan );
            for ( int pipelineIndex = 0; pipelineIndex < regionExecutionPlan.getPipelineCount(); pipelineIndex++ )
            {
                final PipelineReplica[] pipelineReplicas = region.getPipelineReplicas( pipelineIndex );
                final PipelineId pipelineId = pipelineReplicas[ 0 ].id().pipelineId;
                final Pipeline pipeline = new Pipeline( pipelineId, regionExecutionPlan, pipelineReplicas );
                verifyInitialSchedulingStrategyTupleCounts( pipeline );
                addPipeline( pipeline );
                pipelines.add( pipeline );
            }

            LOGGER.info( "regionId={} is created", regionExecutionPlan.getRegionId() );
        }

        return pipelines;
    }

    private void verifyInitialSchedulingStrategyTupleCounts ( final Pipeline pipeline )
    {
        for ( int replicaIndex = 0; replicaIndex < pipeline.getReplicaCount(); replicaIndex++ )
        {
            final PipelineReplica pipelineReplica = pipeline.getPipelineReplica( replicaIndex );
            for ( OperatorReplica operatorReplica : pipelineReplica.getOperators() )
            {
                final SchedulingStrategy schedulingStrategy = operatorReplica.getInitialSchedulingStrategy();
                if ( schedulingStrategy instanceof ScheduleWhenTuplesAvailable )
                {
                    final ScheduleWhenTuplesAvailable s = (ScheduleWhenTuplesAvailable) schedulingStrategy;
                    for ( int tupleCount : s.getTupleCounts() )
                    {
                        checkState( tupleCount <= jokerConfig.getTupleQueueManagerConfig().getTupleQueueCapacity(),
                                    "Tuple queues are too small for scheduling strategy: %s of Operator=%s",
                                    operatorReplica.getOperatorName(),
                                    schedulingStrategy );
                    }
                }
            }
        }
    }

    private void createDownstreamTupleSenders ( final FlowDef flow )
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            createDownstreamTupleSenders( flow, pipeline );
        }
    }

    private void createDownstreamTupleSenders ( final FlowDef flow, final Pipeline pipeline )
    {
        final OperatorDef lastOperator = pipeline.getLastOperatorDef();
        final Map<String, List<Pair<Integer, Integer>>> connectionsByOperatorId = getDownstreamConnectionsByOperatorId( flow,
                                                                                                                        lastOperator );
        LOGGER.info( "Pipeline {} with last operator {} has following downstream connectionsByOperatorId: {}",
                     pipeline.getId(),
                     lastOperator.getId(),
                     connectionsByOperatorId );

        final DownstreamTupleSender[] senders = new DownstreamTupleSender[ pipeline.getReplicaCount() ];
        for ( int replicaIndex = 0; replicaIndex < pipeline.getReplicaCount(); replicaIndex++ )
        {
            final DownstreamTupleSender[] sendersToDownstreamOperators = new DownstreamTupleSender[ connectionsByOperatorId.size() ];
            int i = 0;
            for ( Entry<String, List<Pair<Integer, Integer>>> e : connectionsByOperatorId.entrySet() )
            {
                final String downstreamOperatorId = e.getKey();
                final List<Pair<Integer, Integer>> pairs = e.getValue();
                final OperatorDef downstreamOperator = flow.getOperator( downstreamOperatorId );
                final Pipeline downstreamPipeline = getPipeline( downstreamOperator, 0 );
                final RegionDef downstreamRegionDef = downstreamPipeline.getRegionDef();
                final OperatorTupleQueue[] pipelineTupleQueues = getPipelineTupleQueues( downstreamOperator );
                final int j = min( pairs.size(), DOWNSTREAM_TUPLE_SENDER_CONSTRUCTOR_COUNT );

                if ( pipeline.getId().getRegionId() == downstreamPipeline.getId().getRegionId() )
                {
                    final OperatorTupleQueue pipelineTupleQueue = pipelineTupleQueues[ replicaIndex ];
                    sendersToDownstreamOperators[ i ] = defaultDownstreamTupleSenderConstructors[ j ].apply( pairs, pipelineTupleQueue );
                }
                else if ( downstreamRegionDef.getRegionType() == PARTITIONED_STATEFUL )
                {
                    final int[] partitionDistribution = getPartitionDistribution( downstreamOperator );
                    final PartitionKeyExtractor partitionKeyExtractor = partitionKeyExtractorFactory.createPartitionKeyExtractor(
                            downstreamRegionDef.getPartitionFieldNames() );
                    sendersToDownstreamOperators[ i ] = partitionedDownstreamTupleSenderConstructors[ j ].apply( pairs,
                                                                                                                 partitionService
                                                                                                                         .getPartitionCount(),
                                                                                                                 partitionDistribution,
                                                                                                                 pipelineTupleQueues,
                                                                                                                 partitionKeyExtractor );
                }
                else if ( downstreamRegionDef.getRegionType() == STATELESS )
                {
                    OperatorTupleQueue pipelineTupleQueue = null;
                    if ( pipeline.getReplicaCount() == downstreamPipeline.getReplicaCount() )
                    {
                        pipelineTupleQueue = pipelineTupleQueues[ replicaIndex ];
                    }
                    else if ( downstreamPipeline.getReplicaCount() == 1 )
                    {
                        pipelineTupleQueue = pipelineTupleQueues[ 0 ];
                    }

                    if ( pipelineTupleQueue != null )
                    {
                        sendersToDownstreamOperators[ i ] = defaultDownstreamTupleSenderConstructors[ j ].apply( pairs,
                                                                                                                 pipelineTupleQueue );
                    }
                    else
                    {
                        throw new IllegalStateException( "incompatible replica counts! pipeline: " + pipeline.getId() + " replica count: "
                                                         + pipeline.getReplicaCount() + " downstream pipeline: "
                                                         + downstreamPipeline.getId() + " downstream pipeline replica count: "
                                                         + downstreamPipeline.getReplicaCount() );
                    }
                }
                else if ( downstreamRegionDef.getRegionType() == STATEFUL )
                {
                    final int l = pipelineTupleQueues.length;
                    checkState( l == 1, "Operator %s can not have %s replicas", downstreamOperatorId, l );
                    final OperatorTupleQueue pipelineTupleQueue = pipelineTupleQueues[ 0 ];
                    sendersToDownstreamOperators[ i ] = defaultDownstreamTupleSenderConstructors[ j ].apply( pairs, pipelineTupleQueue );
                }
                else
                {
                    throw new IllegalStateException( "invalid region type: " + downstreamRegionDef.getRegionType() );
                }

                i++;
            }

            DownstreamTupleSender sender;
            if ( i == 0 )
            {
                sender = new NopDownstreamTupleSender();
            }
            else if ( i == 1 )
            {
                sender = sendersToDownstreamOperators[ 0 ];
            }
            else
            {
                sender = new CompositeDownstreamTupleSender( sendersToDownstreamOperators );
            }

            LOGGER.info( "Created {} for Pipeline {} replica index {}", sender.getClass().getSimpleName(), pipeline.getId(), replicaIndex );

            senders[ replicaIndex ] = sender;
        }

        pipeline.setDownstreamTupleSenders( senders );
    }

    private Map<String, List<Pair<Integer, Integer>>> getDownstreamConnectionsByOperatorId ( final FlowDef flow,
                                                                                             final OperatorDef operator )
    {
        final Map<String, List<Pair<Integer, Integer>>> result = new LinkedHashMap<>();
        final Map<Port, Set<Port>> connections = flow.getOutboundConnections( operator.getId() );
        final Set<String> downstreamOperatorIds = new HashSet<>();
        for ( Collection<Port> c : connections.values() )
        {
            downstreamOperatorIds.addAll( c.stream().map( Port::getOperatorId ).collect( Collectors.toSet() ) );
        }

        final List<String> downstreamOperatorIdsSorted = new ArrayList<>( downstreamOperatorIds );
        sort( downstreamOperatorIdsSorted );

        for ( String downstreamOperatorId : downstreamOperatorIdsSorted )
        {
            for ( Entry<Port, Set<Port>> e : connections.entrySet() )
            {
                final Port upstreamPort = e.getKey();
                e.getValue().stream().filter( downstreamPort -> downstreamPort.getOperatorId().equals( downstreamOperatorId ) )
                 .forEach( downstreamPort -> result.computeIfAbsent( downstreamOperatorId, s -> new ArrayList<>() )
                                                   .add( Pair.of( upstreamPort.getPortIndex(), downstreamPort.getPortIndex() ) ) );
            }
        }

        for ( List<Pair<Integer, Integer>> pairs : result.values() )
        {
            pairs.sort( ( p1, p2 ) ->
                        {
                            final int r = compare( p1._1, p2._1 );
                            return r == 0 ? compare( p1._2, p2._2 ) : r;
                        } );
        }

        return result;
    }

    private int[] getPartitionDistribution ( final OperatorDef operator )
    {
        final Pipeline pipeline = getPipeline( operator, 0 );
        final PartitionDistribution partitionDistribution = partitionService.getPartitionDistributionOrFail( pipeline.getId()
                                                                                                                     .getRegionId() );
        return partitionDistribution.getDistribution();
    }

    private OperatorTupleQueue[] getPipelineTupleQueues ( final OperatorDef operator )
    {
        final Pipeline pipeline = getPipeline( operator, 0 );
        final int replicaCount = pipeline.getReplicaCount();
        final OperatorTupleQueue[] pipelineTupleQueues = new OperatorTupleQueue[ replicaCount ];
        for ( int i = 0; i < replicaCount; i++ )
        {
            pipelineTupleQueues[ i ] = pipeline.getPipelineReplica( i ).getPipelineTupleQueue();
        }

        return pipelineTupleQueues;
    }

    private void copyPorts ( final List<Pair<Integer, Integer>> pairs, final int[] sourcePorts, final int[] destinationPorts )
    {
        for ( int i = 0; i < pairs.size(); i++ )
        {
            sourcePorts[ i ] = pairs.get( i )._1;
            destinationPorts[ i ] = pairs.get( i )._2;
        }
    }

    private void createUpstreamContexts ( final FlowDef flow )
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            UpstreamContext upstreamContext = createUpstreamContext( flow, pipeline );
            LOGGER.info( "Created {} for pipeline {}", upstreamContext, pipeline.getId() );
            pipeline.setUpstreamContext( upstreamContext );
        }
    }

    private UpstreamContext createUpstreamContext ( final FlowDef flow, final Pipeline pipeline )
    {
        UpstreamContext upstreamContext;
        final OperatorDef firstOperator = pipeline.getFirstOperatorDef();
        if ( firstOperator.getInputPortCount() == 0 )
        {
            upstreamContext = new UpstreamContext( 0, new UpstreamConnectionStatus[] {} );
        }
        else
        {
            final UpstreamConnectionStatus[] statuses = new UpstreamConnectionStatus[ firstOperator.getInputPortCount() ];
            final Map<Port, Set<Port>> upstreamConnections = flow.getInboundConnections( firstOperator.getId() );
            for ( int portIndex = 0; portIndex < firstOperator.getInputPortCount(); portIndex++ )
            {
                UpstreamConnectionStatus status = CLOSED;
                final Collection<Port> upstreamPorts = upstreamConnections.get( new Port( firstOperator.getId(), portIndex ) );
                if ( upstreamPorts == null || upstreamPorts.isEmpty() )
                {
                    status = CLOSED;
                }
                else
                {
                    for ( Port upstreamPort : upstreamPorts )
                    {
                        final OperatorDef upstreamOperator = flow.getOperator( upstreamPort.getOperatorId() );
                        final Pipeline upstreamPipeline = getPipeline( upstreamOperator, -1 );
                        final int operatorIndex = upstreamPipeline.getOperatorIndex( upstreamOperator );
                        checkState( ( upstreamPipeline.getOperatorCount() - 1 ) == operatorIndex,
                                    "Operator %s is supposed to be last operator of pipeline %s but it is on index %s",
                                    upstreamOperator.getId(),
                                    upstreamPipeline.getId(),
                                    operatorIndex );
                        final OperatorReplicaStatus pipelineStatus = upstreamPipeline.getPipelineStatus();
                        if ( pipelineStatus == INITIAL || pipelineStatus == RUNNING || pipelineStatus == COMPLETING )
                        {
                            status = ACTIVE;
                            break;
                        }
                    }
                }
                statuses[ portIndex ] = status;
            }
            upstreamContext = new UpstreamContext( 0, statuses );
        }
        return upstreamContext;
    }

    private void startPipelineReplicaRunners ( final Supervisor supervisor )
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            pipeline.startPipelineReplicaRunners( jokerConfig, supervisor, jokerThreadGroup );
        }
    }

    private Pipeline getPipeline ( final OperatorDef operator, final int operatorIndex )
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            final int i = pipeline.getOperatorIndex( operator );
            if ( i == -1 )
            {
                continue;
            }
            else if ( operatorIndex == -1 )
            {
                return pipeline;
            }

            checkArgument( operatorIndex == i, "Operator %s is expected to be at %s'th index of pipeline %s but it is at %s'th index",
                           operator.getId(),
                           pipeline.getId(),
                           i );
            return pipeline;
        }

        throw new IllegalArgumentException( "Operator " + operator.getId() + " is not found in the pipelines" );
    }

    private Pipeline getPipelineOrFail ( final PipelineId id )
    {
        final Pipeline pipeline = pipelines.get( id );
        checkArgument( pipeline != null, "no pipeline found for pipeline id: %s", id );
        return pipeline;
    }

    private Pipeline getPipelineOrFail ( final OperatorDef operator )
    {
        return pipelines.values()
                        .stream()
                        .filter( p -> p.getOperatorIndex( operator ) != -1 )
                        .findFirst()
                        .orElseThrow( (Supplier<RuntimeException>) () -> new IllegalArgumentException( "No pipeline found for operator "
                                                                                                       + operator.getId() ) );
    }

    private Pipeline getPipelineByFirstOperatorOrFail ( final OperatorDef operator )
    {
        final int expectedOperatorIndex = 0;
        final Pipeline pipeline = getPipelineOrFail( operator );
        final int operatorIndex = pipeline.getOperatorIndex( operator );
        checkArgument( operatorIndex == expectedOperatorIndex,
                       "Pipeline %s has operator %s with index %s but expected index is %s",
                       pipeline.getId(),
                       operator.getId(),
                       operatorIndex,
                       expectedOperatorIndex );
        return pipeline;
    }

    private void stopPipelineReplicaRunners ()
    {
        for ( Pipeline pipeline : getPipelinesTopologicallySorted() )
        {
            final long runnerStopTimeoutInMillis = jokerConfig.getPipelineManagerConfig().getRunnerCommandTimeoutInMillis();
            pipeline.stopPipelineReplicaRunners( runnerStopTimeoutInMillis );
        }
    }

    private List<Pipeline> getPipelinesTopologicallySorted ()
    {
        final List<Pipeline> pipelines = new ArrayList<>( this.pipelines.values() );
        pipelines.sort( comparing( Pipeline::getId ) );

        return pipelines;
    }

    private void shutdownPipelines ()
    {
        for ( Pipeline pipeline : pipelines.values() )
        {
            pipeline.shutdown();
        }
    }

    @Override
    public void handlePipelineReplicaFailed ( final PipelineReplicaId id, final Throwable failure )
    {
        LOGGER.error( "Pipeline Replica " + id + " failed.", failure );
        shutdownGracefully( failure );
    }

    @Override
    public FlowStatus getFlowStatus ()
    {
        return status;
    }

    private void shutdownGracefully ( final Throwable reason )
    {
        try
        {
            if ( reason != null )
            {
                LOGGER.error( "Shutting down flow", reason );
                downstreamTupleSenderFailureFlag.setFailed();
            }
            else
            {
                LOGGER.info( "Shutting down flow..." );
            }

            status = FlowStatus.SHUT_DOWN;

            stopPipelineReplicaRunners();
            shutdownPipelines();
            releaseRegions();
        }
        catch ( Exception e )
        {
            LOGGER.error( "Shutdown failed", e );
        }
    }

    @FunctionalInterface
    private interface Function6<T1, T2, T3, T4, T5, T6>
    {
        T6 apply ( T1 t1, T2 t2, T3 t3, T4 t4, T5 t5 );
    }


    static class NopDownstreamTupleSender implements DownstreamTupleSender
    {

        @Override
        public Future<Void> send ( final TuplesImpl tuples )
        {
            return null;
        }

    }

}
