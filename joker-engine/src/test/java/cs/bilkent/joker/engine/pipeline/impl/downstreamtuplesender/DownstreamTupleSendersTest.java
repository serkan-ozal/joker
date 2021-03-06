package cs.bilkent.joker.engine.pipeline.impl.downstreamtuplesender;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import cs.bilkent.joker.engine.exception.JokerException;
import cs.bilkent.joker.engine.pipeline.DownstreamTupleSenderFailureFlag;
import cs.bilkent.joker.engine.tuplequeue.OperatorTupleQueue;
import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import cs.bilkent.joker.test.AbstractJokerTest;
import static java.util.Collections.singletonList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith( MockitoJUnitRunner.class )
public class DownstreamTupleSendersTest extends AbstractJokerTest
{

    private final DownstreamTupleSenderFailureFlag failureFlag = new DownstreamTupleSenderFailureFlag();

    private final TuplesImpl tuples = new TuplesImpl( 10 );

    @Mock
    private OperatorTupleQueue operatorTupleQueue;

    private int sourcePortIndex1 = 1, sourcePortIndex2 = 2, sourcePortIndex3 = 3, sourcePortIndex4 = 4;

    private int destinationPortIndex1 = 4, destinationPortIndex2 = 3, destinationPortIndex3 = 2, destinationPortIndex4 = 1;

    @Test
    public void testDownstreamTupleSender1 ()
    {
        sendViaDownstreamTupleSender1( 1 );
    }

    @Test( expected = JokerException.class )
    public void testDownstreamTupleSender1FailureWhenFailureFlagIsSet ()
    {
        failureFlag.setFailed();

        sendViaDownstreamTupleSender1( 0 );
    }

    private void sendViaDownstreamTupleSender1 ( final int offerResult )
    {

        final DownstreamTupleSender1 tupleSender = new DownstreamTupleSender1( failureFlag,
                                                                               sourcePortIndex1,
                                                                               destinationPortIndex1,
                                                                               operatorTupleQueue );
        addTuple( "key", "val", sourcePortIndex1 );

        setMock( sourcePortIndex1, destinationPortIndex1, offerResult );

        tupleSender.send( tuples );

        verifyMock( "key", "val", destinationPortIndex1 );
    }

    @Test
    public void testDownstreamTupleSenderN ()
    {
        sendViaDownstreamTupleSenderN( 1 );
    }

    @Test( expected = JokerException.class )
    public void testDownstreamTupleSenderNFailureWhenFailureFlagIsSet ()
    {
        failureFlag.setFailed();

        sendViaDownstreamTupleSenderN( 0 );
    }

    private void sendViaDownstreamTupleSenderN ( final int offerResult )
    {
        final DownstreamTupleSenderN tupleSender = new DownstreamTupleSenderN( failureFlag,
                                                                               new int[] { sourcePortIndex1,
                                                                                           sourcePortIndex2,
                                                                                           sourcePortIndex3,
                                                                                           sourcePortIndex4 },
                                                                               new int[] { destinationPortIndex1,
                                                                                           destinationPortIndex2,
                                                                                           destinationPortIndex3,
                                                                                           destinationPortIndex4 },
                                                                               operatorTupleQueue );
        addTuple( "key1", "val", sourcePortIndex1 );
        addTuple( "key2", "val", sourcePortIndex2 );
        addTuple( "key3", "val", sourcePortIndex3 );
        addTuple( "key4", "val", sourcePortIndex4 );

        setMock( sourcePortIndex1, destinationPortIndex1, offerResult );
        setMock( sourcePortIndex2, destinationPortIndex2, offerResult );
        setMock( sourcePortIndex3, destinationPortIndex3, offerResult );
        setMock( sourcePortIndex4, destinationPortIndex4, offerResult );

        tupleSender.send( tuples );

        verifyMock( "key1", "val", destinationPortIndex1 );
        verifyMock( "key2", "val", destinationPortIndex2 );
        verifyMock( "key3", "val", destinationPortIndex3 );
        verifyMock( "key4", "val", destinationPortIndex4 );
    }

    private void addTuple ( final String key, final Object val, final int sourcePortIndex )
    {
        final Tuple tuple = new Tuple();
        tuple.set( key, val );
        tuples.add( sourcePortIndex, tuple );
    }

    private void setMock ( final int sourcePortIndex, final int destinationPortIndex, final int offerResult )
    {
        when( operatorTupleQueue.offer( destinationPortIndex,
                                        tuples.getTuplesModifiable( sourcePortIndex ),
                                        0 ) ).thenReturn( offerResult );
    }

    private void verifyMock ( final String key, final Object val, final int destinationPortIndex )
    {
        final Tuple expected = new Tuple();
        expected.set( key, val );
        verify( operatorTupleQueue ).offer( destinationPortIndex, singletonList( expected ), 0 );
    }

}
