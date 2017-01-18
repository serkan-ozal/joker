package cs.bilkent.joker.engine.metric.impl;

import java.lang.management.ThreadMXBean;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import cs.bilkent.joker.engine.metric.PipelineMeter;
import static cs.bilkent.joker.engine.metric.PipelineMeter.PIPELINE_EXECUTION_INDEX;
import cs.bilkent.joker.engine.metric.impl.PipelineMetrics.PipelineMetricsSnapshot;
import cs.bilkent.joker.engine.pipeline.PipelineId;
import cs.bilkent.joker.test.AbstractJokerTest;
import static java.lang.System.arraycopy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@RunWith( MockitoJUnitRunner.class )
public class PipelineMetricsTest extends AbstractJokerTest
{

    private static final int FLOW_VERSION = 5;

    private static final int REPLICA_COUNT = 2;

    private static final int OPERATOR_COUNT = 3;

    private static final int CONSUMED_PORT_COUNT = 2;

    private static final int PRODUCED_PORT_COUNT = 1;

    private static final int HISTORY_SIZE = 10;

    private static final PipelineId PIPELINE_ID = new PipelineId( 10, 20 );

    @Mock
    private ThreadMXBean threadMXBean;

    @Mock
    private PipelineMeter meter;

    private PipelineMetrics metrics;

    @Before
    public void init ()
    {
        when( meter.getPipelineId() ).thenReturn( PIPELINE_ID );
        when( meter.getReplicaCount() ).thenReturn( REPLICA_COUNT );
        when( meter.getOperatorCount() ).thenReturn( OPERATOR_COUNT );
        when( meter.getConsumedPortCount() ).thenReturn( CONSUMED_PORT_COUNT );
        when( meter.getProducedPortCount() ).thenReturn( PRODUCED_PORT_COUNT );

        metrics = new PipelineMetrics( FLOW_VERSION, meter, HISTORY_SIZE );
    }

    @Test
    public void shouldSetInitialSnapshot ()
    {
        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();

        assertNotNull( snapshot );

        for ( int replicaIndex = 0; replicaIndex < REPLICA_COUNT; replicaIndex++ )
        {
            assertEquals( 0, snapshot.getCpuUtilizationRatio( replicaIndex ), 0.01 );
            assertEquals( 0, snapshot.getPipelineCost( replicaIndex ), 0.01 );
            for ( int operatorIndex = 0; operatorIndex < OPERATOR_COUNT; operatorIndex++ )
            {
                assertEquals( 0, snapshot.getOperatorCost( replicaIndex, operatorIndex ), 0.01 );
            }

            for ( int portIndex = 0; portIndex < CONSUMED_PORT_COUNT; portIndex++ )
            {
                assertEquals( 0, snapshot.getConsumeThroughput( replicaIndex, portIndex ) );
            }

            for ( int portIndex = 0; portIndex < PRODUCED_PORT_COUNT; portIndex++ )
            {
                assertEquals( 0, snapshot.getProduceThroughput( replicaIndex, portIndex ) );
            }
        }
    }

    @Test
    public void shouldInitialize ()
    {
        metrics.initialize( threadMXBean );

        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();

        assertNotNull( snapshot );

        for ( int replicaIndex = 0; replicaIndex < REPLICA_COUNT; replicaIndex++ )
        {
            assertEquals( 0, snapshot.getCpuUtilizationRatio( replicaIndex ), 0.01 );
            assertEquals( 0, snapshot.getPipelineCost( replicaIndex ), 0.01 );
            for ( int operatorIndex = 0; operatorIndex < OPERATOR_COUNT; operatorIndex++ )
            {
                assertEquals( 0, snapshot.getOperatorCost( replicaIndex, operatorIndex ), 0.01 );
            }

            for ( int portIndex = 0; portIndex < CONSUMED_PORT_COUNT; portIndex++ )
            {
                assertEquals( 0, snapshot.getConsumeThroughput( replicaIndex, portIndex ) );
            }

            for ( int portIndex = 0; portIndex < PRODUCED_PORT_COUNT; portIndex++ )
            {
                assertEquals( 0, snapshot.getProduceThroughput( replicaIndex, portIndex ) );
            }
        }
    }

    @Test
    public void shouldPublishThreadCpuTimes ()
    {
        final long[] initialThreadCpuTimes = { 1, 2 };
        final long[] newThreadCpuTimes = { 2, 4 };
        final long systemTimeDiff = 10;
        doAnswer( invocation ->
                  {
                      final long[] arr = (long[]) invocation.getArguments()[ 1 ];
                      arraycopy( initialThreadCpuTimes, 0, arr, 0, initialThreadCpuTimes.length );
                      return null;
                  } ).when( meter ).getThreadCpuTimes( anyObject(), anyObject() );

        metrics.initialize( threadMXBean );

        metrics.update( newThreadCpuTimes, systemTimeDiff );

        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();
        for ( int replicaIndex = 0; replicaIndex < REPLICA_COUNT; replicaIndex++ )
        {
            final double expected =
                    ( ( (double) newThreadCpuTimes[ replicaIndex ] ) - initialThreadCpuTimes[ replicaIndex ] ) / systemTimeDiff;
            assertEquals( "replica index=" + replicaIndex, expected, snapshot.getCpuUtilizationRatio( replicaIndex ), 0.01 );
        }
    }

    @Test
    public void shouldPublishThreadCpuTimesMultipleTimes ()
    {
        final long[] initialThreadCpuTimes = { 1, 2 };
        final long[] newThreadCpuTimes = { 2, 4 };
        final long[] newThreadCpuTimes2 = { 6, 10 };
        final long systemTimeDiff = 10;
        doAnswer( invocation ->
                  {
                      final long[] arr = (long[]) invocation.getArguments()[ 1 ];
                      arraycopy( initialThreadCpuTimes, 0, arr, 0, initialThreadCpuTimes.length );
                      return null;
                  } ).when( meter ).getThreadCpuTimes( anyObject(), anyObject() );

        metrics.initialize( threadMXBean );

        metrics.update( newThreadCpuTimes, systemTimeDiff );
        metrics.update( newThreadCpuTimes2, systemTimeDiff );

        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();
        for ( int replicaIndex = 0; replicaIndex < REPLICA_COUNT; replicaIndex++ )
        {
            final double expected =
                    ( ( (double) newThreadCpuTimes2[ replicaIndex ] ) - newThreadCpuTimes[ replicaIndex ] ) / systemTimeDiff;
            assertEquals( "replica index=" + replicaIndex, expected, snapshot.getCpuUtilizationRatio( replicaIndex ), 0.01 );
        }
    }

    @Test
    public void shouldPublishCosts ()
    {
        when( meter.getCurrentlyExecutingComponentIndex( threadMXBean, 0 ) ).thenReturn( PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         2 );

        when( meter.getCurrentlyExecutingComponentIndex( threadMXBean, 1 ) ).thenReturn( PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         1,
                                                                                         2,
                                                                                         2,
                                                                                         2,
                                                                                         2 );

        for ( int i = 0; i < 10; i++ )
        {
            metrics.sample( threadMXBean );
        }

        metrics.update( new long[ REPLICA_COUNT ], 10 );

        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();
        assertEquals( 0.4, snapshot.getPipelineCost( 0 ), 0.01 );
        assertEquals( 0.3, snapshot.getOperatorCost( 0, 0 ), 0.01 );
        assertEquals( 0.2, snapshot.getOperatorCost( 0, 1 ), 0.01 );
        assertEquals( 0.1, snapshot.getOperatorCost( 0, 2 ), 0.01 );
        assertEquals( 0.1, snapshot.getPipelineCost( 1 ), 0.01 );
        assertEquals( 0.2, snapshot.getOperatorCost( 1, 0 ), 0.01 );
        assertEquals( 0.3, snapshot.getOperatorCost( 1, 1 ), 0.01 );
        assertEquals( 0.4, snapshot.getOperatorCost( 1, 2 ), 0.01 );
    }

    @Test
    public void shouldPublishCostsMultipleTimes ()
    {
        when( meter.getCurrentlyExecutingComponentIndex( threadMXBean, 0 ) ).thenReturn( PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         2,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         1,
                                                                                         2,
                                                                                         2,
                                                                                         2,
                                                                                         2 );

        when( meter.getCurrentlyExecutingComponentIndex( threadMXBean, 1 ) ).thenReturn( PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         1,
                                                                                         2,
                                                                                         2,
                                                                                         2,
                                                                                         2,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         PIPELINE_EXECUTION_INDEX,
                                                                                         0,
                                                                                         0,
                                                                                         0,
                                                                                         1,
                                                                                         1,
                                                                                         2 );

        for ( int i = 0; i < 10; i++ )
        {
            metrics.sample( threadMXBean );
        }

        metrics.update( new long[ REPLICA_COUNT ], 10 );

        for ( int i = 0; i < 10; i++ )
        {
            metrics.sample( threadMXBean );
        }

        metrics.update( new long[ REPLICA_COUNT ], 10 );

        final PipelineMetricsSnapshot snapshot = metrics.getRecentSnapshot();
        assertEquals( 0.1, snapshot.getPipelineCost( 0 ), 0.01 );
        assertEquals( 0.2, snapshot.getOperatorCost( 0, 0 ), 0.01 );
        assertEquals( 0.3, snapshot.getOperatorCost( 0, 1 ), 0.01 );
        assertEquals( 0.4, snapshot.getOperatorCost( 0, 2 ), 0.01 );
        assertEquals( 0.4, snapshot.getPipelineCost( 1 ), 0.01 );
        assertEquals( 0.3, snapshot.getOperatorCost( 1, 0 ), 0.01 );
        assertEquals( 0.2, snapshot.getOperatorCost( 1, 1 ), 0.01 );
        assertEquals( 0.1, snapshot.getOperatorCost( 1, 2 ), 0.01 );
    }

}
