package cs.bilkent.joker.operators;

import java.util.List;
import java.util.function.Predicate;

import org.junit.Test;

import static cs.bilkent.joker.operator.InvocationContext.InvocationReason.SHUTDOWN;
import static cs.bilkent.joker.operator.InvocationContext.InvocationReason.SUCCESS;
import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.operator.impl.InitializationContextImpl;
import cs.bilkent.joker.operator.impl.InvocationContextImpl;
import cs.bilkent.joker.operator.impl.TuplesImpl;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import static cs.bilkent.joker.operators.FilterOperator.PREDICATE_CONFIG_PARAMETER;
import static cs.bilkent.joker.operators.FilterOperator.TUPLE_COUNT_CONFIG_PARAMETER;
import static cs.bilkent.joker.operators.MapperOperatorTest.assertScheduleWhenTuplesAvailableStrategy;
import cs.bilkent.joker.test.AbstractJokerTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertTrue;


public class FilterOperatorTest extends AbstractJokerTest
{

    private final FilterOperator operator = new FilterOperator();

    private final InitializationContextImpl initContext = new InitializationContextImpl();

    private final Predicate<Tuple> positiveCountsPredicate = tuple -> tuple.getInteger( "count" ) > 0;

    private final TuplesImpl input = new TuplesImpl( 1 );

    private final TuplesImpl output = new TuplesImpl( 1 );

    private final InvocationContextImpl invocationContext = new InvocationContextImpl( SUCCESS, input, output );

    @Test( expected = IllegalArgumentException.class )
    public void shouldFailWithNoPredicate ()
    {
        operator.init( initContext );
    }

    @Test
    public void shouldInitializeWithPredicate ()
    {
        initContext.getConfig().set( PREDICATE_CONFIG_PARAMETER, positiveCountsPredicate );

        final SchedulingStrategy strategy = operator.init( initContext );

        assertScheduleWhenTuplesAvailableStrategy( strategy, 1 );
    }

    @Test
    public void shouldInitializeWithInvalidPredicate ()
    {
        initContext.getConfig().set( PREDICATE_CONFIG_PARAMETER, positiveCountsPredicate );

        final SchedulingStrategy strategy = operator.init( initContext );

        assertScheduleWhenTuplesAvailableStrategy( strategy, 1 );
    }

    @Test
    public void shouldInitializeWithTupleCount ()
    {
        final int tupleCount = 5;
        initContext.getConfig().set( PREDICATE_CONFIG_PARAMETER, positiveCountsPredicate );
        initContext.getConfig().set( TUPLE_COUNT_CONFIG_PARAMETER, tupleCount );

        final SchedulingStrategy strategy = operator.init( initContext );

        assertScheduleWhenTuplesAvailableStrategy( strategy, tupleCount );
    }

    @Test
    public void shouldFilterMultipleTuplesForSuccessfulInvocation ()
    {
        final Tuple tuple1 = new Tuple();
        tuple1.set( "count", -1 );
        input.add( tuple1 );
        final Tuple tuple2 = new Tuple();
        tuple2.set( "count", 1 );
        input.add( tuple2 );
        shouldFilterTuplesWithPositiveCount( invocationContext );
    }

    @Test
    public void shouldFilterMultipleTuplesForErroneousInvocation ()
    {
        final Tuple tuple1 = new Tuple();
        tuple1.set( "count", -1 );
        input.add( tuple1 );
        final Tuple tuple2 = new Tuple();
        tuple2.set( "count", 1 );
        input.add( tuple2 );
        invocationContext.setReason( SHUTDOWN );
        shouldFilterTuplesWithPositiveCount( invocationContext );
    }

    private void shouldFilterTuplesWithPositiveCount ( final InvocationContextImpl invocationContext )
    {
        initContext.getConfig().set( PREDICATE_CONFIG_PARAMETER, positiveCountsPredicate );
        operator.init( initContext );

        operator.invoke( invocationContext );
        final List<Tuple> outputTuples = invocationContext.getOutput().getTuplesByDefaultPort();

        final long expectedCount = outputTuples.stream().filter( positiveCountsPredicate ).count();

        assertThat( outputTuples, hasSize( (int) expectedCount ) );
        final List<Tuple> inputTuples = invocationContext.getInput().getTuplesByDefaultPort();
        for ( Tuple outputTuple : outputTuples )
        {
            assertTrue( positiveCountsPredicate.test( outputTuple ) );
            assertTrue( inputTuples.contains( outputTuple ) );
        }
    }
}
