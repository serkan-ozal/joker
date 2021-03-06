package cs.bilkent.joker.operators;

import org.junit.Before;
import org.junit.Test;

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
import cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByCount.AT_LEAST;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import static cs.bilkent.joker.operators.ExponentialMovingAverageAggregationOperator.CURRENT_WINDOW_KEY;
import static cs.bilkent.joker.operators.ExponentialMovingAverageAggregationOperator.FIELD_NAME_CONFIG_PARAMETER;
import static cs.bilkent.joker.operators.ExponentialMovingAverageAggregationOperator.TUPLE_COUNT_FIELD;
import static cs.bilkent.joker.operators.ExponentialMovingAverageAggregationOperator.VALUE_FIELD;
import static cs.bilkent.joker.operators.ExponentialMovingAverageAggregationOperator.WEIGHT_CONFIG_PARAMETER;
import cs.bilkent.joker.test.AbstractJokerTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


public class ExponentialMovingAverageAggregationOperatorTest extends AbstractJokerTest
{

    private ExponentialMovingAverageAggregationOperator operator;

    private final TuplesImpl input = new TuplesImpl( 1 );

    private final TuplesImpl output = new TuplesImpl( 1 );

    private final KVStore kvStore = new InMemoryKVStore();

    private final InvocationContextImpl invocationContext = new InvocationContextImpl();

    private final OperatorConfig config = new OperatorConfig();

    private InitializationContextImpl initContext;

    @Before
    public void init () throws InstantiationException, IllegalAccessException
    {
        invocationContext.setInvocationParameters( SUCCESS, input, output, null, kvStore );

        final OperatorDef operatorDef = OperatorDefBuilder.newInstance( "op", ExponentialMovingAverageAggregationOperator.class )
                                                          .setConfig( config )
                                                          .build();
        operator = (ExponentialMovingAverageAggregationOperator) operatorDef.createOperator();
        initContext = new InitializationContextImpl( operatorDef, new boolean[] { true } );
    }

    @Test( expected = IllegalArgumentException.class )
    public void shouldFailWithNoTupleCount ()
    {
        config.set( FIELD_NAME_CONFIG_PARAMETER, "val" );
        operator.init( initContext );
    }

    @Test( expected = IllegalArgumentException.class )
    public void shouldFailWithNoFieldName ()
    {
        config.set( WEIGHT_CONFIG_PARAMETER, .5 );
        operator.init( initContext );
    }

    @Test
    public void shouldInitializeWithProperConfig ()
    {
        configure();

        final SchedulingStrategy strategy = operator.init( initContext );
        assertTrue( strategy instanceof ScheduleWhenTuplesAvailable );

        final ScheduleWhenTuplesAvailable tupleAvailabilitySchedule = (ScheduleWhenTuplesAvailable) strategy;
        assertThat( tupleAvailabilitySchedule.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 1 ) );
        assertThat( tupleAvailabilitySchedule.getTupleAvailabilityByCount(), equalTo( AT_LEAST ) );
    }

    @Test
    public void shouldSetAccumulatorForFirstValue ()
    {
        configure();

        operator.init( initContext );
        final Tuple tuple = new Tuple();
        tuple.set( "val", 10 );
        input.add( tuple );

        operator.invoke( invocationContext );

        final Tuple value = kvStore.get( CURRENT_WINDOW_KEY );
        assertNotNull( value );

        assertValue( value, 10 );
    }

    @Test
    public void shouldSetAccumulatorForSecondValue ()
    {
        configure();
        setCurrentAvgInKVStore( 0, 5 );

        operator.init( initContext );
        final Tuple tuple = new Tuple();
        tuple.set( "val", 10 );
        input.add( tuple );

        operator.invoke( invocationContext );

        final Tuple value = kvStore.get( CURRENT_WINDOW_KEY );
        assertNotNull( value );

        assertValue( value, 10 );
    }

    @Test
    public void shouldReturnFirstAverage ()
    {
        configure();
        setCurrentAvgInKVStore( 3, 6 );

        operator.init( initContext );
        final Tuple val = new Tuple();
        val.set( "val", 4 );
        input.add( val );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 1 ) );
        final Tuple tuple = output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 );
        assertValue( tuple, 5 );

        final Tuple value = kvStore.get( CURRENT_WINDOW_KEY );
        assertNotNull( value );

        assertValue( value, 5 );
        assertTupleCount( value, 4 );
    }

    @Test
    public void shouldReturnSecondAverage ()
    {
        configure();
        setCurrentAvgInKVStore( 4, 10 );

        operator.init( initContext );
        final Tuple t = new Tuple();
        t.set( "val", 5 );
        input.add( t );
        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 1 ) );
        final Tuple tuple = output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 );
        assertValue( tuple, 7.5 );

        final Tuple value = kvStore.get( CURRENT_WINDOW_KEY );
        assertNotNull( value );

        assertValue( value, 7.5 );
        assertTupleCount( value, 5 );
    }

    @Test
    public void shouldReturnMultipleAverages ()
    {
        configure();
        setCurrentAvgInKVStore( 3, 6 );

        operator.init( initContext );
        final Tuple t1 = new Tuple();
        t1.set( "val", 4 );
        input.add( t1 );
        final Tuple t2 = new Tuple();
        t2.set( "val", 7 );
        input.add( t2 );

        operator.invoke( invocationContext );

        assertThat( output.getTupleCount( DEFAULT_PORT_INDEX ), equalTo( 2 ) );

        final Tuple tuple1 = output.getTupleOrFail( DEFAULT_PORT_INDEX, 0 );
        assertValue( tuple1, 5 );
        final Tuple tuple2 = output.getTupleOrFail( DEFAULT_PORT_INDEX, 1 );
        assertValue( tuple2, 6 );

    }

    private void configure ()
    {
        config.set( FIELD_NAME_CONFIG_PARAMETER, "val" );
        config.set( WEIGHT_CONFIG_PARAMETER, .5 );
    }

    private void setCurrentAvgInKVStore ( final int tupleCount, final double value )
    {
        final Tuple tuple = new Tuple();
        tuple.set( TUPLE_COUNT_FIELD, tupleCount );
        tuple.set( VALUE_FIELD, value );
        kvStore.set( CURRENT_WINDOW_KEY, tuple );
    }

    private void assertValue ( final Tuple tuple, final double expectedValue )
    {
        assertThat( tuple.getDouble( VALUE_FIELD ), equalTo( expectedValue ) );
    }

    private void assertTupleCount ( final Tuple tuple, final int expectedTupleCount )
    {
        assertThat( tuple.getInteger( TUPLE_COUNT_FIELD ), equalTo( expectedTupleCount ) );
    }

}
