package cs.bilkent.joker.engine.pipeline;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import static cs.bilkent.joker.engine.pipeline.OperatorReplicaStatus.COMPLETED;
import static cs.bilkent.joker.engine.pipeline.UpstreamConnectionStatus.ACTIVE;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.scheduleWhenTuplesAvailableOnDefaultPort;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import static org.junit.Assert.assertNull;

@RunWith( MockitoJUnitRunner.class )
public class OperatorReplicaCompletedStatusTest extends AbstractOperatorReplicaInvocationTest
{

    @Test
    public void shouldNotInvokeWhenOperatorReplicaStatusIsCompleted ()
    {
        final int inputPortCount = 1, outputPortCount = 1;
        final SchedulingStrategy initializationStrategy = scheduleWhenTuplesAvailableOnDefaultPort( 1 );
        initializeOperatorReplica( inputPortCount, outputPortCount, initializationStrategy );
        setOperatorReplicaStatus( COMPLETED );

        assertNull( operatorReplica.invoke( new TuplesImpl( inputPortCount ),
                                            OperatorReplicaInitializationTest.newUpstreamContextInstance( 0, inputPortCount, ACTIVE ) ) );
    }

}
