package cs.bilkent.joker.examples.bargaindiscovery;

import org.junit.Before;
import org.junit.Test;

import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.SINGLE_VOLUME_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.SINGLE_VWAP_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.SLIDE_FACTOR_CONfIG_PARAMETER;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.TICKER_SYMBOL_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.TIMESTAMP_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.TUPLE_COUNT_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.TUPLE_INPUT_VWAP_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.TUPLE_VOLUME_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.VOLUMES_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.VWAPS_FIELD;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.WINDOW_KEY;
import static cs.bilkent.joker.examples.bargaindiscovery.VWAPAggregatorOperator.WINDOW_SIZE_CONfIG_PARAMETER;
import static cs.bilkent.joker.flow.Port.DEFAULT_PORT_INDEX;
import static cs.bilkent.joker.operator.InvocationContext.InvocationReason.SUCCESS;
import cs.bilkent.joker.operator.OperatorConfig;
import cs.bilkent.joker.operator.OperatorDef;
import cs.bilkent.joker.operator.OperatorDefBuilder;
import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.operator.impl.InMemoryKVStore;
import cs.bilkent.joker.operator.impl.InitializationContextImpl;
import cs.bilkent.joker.operator.impl.InvocationContextImpl;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import cs.bilkent.joker.operator.kvstore.KVStore;
import cs.bilkent.joker.test.AbstractJokerTest;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;

public class VWAPAggregatorOperatorTest extends AbstractJokerTest
{

    private static final String TUPLE_PARTITION_KEY = "key1";


    private final TuplesImpl input = new TuplesImpl( 1 );

    private final TuplesImpl output = new TuplesImpl( 1 );

    private final KVStore kvStore = new InMemoryKVStore();

    private final InvocationContextImpl invocationContext = new InvocationContextImpl();

    private final OperatorConfig config = new OperatorConfig();

    private VWAPAggregatorOperator operator;

    private InitializationContextImpl initContext;


    @Before
    public void init () throws InstantiationException, IllegalAccessException
    {
        invocationContext.setInvocationParameters( SUCCESS, input, output, singletonList( TICKER_SYMBOL_FIELD ), kvStore );

        final OperatorDef operatorDef = OperatorDefBuilder.newInstance( "op", VWAPAggregatorOperator.class )
                                                          .setPartitionFieldNames( singletonList( TICKER_SYMBOL_FIELD ) )
                                                          .setConfig( config )
                                                          .build();

        operator = (VWAPAggregatorOperator) operatorDef.createOperator();
        initContext = new InitializationContextImpl( operatorDef, new boolean[] { true } );
    }

    @Test( expected = IllegalArgumentException.class )
    public void shouldFailToInitWithNoWindowSize ()
    {
        operator.init( initContext );
    }

    @Test
    public void shouldNotProduceOutputBeforeFirstWindowCompletes ()
    {
        configure();

        addInputTuple( 5, 20, 1 );
        addInputTuple( 10, 25, 2 );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( 0 ), equalTo( 0 ) );
        assertWindow( 2, new double[] { 5, 10, 0 }, new double[] { 20, 25, 0 }, 15, 45 );
    }

    @Test
    public void shouldProduceOutputForFirstWindow ()
    {
        configure();

        addInputTuple( 5, 20, 1 );
        addInputTuple( 10, 25, 2 );
        addInputTuple( 30, 60, 3 );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 1 ) );
        assertTuple( output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 ), 45, 105 );

        assertWindow( 3, new double[] { 5, 10, 30 }, new double[] { 20, 25, 60 }, 45, 105 );
    }

    @Test
    public void shouldNotProduceOutputBeforeSlideFactorCompletes ()
    {
        configure();

        addInputTuple( 5, 20, 1 );
        addInputTuple( 10, 25, 2 );
        addInputTuple( 30, 60, 3 );
        addInputTuple( 40, 50, 4 );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 1 ) );
        assertTuple( output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 ), 45, 105 );

        assertWindow( 4, new double[] { 40, 10, 30 }, new double[] { 50, 25, 60 }, 80, 135 );
    }

    @Test
    public void shouldProduceOutputWhenSlideFactorCompletes ()
    {
        configure();

        addInputTuple( 5, 20, 1 );
        addInputTuple( 10, 25, 2 );
        addInputTuple( 30, 60, 3 );
        addInputTuple( 40, 50, 4 );
        addInputTuple( 50, 40, 5 );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 2 ) );
        assertTuple( output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 ), 45, 105 );
        assertTuple( output.getTupleOrFail( DEFAULT_PORT_INDEX, 1 ), 120, 150 );

        assertWindow( 5, new double[] { 40, 50, 30 }, new double[] { 50, 40, 60 }, 120, 150 );
    }

    private void configure ()
    {
        final int windowSize = 3;
        final int slideFactor = 2;

        config.set( WINDOW_SIZE_CONfIG_PARAMETER, windowSize );
        config.set( SLIDE_FACTOR_CONfIG_PARAMETER, slideFactor );

        operator.init( initContext );
    }

    private void addInputTuple ( final double vwap, final double volume, final long timestamp )
    {
        final Tuple tuple = new Tuple();
        tuple.set( TUPLE_INPUT_VWAP_FIELD, vwap );
        tuple.set( TUPLE_VOLUME_FIELD, volume );
        tuple.set( TICKER_SYMBOL_FIELD, TUPLE_PARTITION_KEY );
        tuple.set( TIMESTAMP_FIELD, timestamp );

        input.add( tuple );
    }

    private void assertTuple ( final Tuple tuple, final double vwap, final double volume )
    {
        assertThat( tuple.get( TICKER_SYMBOL_FIELD ), equalTo( TUPLE_PARTITION_KEY ) );
        assertThat( tuple.getDouble( SINGLE_VWAP_FIELD ), equalTo( vwap ) );
        assertThat( tuple.getDouble( SINGLE_VOLUME_FIELD ), equalTo( volume ) );
    }

    private void assertWindow ( final int tupleCount,
                                final double[] vwaps,
                                final double[] volumes,
                                final double vwapSum,
                                final double volumeSum )
    {
        final Tuple window = kvStore.get( WINDOW_KEY );
        assertNotNull( window );

        assertThat( window.get( TUPLE_COUNT_FIELD ), equalTo( tupleCount ) );

        assertThat( window.get( VWAPS_FIELD ), equalTo( vwaps ) );
        assertThat( window.get( VOLUMES_FIELD ), equalTo( volumes ) );
        assertThat( window.get( SINGLE_VWAP_FIELD ), equalTo( vwapSum ) );
        assertThat( window.get( SINGLE_VOLUME_FIELD ), equalTo( volumeSum ) );
    }
}
