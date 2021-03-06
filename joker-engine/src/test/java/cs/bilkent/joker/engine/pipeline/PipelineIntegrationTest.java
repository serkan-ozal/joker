package cs.bilkent.joker.engine.pipeline;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.Test;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import cs.bilkent.joker.engine.config.JokerConfig;
import static cs.bilkent.joker.engine.config.ThreadingPreference.MULTI_THREADED;
import static cs.bilkent.joker.engine.config.ThreadingPreference.SINGLE_THREADED;
import cs.bilkent.joker.engine.flow.PipelineId;
import cs.bilkent.joker.engine.kvstore.OperatorKVStore;
import cs.bilkent.joker.engine.kvstore.impl.KVStoreContainer;
import cs.bilkent.joker.engine.kvstore.impl.OperatorKVStoreManagerImpl;
import cs.bilkent.joker.engine.metric.PipelineReplicaMeter;
import cs.bilkent.joker.engine.partition.PartitionDistribution;
import cs.bilkent.joker.engine.partition.PartitionService;
import cs.bilkent.joker.engine.partition.impl.PartitionKeyExtractorFactoryImpl;
import cs.bilkent.joker.engine.partition.impl.PartitionServiceImpl;
import static cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus.ACTIVE;
import static cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus.CLOSED;
import cs.bilkent.joker.engine.pipeline.impl.tuplesupplier.CachedTuplesImplSupplier;
import cs.bilkent.joker.engine.pipeline.impl.tuplesupplier.NonCachedTuplesImplSupplier;
import cs.bilkent.joker.engine.supervisor.Supervisor;
import cs.bilkent.joker.engine.tuplequeue.OperatorTupleQueue;
import cs.bilkent.joker.engine.tuplequeue.TupleQueue;
import cs.bilkent.joker.engine.tuplequeue.TupleQueueDrainerPool;
import cs.bilkent.joker.engine.tuplequeue.impl.OperatorTupleQueueManagerImpl;
import cs.bilkent.joker.engine.tuplequeue.impl.drainer.pool.BlockingTupleQueueDrainerPool;
import cs.bilkent.joker.engine.tuplequeue.impl.drainer.pool.NonBlockingTupleQueueDrainerPool;
import cs.bilkent.joker.engine.tuplequeue.impl.operator.EmptyOperatorTupleQueue;
import cs.bilkent.joker.engine.tuplequeue.impl.queue.MultiThreadedTupleQueue;
import cs.bilkent.joker.engine.util.concurrent.BackoffIdleStrategy;
import cs.bilkent.joker.engine.util.concurrent.IdleStrategy;
import cs.bilkent.joker.operator.InitializationContext;
import cs.bilkent.joker.operator.InvocationContext;
import cs.bilkent.joker.operator.Operator;
import cs.bilkent.joker.operator.OperatorConfig;
import cs.bilkent.joker.operator.OperatorDef;
import cs.bilkent.joker.operator.OperatorDefBuilder;
import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.operator.Tuples;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import cs.bilkent.joker.operator.kvstore.KVStore;
import cs.bilkent.joker.operator.scheduling.ScheduleWhenAvailable;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByCount.EXACT;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.scheduleWhenTuplesAvailableOnAny;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.scheduleWhenTuplesAvailableOnDefaultPort;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import cs.bilkent.joker.operator.schema.annotation.OperatorSchema;
import cs.bilkent.joker.operator.schema.annotation.PortSchema;
import static cs.bilkent.joker.operator.schema.annotation.PortSchemaScope.EXACT_FIELD_SET;
import cs.bilkent.joker.operator.schema.annotation.SchemaField;
import cs.bilkent.joker.operator.spec.OperatorSpec;
import static cs.bilkent.joker.operator.spec.OperatorType.PARTITIONED_STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATELESS;
import cs.bilkent.joker.operators.FilterOperator;
import static cs.bilkent.joker.operators.FilterOperator.PREDICATE_CONFIG_PARAMETER;
import cs.bilkent.joker.operators.MapperOperator;
import static cs.bilkent.joker.operators.MapperOperator.MAPPER_CONFIG_PARAMETER;
import cs.bilkent.joker.test.AbstractJokerTest;
import cs.bilkent.joker.utils.Pair;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO clean up duplicate code
public class PipelineIntegrationTest extends AbstractJokerTest
{

    private static final int REGION_ID = 1;

    private static final int REPLICA_INDEX = 1;


    private final JokerConfig jokerConfig = new JokerConfig();

    private final PartitionService partitionService = new PartitionServiceImpl( jokerConfig );

    private final OperatorTupleQueueManagerImpl operatorTupleQueueManager = new OperatorTupleQueueManagerImpl( jokerConfig,
                                                                                                               new PartitionKeyExtractorFactoryImpl() );

    private final OperatorKVStoreManagerImpl operatorKVStoreManager = new OperatorKVStoreManagerImpl();

    private final OperatorKVStore nopOperatorKvStore = mock( OperatorKVStore.class );

    private final PipelineReplicaId pipelineReplicaId1 = new PipelineReplicaId( new PipelineId( 0, 0 ), 0 );

    private final PipelineReplicaId pipelineReplicaId2 = new PipelineReplicaId( new PipelineId( 1, 0 ), 0 );

    private final PipelineReplicaId pipelineReplicaId3 = new PipelineReplicaId( new PipelineId( 2, 0 ), 0 );


    @Test
    public void testPipelineWithSingleOperator () throws ExecutionException, InterruptedException
    {
        final OperatorConfig mapperOperatorConfig = new OperatorConfig();
        final BiConsumer<Tuple, Tuple> multiplyBy2 = ( input, output ) -> output.set( "val",
                                                                                      2 * input.getIntegerValueOrDefault( "val", 0 ) );
        mapperOperatorConfig.set( MAPPER_CONFIG_PARAMETER, multiplyBy2 );
        final OperatorDef mapperOperatorDef = OperatorDefBuilder.newInstance( "map", MapperOperator.class )
                                                                .setConfig( mapperOperatorConfig )
                                                                .build();

        final PipelineReplicaMeter pipelineReplicaMeter = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                    pipelineReplicaId1,
                                                                                    mapperOperatorDef );

        final OperatorTupleQueue operatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                 REPLICA_INDEX,
                                                                                                                 mapperOperatorDef,
                                                                                                                 MULTI_THREADED );
        final TupleQueueDrainerPool drainerPool = new BlockingTupleQueueDrainerPool( jokerConfig, mapperOperatorDef );
        final Supplier<TuplesImpl> tuplesImplSupplier = new NonCachedTuplesImplSupplier( mapperOperatorDef.getOutputPortCount() );
        final OperatorReplica mapperOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    mapperOperatorDef,
                                                                    operatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    drainerPool,
                                                                    tuplesImplSupplier,
                                                                    pipelineReplicaMeter );
        final PipelineReplica pipeline = new PipelineReplica( jokerConfig,
                                                              pipelineReplicaId1,
                                                              new OperatorReplica[] { mapperOperator },
                                                              new EmptyOperatorTupleQueue( "map", mapperOperatorDef.getInputPortCount() ),
                                                              pipelineReplicaMeter );
        final Supervisor supervisor = mock( Supervisor.class );
        pipeline.init( new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE } ) );

        final TupleCollectorDownstreamTupleSender tupleCollector = new TupleCollectorDownstreamTupleSender( mapperOperatorDef
                                                                                                                    .getOutputPortCount() );
        final PipelineReplicaRunner runner = new PipelineReplicaRunner( jokerConfig, pipeline, supervisor, tupleCollector );

        final Thread runnerThread = spawnThread( runner );

        final int initialVal = 1 + new Random().nextInt( 98 );
        final int tupleCount = 200;
        for ( int i = 0; i < tupleCount; i++ )
        {
            final Tuple tuple = new Tuple();
            tuple.set( "val", initialVal + i );
            operatorTupleQueue.offer( 0, singletonList( tuple ) );
        }

        assertTrueEventually( () -> assertEquals( tupleCount, tupleCollector.tupleQueues[ 0 ].size() ) );
        final List<Tuple> tuples = tupleCollector.tupleQueues[ 0 ].poll( Integer.MAX_VALUE );
        for ( int i = 0; i < tupleCount; i++ )
        {
            final Tuple expected = new Tuple();
            final Tuple t = new Tuple();
            t.set( "val", initialVal + i );
            multiplyBy2.accept( t, expected );
            assertEquals( expected, tuples.get( i ) );
        }

        final UpstreamContext updatedUpstreamContext = new UpstreamContext( 1, new UpstreamConnectionStatus[] { CLOSED } );
        when( supervisor.getUpstreamContext( pipelineReplicaId1 ) ).thenReturn( updatedUpstreamContext );
        runner.updatePipelineUpstreamContext();
        runnerThread.join();
    }

    @Test
    public void testPipelineWithMultipleOperators_pipelineUpstreamClosed () throws ExecutionException, InterruptedException
    {
        final OperatorConfig mapperOperatorConfig = new OperatorConfig();
        final BiConsumer<Tuple, Tuple> add1 = ( input, output ) -> output.set( "val", 1 + input.getIntegerValueOrDefault( "val", -1 ) );
        mapperOperatorConfig.set( MAPPER_CONFIG_PARAMETER, add1 );
        final OperatorDef mapperOperatorDef = OperatorDefBuilder.newInstance( "map", MapperOperator.class )
                                                                .setConfig( mapperOperatorConfig )
                                                                .build();

        final OperatorConfig filterOperatorConfig = new OperatorConfig();
        final Predicate<Tuple> filterEvenVals = tuple -> tuple.getInteger( "val" ) % 2 == 0;
        filterOperatorConfig.set( PREDICATE_CONFIG_PARAMETER, filterEvenVals );
        final OperatorDef filterOperatorDef = OperatorDefBuilder.newInstance( "filter", FilterOperator.class )
                                                                .setConfig( filterOperatorConfig )
                                                                .build();

        final PipelineReplicaMeter pipelineReplicaMeter = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                    pipelineReplicaId1,
                                                                                    mapperOperatorDef );

        final OperatorTupleQueue mapperOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       mapperOperatorDef,
                                                                                                                       MULTI_THREADED );

        final TupleQueueDrainerPool mapperDrainerPool = new BlockingTupleQueueDrainerPool( jokerConfig, mapperOperatorDef );
        final Supplier<TuplesImpl> mapperTuplesImplSupplier = new CachedTuplesImplSupplier( mapperOperatorDef.getOutputPortCount() );
        final OperatorReplica mapperOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    mapperOperatorDef,
                                                                    mapperOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    mapperDrainerPool,
                                                                    mapperTuplesImplSupplier,
                                                                    pipelineReplicaMeter );

        final OperatorTupleQueue filterOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       filterOperatorDef,
                                                                                                                       SINGLE_THREADED );
        final TupleQueueDrainerPool filterDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, filterOperatorDef );
        final Supplier<TuplesImpl> filterTuplesImplSupplier = new NonCachedTuplesImplSupplier( filterOperatorDef.getInputPortCount() );

        final OperatorReplica filterOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    filterOperatorDef,
                                                                    filterOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    filterDrainerPool,
                                                                    filterTuplesImplSupplier,
                                                                    pipelineReplicaMeter );

        final PipelineReplica pipeline = new PipelineReplica( jokerConfig,
                                                              pipelineReplicaId1,
                                                              new OperatorReplica[] { mapperOperator, filterOperator },
                                                              new EmptyOperatorTupleQueue( "map", mapperOperatorDef.getInputPortCount() ),
                                                              pipelineReplicaMeter );

        final Supervisor supervisor = mock( Supervisor.class );

        pipeline.init( new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE } ) );

        final TupleCollectorDownstreamTupleSender tupleCollector = new TupleCollectorDownstreamTupleSender( filterOperatorDef
                                                                                                                    .getOutputPortCount() );

        final PipelineReplicaRunner runner = new PipelineReplicaRunner( jokerConfig, pipeline, supervisor, tupleCollector );

        final Thread runnerThread = spawnThread( runner );

        final int initialVal = 2 + 2 * new Random().nextInt( 98 );
        final int tupleCount = 200;

        for ( int i = 0; i < tupleCount; i++ )
        {
            final int value = initialVal + i;
            final Tuple tuple = new Tuple();
            tuple.set( "val", value );
            mapperOperatorTupleQueue.offer( 0, singletonList( tuple ) );
        }

        final int evenValCount = tupleCount / 2;
        assertTrueEventually( () -> assertEquals( evenValCount, tupleCollector.tupleQueues[ 0 ].size() ) );
        final List<Tuple> tuples = tupleCollector.tupleQueues[ 0 ].poll( Integer.MAX_VALUE );
        for ( int i = 0; i < evenValCount; i++ )
        {
            final Tuple expected = new Tuple();
            final Tuple t = new Tuple();
            t.set( "val", initialVal + ( i * 2 ) );
            add1.accept( t, expected );
            if ( filterEvenVals.test( expected ) )
            {
                assertEquals( expected, tuples.get( i ) );
            }
        }

        final UpstreamContext updatedUpstreamContext = new UpstreamContext( 1, new UpstreamConnectionStatus[] { CLOSED } );
        when( supervisor.getUpstreamContext( pipelineReplicaId1 ) ).thenReturn( updatedUpstreamContext );
        runner.updatePipelineUpstreamContext();
        runnerThread.join();
    }

    @Test
    public void testPipelineWithMultipleOperators_pipelineUpstreamClosed_0inputOperator () throws InterruptedException
    {
        final int batchCount = 4;

        final OperatorConfig generatorOperatorConfig = new OperatorConfig();
        generatorOperatorConfig.set( "batchCount", batchCount );
        final OperatorDef generatorOperatorDef = OperatorDefBuilder.newInstance( "generator", ValueGeneratorOperator.class )
                                                                   .setConfig( generatorOperatorConfig )
                                                                   .build();

        final OperatorConfig passerOperatorConfig = new OperatorConfig();
        passerOperatorConfig.set( "batchCount", batchCount / 2 );
        final OperatorDef passerOperatorDef = OperatorDefBuilder.newInstance( "passer", ValuePasserOperator.class )
                                                                .setConfig( passerOperatorConfig )
                                                                .build();

        final OperatorDef stateOperatorDef = OperatorDefBuilder.newInstance( "state", ValueStateOperator.class )
                                                               .setPartitionFieldNames( singletonList( "val" ) )
                                                               .build();

        final PipelineReplicaMeter pipelineReplicaMeter = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                    pipelineReplicaId1,
                                                                                    generatorOperatorDef );

        final OperatorTupleQueue generatorOperatorTupleQueue = new EmptyOperatorTupleQueue( generatorOperatorDef.getId(),
                                                                                            generatorOperatorDef.getInputPortCount() );
        final TupleQueueDrainerPool generatorDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, generatorOperatorDef );
        final Supplier<TuplesImpl> generatorTuplesImplSupplier = new CachedTuplesImplSupplier( generatorOperatorDef.getOutputPortCount() );
        final OperatorReplica generatorOperator = new OperatorReplica( pipelineReplicaId1,
                                                                       generatorOperatorDef,
                                                                       generatorOperatorTupleQueue,
                                                                       nopOperatorKvStore,
                                                                       generatorDrainerPool,
                                                                       generatorTuplesImplSupplier,
                                                                       pipelineReplicaMeter );

        final OperatorTupleQueue passerOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       passerOperatorDef,
                                                                                                                       SINGLE_THREADED );
        final TupleQueueDrainerPool passerDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, passerOperatorDef );
        final Supplier<TuplesImpl> passerTuplesImplSupplier = new CachedTuplesImplSupplier( passerOperatorDef.getOutputPortCount() );
        final OperatorReplica passerOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    passerOperatorDef,
                                                                    passerOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    passerDrainerPool,
                                                                    passerTuplesImplSupplier,
                                                                    pipelineReplicaMeter );

        final PartitionDistribution partitionDistribution = partitionService.createPartitionDistribution( REGION_ID, 1 );
        final OperatorKVStore[] operatorKvStores = operatorKVStoreManager.createPartitionedOperatorKVStores( REGION_ID,
                                                                                                             "state",
                                                                                                             partitionDistribution );

        final OperatorTupleQueue[] stateOperatorTupleQueues = operatorTupleQueueManager.createPartitionedOperatorTupleQueues( REGION_ID,
                                                                                                                              stateOperatorDef,
                                                                                                                              partitionDistribution );
        final OperatorTupleQueue stateOperatorTupleQueue = stateOperatorTupleQueues[ 0 ];
        final TupleQueueDrainerPool stateDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, stateOperatorDef );
        final Supplier<TuplesImpl> stateTuplesImplSupplier = new CachedTuplesImplSupplier( stateOperatorDef.getOutputPortCount() );
        final OperatorReplica stateOperator = new OperatorReplica( pipelineReplicaId1,
                                                                   stateOperatorDef,
                                                                   stateOperatorTupleQueue,
                                                                   operatorKvStores[ 0 ],
                                                                   stateDrainerPool,
                                                                   stateTuplesImplSupplier,
                                                                   pipelineReplicaMeter );

        final PipelineReplica pipeline = new PipelineReplica( jokerConfig,
                                                              pipelineReplicaId1,
                                                              new OperatorReplica[] { generatorOperator, passerOperator, stateOperator },
                                                              new EmptyOperatorTupleQueue( "generator",
                                                                                           generatorOperatorDef.getInputPortCount() ),
                                                              pipelineReplicaMeter );

        final Supervisor supervisor = mock( Supervisor.class );

        pipeline.init( new UpstreamContext( 0, new UpstreamConnectionStatus[] {} ) );

        final PipelineReplicaRunner runner = new PipelineReplicaRunner( jokerConfig, pipeline, supervisor,
                                                                        mock( DownstreamTupleSender.class ) );

        final Thread runnerThread = spawnThread( runner );

        final ValueGeneratorOperator generatorOp = (ValueGeneratorOperator) generatorOperator.getOperator();
        generatorOp.start = true;

        assertTrueEventually( () -> assertTrue( generatorOp.count > 1000 ) );

        final UpstreamContext updatedUpstreamContext = new UpstreamContext( 1, new UpstreamConnectionStatus[] {} );
        when( supervisor.getUpstreamContext( pipelineReplicaId1 ) ).thenReturn( updatedUpstreamContext );
        runner.updatePipelineUpstreamContext();

        assertTrueEventually( () -> verify( supervisor ).notifyPipelineReplicaCompleted( pipelineReplicaId1 ) );

        runnerThread.join();

        final ValuePasserOperator passerOp = (ValuePasserOperator) passerOperator.getOperator();
        final ValueStateOperator stateOp = (ValueStateOperator) stateOperator.getOperator();

        assertEquals( generatorOp.count, passerOp.count );
        assertEquals( generatorOp.count, stateOp.count );
        assertEquals( generatorOp.count, getKVStoreTotalItemCount( REGION_ID, "state" ) );
    }

    @Test
    public void testMultiplePipelines_singleInputPort () throws ExecutionException, InterruptedException
    {
        final SupervisorImpl supervisor = new SupervisorImpl();
        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE } ) );
        supervisor.upstreamContexts.put( pipelineReplicaId2, new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE } ) );
        supervisor.inputPortIndices.put( pipelineReplicaId1, 0 );
        supervisor.inputPortIndices.put( pipelineReplicaId2, 0 );

        final OperatorConfig mapperOperatorConfig = new OperatorConfig();
        final BiConsumer<Tuple, Tuple> add1 = ( input, output ) -> output.set( "val", 1 + input.getIntegerValueOrDefault( "val", -1 ) );
        mapperOperatorConfig.set( MAPPER_CONFIG_PARAMETER, add1 );
        final OperatorDef mapperOperatorDef = OperatorDefBuilder.newInstance( "map", MapperOperator.class )
                                                                .setConfig( mapperOperatorConfig )
                                                                .build();

        final OperatorTupleQueue mapperOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       mapperOperatorDef,
                                                                                                                       MULTI_THREADED );

        final TupleQueueDrainerPool mapperDrainerPool = new BlockingTupleQueueDrainerPool( jokerConfig, mapperOperatorDef );
        final Supplier<TuplesImpl> mapperTuplesImplSupplier = new NonCachedTuplesImplSupplier( mapperOperatorDef.getOutputPortCount() );

        final PipelineReplicaMeter pipelineReplicaMeter1 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId1,
                                                                                     mapperOperatorDef );
        final OperatorReplica mapperOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    mapperOperatorDef,
                                                                    mapperOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    mapperDrainerPool,
                                                                    mapperTuplesImplSupplier,
                                                                    pipelineReplicaMeter1 );

        final PipelineReplica pipeline1 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId1,
                                                               new OperatorReplica[] { mapperOperator },
                                                               new EmptyOperatorTupleQueue( "map", mapperOperatorDef.getInputPortCount() ),
                                                               pipelineReplicaMeter1 );
        pipeline1.init( supervisor.upstreamContexts.get( pipelineReplicaId1 ) );

        final OperatorConfig filterOperatorConfig = new OperatorConfig();
        final Predicate<Tuple> filterEvenVals = tuple -> tuple.getInteger( "val" ) % 2 == 0;
        filterOperatorConfig.set( PREDICATE_CONFIG_PARAMETER, filterEvenVals );
        final OperatorDef filterOperatorDef = OperatorDefBuilder.newInstance( "filter", FilterOperator.class )
                                                                .setConfig( filterOperatorConfig )
                                                                .build();

        final OperatorTupleQueue filterOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       filterOperatorDef,
                                                                                                                       MULTI_THREADED );

        final DownstreamTupleSenderImpl tupleSender = new DownstreamTupleSenderImpl( filterOperatorTupleQueue,
                                                                                     new Pair[] { Pair.of( 0, 0 ) } );

        final TupleQueueDrainerPool filterDrainerPool = new BlockingTupleQueueDrainerPool( jokerConfig, filterOperatorDef );
        final Supplier<TuplesImpl> filterTuplesImplSupplier = new NonCachedTuplesImplSupplier( filterOperatorDef.getInputPortCount() );

        final PipelineReplicaMeter pipelineReplicaMeter2 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId2,
                                                                                     filterOperatorDef );
        final OperatorReplica filterOperator = new OperatorReplica( pipelineReplicaId2,
                                                                    filterOperatorDef,
                                                                    filterOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    filterDrainerPool,
                                                                    filterTuplesImplSupplier,
                                                                    pipelineReplicaMeter2 );

        final PipelineReplica pipeline2 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId2,
                                                               new OperatorReplica[] { filterOperator },
                                                               new EmptyOperatorTupleQueue( "filter",
                                                                                            filterOperatorDef.getInputPortCount() ),
                                                               pipelineReplicaMeter2 );

        pipeline2.init( supervisor.upstreamContexts.get( pipelineReplicaId2 ) );

        final PipelineReplicaRunner runner1 = new PipelineReplicaRunner( jokerConfig, pipeline1, supervisor, tupleSender );
        supervisor.downstreamTupleSenders.put( pipeline1.id(), tupleSender );

        final TupleCollectorDownstreamTupleSender tupleCollector = new TupleCollectorDownstreamTupleSender( filterOperatorDef.getOutputPortCount() );

        final PipelineReplicaRunner runner2 = new PipelineReplicaRunner( jokerConfig, pipeline2, supervisor, tupleCollector );
        supervisor.downstreamTupleSenders.put( pipeline2.id(), tupleCollector );

        supervisor.targetPipelineReplicaId = pipelineReplicaId2;
        supervisor.runner = runner2;

        final Thread runnerThread1 = spawnThread( runner1 );
        final Thread runnerThread2 = spawnThread( runner2 );

        final int initialVal = 2 + 2 * new Random().nextInt( 98 );
        final int tupleCount = 200;

        for ( int i = 0; i < tupleCount; i++ )
        {
            final int value = initialVal + i;
            final Tuple tuple = new Tuple();
            tuple.set( "val", value );
            mapperOperatorTupleQueue.offer( 0, singletonList( tuple ) );
        }

        final int evenValCount = tupleCount / 2;
        assertTrueEventually( () -> assertEquals( evenValCount, tupleCollector.tupleQueues[ 0 ].size() ) );
        final List<Tuple> tuples = tupleCollector.tupleQueues[ 0 ].poll( Integer.MAX_VALUE );
        for ( int i = 0; i < evenValCount; i++ )
        {
            final Tuple expected = new Tuple();
            final Tuple t = new Tuple();
            t.set( "val", initialVal + ( i * 2 ) );
            add1.accept( t, expected );
            if ( filterEvenVals.test( expected ) )
            {
                assertEquals( expected, tuples.get( i ) );
            }
        }

        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 1, new UpstreamConnectionStatus[] { CLOSED } ) );
        runner1.updatePipelineUpstreamContext();
        runnerThread1.join();
        runnerThread2.join();
        assertTrue( supervisor.completedPipelines.contains( pipelineReplicaId1 ) );
        assertTrue( supervisor.completedPipelines.contains( pipelineReplicaId2 ) );
    }

    @Test
    public void testMultiplePipelines_multipleInputPorts () throws InterruptedException
    {
        final SupervisorImpl supervisor = new SupervisorImpl();
        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 0, new UpstreamConnectionStatus[] {} ) );
        supervisor.upstreamContexts.put( pipelineReplicaId2, new UpstreamContext( 0, new UpstreamConnectionStatus[] {} ) );
        supervisor.upstreamContexts.put( pipelineReplicaId3, new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE, ACTIVE } ) );
        supervisor.inputPortIndices.put( pipelineReplicaId1, 0 );
        supervisor.inputPortIndices.put( pipelineReplicaId2, 1 );

        final int batchCount = 4;

        final OperatorConfig generatorOperatorConfig1 = new OperatorConfig();
        generatorOperatorConfig1.set( "batchCount", batchCount );
        final OperatorDef generatorOperatorDef1 = OperatorDefBuilder.newInstance( "generator1", ValueGeneratorOperator.class )
                                                                    .setConfig( generatorOperatorConfig1 )
                                                                    .build();

        final OperatorTupleQueue generatorOperatorTupleQueue1 = new EmptyOperatorTupleQueue( generatorOperatorDef1.getId(),
                                                                                             generatorOperatorDef1.getInputPortCount() );
        final TupleQueueDrainerPool generatorDrainerPool1 = new NonBlockingTupleQueueDrainerPool( jokerConfig, generatorOperatorDef1 );
        final Supplier<TuplesImpl> generatorTuplesImplSupplier1 = new NonCachedTuplesImplSupplier( generatorOperatorDef1.getOutputPortCount() );

        final PipelineReplicaMeter pipelineReplicaMeter1 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId1,
                                                                                     generatorOperatorDef1 );
        final OperatorReplica generatorOperator1 = new OperatorReplica( pipelineReplicaId1,
                                                                        generatorOperatorDef1,
                                                                        generatorOperatorTupleQueue1,
                                                                        nopOperatorKvStore,
                                                                        generatorDrainerPool1,
                                                                        generatorTuplesImplSupplier1,
                                                                        pipelineReplicaMeter1 );

        final PipelineReplica pipeline1 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId1,
                                                               new OperatorReplica[] { generatorOperator1 },
                                                               new EmptyOperatorTupleQueue( "generator1",
                                                                                            generatorOperatorDef1.getInputPortCount() ),
                                                               pipelineReplicaMeter1 );

        pipeline1.init( supervisor.upstreamContexts.get( pipelineReplicaId1 ) );

        final OperatorConfig generatorOperatorConfig2 = new OperatorConfig();
        generatorOperatorConfig2.set( "batchCount", batchCount );
        generatorOperatorConfig2.set( "increment", false );
        final OperatorDef generatorOperatorDef2 = OperatorDefBuilder.newInstance( "generator2", ValueGeneratorOperator.class )
                                                                    .setConfig( generatorOperatorConfig2 )
                                                                    .build();

        final OperatorTupleQueue generatorOperatorTupleQueue2 = new EmptyOperatorTupleQueue( generatorOperatorDef2.getId(),
                                                                                             generatorOperatorDef2.getInputPortCount() );
        final TupleQueueDrainerPool generatorDrainerPool2 = new NonBlockingTupleQueueDrainerPool( jokerConfig, generatorOperatorDef2 );
        final Supplier<TuplesImpl> generatorTuplesImplSupplier2 = new NonCachedTuplesImplSupplier( generatorOperatorDef2.getOutputPortCount() );

        final PipelineReplicaMeter pipelineReplicaMeter2 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId2,
                                                                                     generatorOperatorDef2 );
        final OperatorReplica generatorOperator2 = new OperatorReplica( pipelineReplicaId2,
                                                                        generatorOperatorDef2,
                                                                        generatorOperatorTupleQueue2,
                                                                        nopOperatorKvStore,
                                                                        generatorDrainerPool2,
                                                                        generatorTuplesImplSupplier2,
                                                                        pipelineReplicaMeter2 );

        final PipelineReplica pipeline2 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId2,
                                                               new OperatorReplica[] { generatorOperator2 },
                                                               new EmptyOperatorTupleQueue( "generator2",
                                                                                            generatorOperatorDef2.getInputPortCount() ),
                                                               pipelineReplicaMeter2 );

        pipeline2.init( supervisor.upstreamContexts.get( pipelineReplicaId2 ) );

        final OperatorConfig sinkOperatorConfig = new OperatorConfig();
        final OperatorDef sinkOperatorDef = OperatorDefBuilder.newInstance( "sink", ValueSinkOperator.class )
                                                              .setConfig( sinkOperatorConfig )
                                                              .build();

        final OperatorConfig passerOperatorConfig = new OperatorConfig();
        passerOperatorConfig.set( "batchCount", batchCount / 2 );
        final OperatorDef passerOperatorDef = OperatorDefBuilder.newInstance( "passer", ValuePasserOperator.class )
                                                                .setConfig( passerOperatorConfig )
                                                                .build();

        final OperatorDef stateOperatorDef = OperatorDefBuilder.newInstance( "state", ValueStateOperator.class )
                                                               .setPartitionFieldNames( singletonList( "val" ) )
                                                               .build();

        final PipelineReplicaMeter pipelineReplicaMeter3 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId3,
                                                                                     sinkOperatorDef );

        final OperatorTupleQueue sinkOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                     REPLICA_INDEX,
                                                                                                                     sinkOperatorDef,
                                                                                                                     MULTI_THREADED );
        final TupleQueueDrainerPool sinkDrainerPool = new BlockingTupleQueueDrainerPool( jokerConfig, sinkOperatorDef );
        final Supplier<TuplesImpl> sinkTuplesImplSupplier = new CachedTuplesImplSupplier( sinkOperatorDef.getOutputPortCount() );
        final OperatorKVStore sinkOperatorKVStore = operatorKVStoreManager.createDefaultOperatorKVStore( REGION_ID, "sink" );
        final OperatorReplica sinkOperator = new OperatorReplica( pipelineReplicaId3,
                                                                  sinkOperatorDef,
                                                                  sinkOperatorTupleQueue,
                                                                  sinkOperatorKVStore,
                                                                  sinkDrainerPool,
                                                                  sinkTuplesImplSupplier,
                                                                  pipelineReplicaMeter3 );

        final OperatorTupleQueue passerOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       passerOperatorDef,
                                                                                                                       SINGLE_THREADED );
        final TupleQueueDrainerPool passerDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, passerOperatorDef );
        final Supplier<TuplesImpl> passerTuplesImplSupplier = new CachedTuplesImplSupplier( passerOperatorDef.getOutputPortCount() );
        final OperatorReplica passerOperator = new OperatorReplica( pipelineReplicaId3,
                                                                    passerOperatorDef,
                                                                    passerOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    passerDrainerPool,
                                                                    passerTuplesImplSupplier,
                                                                    pipelineReplicaMeter3 );
        final PartitionDistribution partitionDistribution = partitionService.createPartitionDistribution( REGION_ID, 1 );
        final OperatorKVStore[] operatorKvStores = operatorKVStoreManager.createPartitionedOperatorKVStores( REGION_ID,
                                                                                                             "state",
                                                                                                             partitionDistribution );
        final OperatorTupleQueue[] stateOperatorTupleQueues = operatorTupleQueueManager.createPartitionedOperatorTupleQueues( REGION_ID,
                                                                                                                              stateOperatorDef,
                                                                                                                              partitionDistribution );
        final OperatorTupleQueue stateOperatorTupleQueue = stateOperatorTupleQueues[ 0 ];
        final TupleQueueDrainerPool stateDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, stateOperatorDef );
        final Supplier<TuplesImpl> stateTuplesImplSupplier = new CachedTuplesImplSupplier( stateOperatorDef.getOutputPortCount() );
        final OperatorReplica stateOperator = new OperatorReplica( pipelineReplicaId3,
                                                                   stateOperatorDef,
                                                                   stateOperatorTupleQueue,
                                                                   operatorKvStores[ 0 ],
                                                                   stateDrainerPool,
                                                                   stateTuplesImplSupplier,
                                                                   pipelineReplicaMeter3 );

        final PipelineReplica pipeline3 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId3,
                                                               new OperatorReplica[] { sinkOperator, passerOperator, stateOperator },
                                                               new EmptyOperatorTupleQueue( "sink", sinkOperatorDef.getInputPortCount() ),
                                                               pipelineReplicaMeter3 );

        pipeline3.init( supervisor.upstreamContexts.get( pipelineReplicaId3 ) );

        final DownstreamTupleSenderImpl sender1 = new DownstreamTupleSenderImpl( sinkOperatorTupleQueue, new Pair[] { Pair.of( 0, 0 ) } );
        final PipelineReplicaRunner runner1 = new PipelineReplicaRunner( jokerConfig, pipeline1, supervisor, sender1 );
        supervisor.downstreamTupleSenders.put( pipeline1.id(), sender1 );

        final DownstreamTupleSenderImpl sender2 = new DownstreamTupleSenderImpl( sinkOperatorTupleQueue, new Pair[] { Pair.of( 0, 1 ) } );
        final PipelineReplicaRunner runner2 = new PipelineReplicaRunner( jokerConfig, pipeline2, supervisor, sender2 );
        supervisor.downstreamTupleSenders.put( pipeline2.id(), sender2 );

        final DownstreamTupleSenderImpl sender3 = new DownstreamTupleSenderImpl( null, new Pair[] {} );
        final PipelineReplicaRunner runner3 = new PipelineReplicaRunner( jokerConfig, pipeline3, supervisor, sender3 );
        supervisor.downstreamTupleSenders.put( pipeline3.id(), sender3 );

        supervisor.targetPipelineReplicaId = pipelineReplicaId3;
        supervisor.runner = runner3;

        final Thread runnerThread1 = spawnThread( runner1 );
        final Thread runnerThread2 = spawnThread( runner2 );
        final Thread runnerThread3 = spawnThread( runner3 );

        final ValueGeneratorOperator generatorOp1 = (ValueGeneratorOperator) generatorOperator1.getOperator();
        generatorOp1.start = true;

        final ValueGeneratorOperator generatorOp2 = (ValueGeneratorOperator) generatorOperator2.getOperator();

        assertTrueEventually( () -> assertTrue( generatorOp1.count > 5000 ) );
        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 1, new UpstreamConnectionStatus[] {} ) );
        runner1.updatePipelineUpstreamContext();

        generatorOp2.start = true;
        assertTrueEventually( () -> assertTrue( generatorOp2.count < -5000 ) );
        supervisor.upstreamContexts.put( pipelineReplicaId2, new UpstreamContext( 1, new UpstreamConnectionStatus[] {} ) );
        runner2.updatePipelineUpstreamContext();

        assertTrueEventually( () -> supervisor.completedPipelines.contains( pipelineReplicaId1 ) );
        assertTrueEventually( () -> supervisor.completedPipelines.contains( pipelineReplicaId2 ) );
        assertTrueEventually( () -> supervisor.completedPipelines.contains( pipelineReplicaId3 ) );

        runnerThread1.join();
        runnerThread2.join();
        runnerThread3.join();

        final ValuePasserOperator passerOp = (ValuePasserOperator) passerOperator.getOperator();
        final ValueStateOperator stateOp = (ValueStateOperator) stateOperator.getOperator();

        final int totalCount = generatorOp1.count + Math.abs( generatorOp2.count );
        assertEquals( totalCount, passerOp.count );
        assertEquals( totalCount, stateOp.count );
        assertEquals( totalCount, getKVStoreTotalItemCount( REGION_ID, "state" ) );
    }

    @Test
    public void testMultiplePipelines_partitionedStatefulDownstreamPipeline () throws ExecutionException, InterruptedException
    {
        final SupervisorImpl supervisor = new SupervisorImpl();
        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 0, new UpstreamConnectionStatus[] {} ) );
        supervisor.upstreamContexts.put( pipelineReplicaId2, new UpstreamContext( 0, new UpstreamConnectionStatus[] { ACTIVE } ) );
        supervisor.inputPortIndices.put( pipelineReplicaId1, 0 );
        supervisor.inputPortIndices.put( pipelineReplicaId2, 0 );

        final int batchCount = 4;
        final OperatorConfig generatorOperatorConfig = new OperatorConfig();
        generatorOperatorConfig.set( "batchCount", batchCount );
        final OperatorDef generatorOperatorDef = OperatorDefBuilder.newInstance( "generator", ValueGeneratorOperator.class )
                                                                   .setConfig( generatorOperatorConfig )
                                                                   .build();

        final OperatorConfig passerOperatorConfig = new OperatorConfig();
        passerOperatorConfig.set( "batchCount", batchCount / 2 );
        final OperatorDef passerOperatorDef = OperatorDefBuilder.newInstance( "passer", ValuePasserOperator.class )
                                                                .setConfig( passerOperatorConfig )
                                                                .build();

        final PipelineReplicaMeter pipelineReplicaMeter1 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId1,
                                                                                     generatorOperatorDef );

        final OperatorTupleQueue generatorOperatorTupleQueue = new EmptyOperatorTupleQueue( generatorOperatorDef.getId(),
                                                                                            generatorOperatorDef.getInputPortCount() );

        final TupleQueueDrainerPool generatorDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, generatorOperatorDef );
        final Supplier<TuplesImpl> generatorTuplesImplSupplier = new NonCachedTuplesImplSupplier( generatorOperatorDef.getOutputPortCount() );

        final OperatorReplica generatorOperator = new OperatorReplica( pipelineReplicaId1,
                                                                       generatorOperatorDef,
                                                                       generatorOperatorTupleQueue,
                                                                       nopOperatorKvStore,
                                                                       generatorDrainerPool,
                                                                       generatorTuplesImplSupplier,
                                                                       pipelineReplicaMeter1 );

        final OperatorTupleQueue passerOperatorTupleQueue = operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                       REPLICA_INDEX,
                                                                                                                       passerOperatorDef,
                                                                                                                       SINGLE_THREADED );

        final TupleQueueDrainerPool passerDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, passerOperatorDef );
        final Supplier<TuplesImpl> passerTuplesImplSupplier = new CachedTuplesImplSupplier( passerOperatorDef.getOutputPortCount() );

        final OperatorReplica passerOperator = new OperatorReplica( pipelineReplicaId1,
                                                                    passerOperatorDef,
                                                                    passerOperatorTupleQueue,
                                                                    nopOperatorKvStore,
                                                                    passerDrainerPool,
                                                                    passerTuplesImplSupplier,
                                                                    pipelineReplicaMeter1 );

        final PartitionDistribution partitionDistribution = partitionService.createPartitionDistribution( REGION_ID, 1 );
        final OperatorKVStore[] operatorKvStores = operatorKVStoreManager.createPartitionedOperatorKVStores( REGION_ID,
                                                                                                             "state",
                                                                                                             partitionDistribution );

        final OperatorDef stateOperatorDef = OperatorDefBuilder.newInstance( "state", ValueStateOperator.class )
                                                               .setPartitionFieldNames( singletonList( "val" ) )
                                                               .build();

        final OperatorTupleQueue[] stateOperatorTupleQueues = operatorTupleQueueManager.createPartitionedOperatorTupleQueues( REGION_ID,
                                                                                                                              stateOperatorDef,
                                                                                                                              partitionDistribution );
        final OperatorTupleQueue stateOperatorTupleQueue = stateOperatorTupleQueues[ 0 ];

        final TupleQueueDrainerPool stateDrainerPool = new NonBlockingTupleQueueDrainerPool( jokerConfig, stateOperatorDef );
        final Supplier<TuplesImpl> stateTuplesImplSupplier = new CachedTuplesImplSupplier( stateOperatorDef.getOutputPortCount() );

        final PipelineReplicaMeter pipelineReplicaMeter2 = new PipelineReplicaMeter( jokerConfig.getMetricManagerConfig().getTickMask(),
                                                                                     pipelineReplicaId2,
                                                                                     stateOperatorDef );
        final OperatorReplica stateOperator = new OperatorReplica( pipelineReplicaId2,
                                                                   stateOperatorDef,
                                                                   stateOperatorTupleQueue,
                                                                   operatorKvStores[ 0 ],
                                                                   stateDrainerPool,
                                                                   stateTuplesImplSupplier,
                                                                   pipelineReplicaMeter2 );

        final PipelineReplica pipeline1 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId1,
                                                               new OperatorReplica[] { generatorOperator, passerOperator },
                                                               new EmptyOperatorTupleQueue( "generator",
                                                                                            generatorOperatorDef.getInputPortCount() ),
                                                               pipelineReplicaMeter1 );

        pipeline1.init( supervisor.upstreamContexts.get( pipelineReplicaId1 ) );

        final PipelineReplica pipeline2 = new PipelineReplica( jokerConfig,
                                                               pipelineReplicaId2,
                                                               new OperatorReplica[] { stateOperator },
                                                               operatorTupleQueueManager.createDefaultOperatorTupleQueue( REGION_ID,
                                                                                                                          REPLICA_INDEX,
                                                                                                                          stateOperatorDef,
                                                                                                                          MULTI_THREADED ),
                                                               pipelineReplicaMeter2 );

        pipeline2.init( supervisor.upstreamContexts.get( pipelineReplicaId2 ) );

        final DownstreamTupleSenderImpl sender1 = new DownstreamTupleSenderImpl( pipeline2.getPipelineTupleQueue(),
                                                                                 new Pair[] { Pair.of( 0, 0 ) } );
        supervisor.downstreamTupleSenders.put( pipeline1.id(), sender1 );
        final PipelineReplicaRunner runner1 = new PipelineReplicaRunner( jokerConfig, pipeline1, supervisor, sender1 );

        final DownstreamTupleSender sender2 = mock( DownstreamTupleSender.class );
        final PipelineReplicaRunner runner2 = new PipelineReplicaRunner( jokerConfig, pipeline2, supervisor, sender2 );
        supervisor.downstreamTupleSenders.put( pipeline2.id(), sender2 );

        supervisor.targetPipelineReplicaId = pipelineReplicaId2;
        supervisor.runner = runner2;

        final Thread runnerThread1 = spawnThread( runner1 );
        final Thread runnerThread2 = spawnThread( runner2 );

        final ValueGeneratorOperator generatorOp = (ValueGeneratorOperator) generatorOperator.getOperator();
        generatorOp.start = true;

        assertTrueEventually( () -> assertTrue( generatorOp.count > 1000 ) );

        supervisor.upstreamContexts.put( pipelineReplicaId1, new UpstreamContext( 1, new UpstreamConnectionStatus[] {} ) );
        runner1.updatePipelineUpstreamContext();

        assertTrueEventually( () -> supervisor.completedPipelines.contains( pipelineReplicaId1 ) );
        assertTrueEventually( () -> supervisor.completedPipelines.contains( pipelineReplicaId2 ) );

        runnerThread1.join();
        runnerThread2.join();

        final ValuePasserOperator passerOp = (ValuePasserOperator) passerOperator.getOperator();
        final ValueStateOperator stateOp = (ValueStateOperator) stateOperator.getOperator();

        assertEquals( generatorOp.count, passerOp.count );
        assertEquals( generatorOp.count, stateOp.count );
        assertEquals( generatorOp.count, getKVStoreTotalItemCount( REGION_ID, "state" ) );
    }

    private int getKVStoreTotalItemCount ( final int regionId, final String operatorId )
    {
        int count = 0;
        for ( KVStoreContainer container : operatorKVStoreManager.getKVStoreContainers( regionId, operatorId ) )
        {
            count += container.getKeyCount();
        }

        return count;
    }

    private static class TupleCollectorDownstreamTupleSender implements DownstreamTupleSender
    {

        private final TupleQueue[] tupleQueues;

        TupleCollectorDownstreamTupleSender ( final int portCount )
        {
            tupleQueues = new TupleQueue[ portCount ];
            for ( int i = 0; i < portCount; i++ )
            {
                tupleQueues[ i ] = new MultiThreadedTupleQueue( 1000 );
            }
        }

        @Override
        public Future<Void> send ( final TuplesImpl tuples )
        {
            for ( int i = 0; i < tuples.getPortCount(); i++ )
            {
                tupleQueues[ i ].offer( tuples.getTuples( i ) );
            }

            return null;
        }

    }


    @OperatorSpec( type = STATEFUL, inputPortCount = 2, outputPortCount = 1 )
    @OperatorSchema( inputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ),

                                @PortSchema( portIndex = 1, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) }, outputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) } )
    public static class ValueSinkOperator implements Operator
    {

        @Override
        public SchedulingStrategy init ( final InitializationContext context )
        {
            return scheduleWhenTuplesAvailableOnAny( 2, 1, 0, 1 );
        }

        @Override
        public void invoke ( final InvocationContext invocationContext )
        {
            final Tuples input = invocationContext.getInput();

            final Tuples output = invocationContext.getOutput();
            output.addAll( input.getTuples( 0 ) );
            output.addAll( input.getTuples( 1 ) );
        }

    }


    @OperatorSpec( type = STATELESS, inputPortCount = 0, outputPortCount = 1 )
    @OperatorSchema( outputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) } )
    public static class ValueGeneratorOperator implements Operator
    {

        private volatile boolean start;

        private int batchCount;

        private volatile int count;

        private boolean increment;

        @Override
        public SchedulingStrategy init ( final InitializationContext context )
        {
            final OperatorConfig config = context.getConfig();
            batchCount = config.getInteger( "batchCount" );
            count = config.getIntegerOrDefault( "initial", 0 );
            increment = config.getBooleanOrDefault( "increment", true );
            return ScheduleWhenAvailable.INSTANCE;
        }

        @Override
        public void invoke ( final InvocationContext invocationContext )
        {
            if ( start )
            {
                final Tuples output = invocationContext.getOutput();
                for ( int i = 0; i < batchCount; i++ )
                {
                    final int val = increment ? ++count : --count;
                    final Tuple t = new Tuple();
                    t.set( "val", val );
                    output.add( t );
                }
            }
            else
            {
                sleepUninterruptibly( 1, TimeUnit.MICROSECONDS );
            }
        }

    }


    @OperatorSpec( type = STATELESS, inputPortCount = 1, outputPortCount = 1 )
    @OperatorSchema( inputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) }, outputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) } )
    public static class ValuePasserOperator implements Operator
    {

        private volatile int count;

        @Override
        public SchedulingStrategy init ( final InitializationContext context )
        {
            final int batchCount = context.getConfig().getInteger( "batchCount" );
            return scheduleWhenTuplesAvailableOnDefaultPort( EXACT, batchCount );
        }

        @Override
        public void invoke ( final InvocationContext invocationContext )
        {
            final Tuples input = invocationContext.getInput();
            final Tuples output = invocationContext.getOutput();
            output.addAll( input.getTuplesByDefaultPort() );
            count += input.getTupleCount( 0 );
        }

    }


    @OperatorSpec( type = PARTITIONED_STATEFUL, inputPortCount = 1, outputPortCount = 1 )
    @OperatorSchema( inputs = { @PortSchema( portIndex = 0, scope = EXACT_FIELD_SET, fields = { @SchemaField( name = "val", type = Integer.class ) } ) } )
    public static class ValueStateOperator implements Operator
    {

        private volatile int count;

        @Override
        public SchedulingStrategy init ( final InitializationContext context )
        {
            return scheduleWhenTuplesAvailableOnDefaultPort( EXACT, 1 );
        }

        @Override
        public void invoke ( final InvocationContext invocationContext )
        {
            final Tuples input = invocationContext.getInput();
            final KVStore kvStore = invocationContext.getKVStore();
            for ( Tuple tuple : input.getTuplesByDefaultPort() )
            {
                kvStore.set( "tuple", tuple );
            }

            count += input.getTupleCount( 0 );
        }

    }


    public static class DownstreamTupleSenderImpl implements DownstreamTupleSender
    {

        private final IdleStrategy idleStrategy = BackoffIdleStrategy.newDefaultInstance();

        private final OperatorTupleQueue operatorTupleQueue;

        private final Pair<Integer, Integer>[] ports;

        public DownstreamTupleSenderImpl ( final OperatorTupleQueue operatorTupleQueue, final Pair<Integer, Integer>[] ports )
        {
            this.operatorTupleQueue = operatorTupleQueue;
            this.ports = ports;
        }

        @Override
        public Future<Void> send ( final TuplesImpl input )
        {
            for ( Pair<Integer, Integer> p : ports )
            {
                final int outputPort = p._1;
                final int inputPort = p._2;
                if ( input.getTupleCount( outputPort ) > 0 )
                {
                    idleStrategy.reset();

                    final List<Tuple> tuples = input.getTuples( outputPort );
                    final int size = tuples.size();
                    int fromIndex = 0;
                    while ( true )
                    {
                        final int offered = operatorTupleQueue.offer( inputPort, tuples, fromIndex );
                        fromIndex += offered;
                        if ( fromIndex == size )
                        {
                            break;
                        }
                        else if ( offered == 0 )
                        {
                            idleStrategy.idle();
                        }
                    }
                }
            }

            return null;
        }

    }


    public static class SupervisorImpl implements Supervisor
    {

        private final Map<PipelineReplicaId, UpstreamContext> upstreamContexts = new ConcurrentHashMap<>();

        private final Map<PipelineReplicaId, DownstreamTupleSender> downstreamTupleSenders = new ConcurrentHashMap<>();

        private final Map<PipelineReplicaId, Integer> inputPortIndices = new HashMap<>();

        private final Set<PipelineReplicaId> completedPipelines = Collections.newSetFromMap( new ConcurrentHashMap<>() );

        private PipelineReplicaId targetPipelineReplicaId;

        private PipelineReplicaRunner runner;

        @Override
        public UpstreamContext getUpstreamContext ( final PipelineReplicaId id )
        {
            return upstreamContexts.get( id );
        }

        @Override
        public DownstreamTupleSender getDownstreamTupleSender ( final PipelineReplicaId id )
        {
            return downstreamTupleSenders.get( id );
        }

        @Override
        public synchronized void notifyPipelineReplicaCompleted ( final PipelineReplicaId id )
        {
            assertTrue( completedPipelines.add( id ) );
            if ( !id.equals( targetPipelineReplicaId ) )
            {
                final UpstreamContext currentUpstreamContext = upstreamContexts.get( targetPipelineReplicaId );
                final UpstreamContext newUpstreamContext = currentUpstreamContext.withClosedUpstreamConnection( inputPortIndices.get( id ) );
                upstreamContexts.put( targetPipelineReplicaId, newUpstreamContext );
                runner.updatePipelineUpstreamContext();
            }
        }

        @Override
        public void notifyPipelineReplicaFailed ( final PipelineReplicaId id, final Throwable failure )
        {
            System.err.println( "Pipeline Replica " + id + " failed with " + failure );
            failure.printStackTrace();
        }

    }

}
