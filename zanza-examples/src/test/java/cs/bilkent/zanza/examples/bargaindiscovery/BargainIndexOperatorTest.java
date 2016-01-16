package cs.bilkent.zanza.examples.bargaindiscovery;

import org.junit.Test;

import static cs.bilkent.zanza.examples.bargaindiscovery.BargainIndexOperator.ASKED_SIZE_FIELD;
import static cs.bilkent.zanza.examples.bargaindiscovery.BargainIndexOperator.ASKED_TICKER_SYMBOL_PRICE_FIELD;
import static cs.bilkent.zanza.examples.bargaindiscovery.BargainIndexOperator.BARGAIN_INDEX_FIELD;
import static cs.bilkent.zanza.examples.bargaindiscovery.CVWAPFunction.CVWAP_FIELD;
import static cs.bilkent.zanza.examples.bargaindiscovery.VWAPAggregatorOperator.TICKER_SYMBOL_FIELD;
import static cs.bilkent.zanza.examples.bargaindiscovery.VWAPAggregatorOperator.TIMESTAMP_FIELD;
import static cs.bilkent.zanza.flow.Port.DEFAULT_PORT_INDEX;
import cs.bilkent.zanza.kvstore.InMemoryKVStore;
import cs.bilkent.zanza.kvstore.KVStore;
import cs.bilkent.zanza.kvstore.KeyPrefixedInMemoryKvStore;
import cs.bilkent.zanza.operator.InvocationContext;
import cs.bilkent.zanza.operator.InvocationContext.InvocationReason;
import cs.bilkent.zanza.operator.InvocationResult;
import cs.bilkent.zanza.operator.PortsToTuples;
import cs.bilkent.zanza.operator.Tuple;
import cs.bilkent.zanza.utils.SimpleInvocationContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class BargainIndexOperatorTest
{

    private static final String TUPLE_PARTITION_KEY = "key1";

    private final BargainIndexOperator operator = new BargainIndexOperator();

    private final PortsToTuples input = new PortsToTuples();

    private final KVStore kvStore = new KeyPrefixedInMemoryKvStore( TUPLE_PARTITION_KEY, new InMemoryKVStore() );

    private final InvocationContext invocationContext = new SimpleInvocationContext( InvocationReason.SUCCESS, input, kvStore );


    @Test
    public void shouldReturnNoOutputWithQuoteButNoVWAP ()
    {
        input.add( 1, newQuoteTuple( 0, 5d, 100, 1 ) );
        final InvocationResult result = operator.process( invocationContext );
        assertThat( result.getOutputTuples().getPortCount(), equalTo( 0 ) );
    }

    @Test
    public void shouldReturnNoOutputFromQuoteWithAskedPriceHigherThanCVWAP ()
    {
        setCVWAPInKvStore( 50 );
        input.add( 1, newQuoteTuple( 0, 0.6, 1, 1 ) );
        final InvocationResult result = operator.process( invocationContext );
        assertThat( result.getOutputTuples().getPortCount(), equalTo( 0 ) );
    }

    @Test
    public void shouldReturnOutputFromQuoteWithAskedPriceLowerThanCVWAP ()
    {
        setCVWAPInKvStore( 50 );
        input.add( 1, newQuoteTuple( 0, 0.4, 1, 2 ) );
        final InvocationResult result = operator.process( invocationContext );
        assertThat( result.getOutputTuples().getPortCount(), equalTo( 1 ) );

        final Tuple output = result.getOutputTuples().getTuple( DEFAULT_PORT_INDEX, 0 );
        assertThat( output.get( BARGAIN_INDEX_FIELD ), equalTo( Math.exp( 10 ) ) );
        assertThat( output.getSequenceNumber(), equalTo( 2 ) );
    }

    @Test
    public void shouldReturnOutputsFromQuotesWithUpToDateVWAPs ()
    {
        setCVWAPInKvStore( 50 );
        input.add( 1, newQuoteTuple( 0, 0.4, 1, 1 ) );
        input.add( 0, newCVWAPTuple( 1, 60, 2 ) );
        input.add( 1, newQuoteTuple( 1, 0.3, 2, 2 ) );
        final InvocationResult result = operator.process( invocationContext );
        final PortsToTuples outputTuples = result.getOutputTuples();
        assertThat( outputTuples.getPortCount(), equalTo( 1 ) );

        final Tuple output1 = outputTuples.getTuple( DEFAULT_PORT_INDEX, 0 );
        assertThat( output1.get( BARGAIN_INDEX_FIELD ), equalTo( Math.exp( 10 ) ) );
        assertThat( output1.getSequenceNumber(), equalTo( 1 ) );
        final Tuple output2 = outputTuples.getTuple( DEFAULT_PORT_INDEX, 1 );
        assertThat( output2.get( BARGAIN_INDEX_FIELD ), equalTo( Math.exp( 30 ) * 2 ) );
        assertThat( output2.getSequenceNumber(), equalTo( 2 ) );
        assertThat( kvStore.get( TUPLE_PARTITION_KEY ), equalTo( 60d ) );
    }

    private Tuple newCVWAPTuple ( final long timestamp, final double cvwap, final int sequenceNumber )
    {
        final Tuple tuple = new Tuple();
        tuple.set( TICKER_SYMBOL_FIELD, TUPLE_PARTITION_KEY );
        tuple.set( CVWAP_FIELD, cvwap );
        tuple.set( TIMESTAMP_FIELD, timestamp );
        tuple.setSequenceNumber( sequenceNumber );

        return tuple;
    }

    private Tuple newQuoteTuple ( final long timestamp, final double askedPrice, final int askedSize, final int sequenceNumber )
    {
        final Tuple tuple = new Tuple();
        tuple.set( TICKER_SYMBOL_FIELD, TUPLE_PARTITION_KEY );
        tuple.set( TIMESTAMP_FIELD, timestamp );
        tuple.set( ASKED_TICKER_SYMBOL_PRICE_FIELD, askedPrice );
        tuple.set( ASKED_SIZE_FIELD, askedSize );
        tuple.setSequenceNumber( sequenceNumber );

        return tuple;
    }

    private void setCVWAPInKvStore ( final double cvwap )
    {
        kvStore.set( TUPLE_PARTITION_KEY, cvwap );
    }

}