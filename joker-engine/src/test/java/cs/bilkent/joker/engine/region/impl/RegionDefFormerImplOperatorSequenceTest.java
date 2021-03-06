package cs.bilkent.joker.engine.region.impl;

import java.util.Collection;
import java.util.List;

import org.junit.Test;

import cs.bilkent.joker.flow.FlowDef;
import cs.bilkent.joker.flow.FlowDefBuilder;
import cs.bilkent.joker.operator.InitializationContext;
import cs.bilkent.joker.operator.InvocationContext;
import cs.bilkent.joker.operator.Operator;
import cs.bilkent.joker.operator.OperatorDef;
import cs.bilkent.joker.operator.OperatorDefBuilder;
import cs.bilkent.joker.operator.scheduling.SchedulingStrategy;
import cs.bilkent.joker.operator.spec.OperatorSpec;
import static cs.bilkent.joker.operator.spec.OperatorType.STATEFUL;
import cs.bilkent.joker.operators.BeaconOperator;
import cs.bilkent.joker.operators.MapperOperator;
import cs.bilkent.joker.test.AbstractJokerTest;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertTrue;

public class RegionDefFormerImplOperatorSequenceTest extends AbstractJokerTest
{

    private final RegionDefFormerImpl regionFormer = new RegionDefFormerImpl( new IdGenerator() );

    private final FlowDefBuilder flowBuilder = new FlowDefBuilder();


    @Test
    public void testFlow1 ()
    {
        /*
         * O1 --> O2
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 1 ) );

        final List<OperatorDef> operators = operatorSequences.iterator().next();
        assertThat( operators, hasSize( 2 ) );
        assertThat( operators.get( 0 ).getId(), equalTo( "o1" ) );
        assertThat( operators.get( 1 ).getId(), equalTo( "o2" ) );
    }

    @Test
    public void testFlow2 ()
    {
        /*
         * O1 --> O2 --> O3
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o2", "o3" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 1 ) );

        final List<OperatorDef> operators = operatorSequences.iterator().next();
        assertThat( operators, hasSize( 3 ) );
        assertThat( operators.get( 0 ).getId(), equalTo( "o1" ) );
        assertThat( operators.get( 1 ).getId(), equalTo( "o2" ) );
        assertThat( operators.get( 2 ).getId(), equalTo( "o3" ) );
    }

    @Test
    public void testFlow3 ()
    {
        /*
         *
         *                   /--> O4
         *                  /
         * O1 --> O2 --> O3
         *                  \
         *                   \--> O5
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o2", "o3" );
        flowBuilder.connect( "o3", "o4" );
        flowBuilder.connect( "o3", "o5" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 3 ) );
        assertOperatorSequence( asList( "o1", "o2", "o3" ), operatorSequences );
        assertOperatorSequence( singletonList( "o4" ), operatorSequences );
        assertOperatorSequence( singletonList( "o5" ), operatorSequences );
    }

    @Test
    public void testFlow4 ()
    {
        /*
         *
         * O1 --> O2 --> O3 --> O5
         *             /
         *       O4 --/
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o2", "o3" );
        flowBuilder.connect( "o4", "o3" );
        flowBuilder.connect( "o3", "o5" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 3 ) );
        assertOperatorSequence( asList( "o1", "o2" ), operatorSequences );
        assertOperatorSequence( asList( "o3", "o5" ), operatorSequences );
        assertOperatorSequence( singletonList( "o4" ), operatorSequences );
    }

    @Test
    public void testFlow5 ()
    {
        /*
         *
         * O1 --> O2 --> O3
         *             /
         *       O4 --/
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", BeaconOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o2", "o3" );
        flowBuilder.connect( "o4", "o3" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 3 ) );
        assertOperatorSequence( asList( "o1", "o2" ), operatorSequences );
        assertOperatorSequence( singletonList( "o3" ), operatorSequences );
        assertOperatorSequence( singletonList( "o4" ), operatorSequences );
    }

    @Test
    public void testFlow6 ()
    {
        /*
         *
         *     /--> O2
         *    /        \
         * O1           --> O4 --> 05
         *    \        /
         *     \--> O3
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o1", "o3" );
        flowBuilder.connect( "o2", "o4" );
        flowBuilder.connect( "o3", "o4" );
        flowBuilder.connect( "o4", "o5" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 4 ) );
        assertOperatorSequence( singletonList( "o1" ), operatorSequences );
        assertOperatorSequence( singletonList( "o2" ), operatorSequences );
        assertOperatorSequence( singletonList( "o3" ), operatorSequences );
        assertOperatorSequence( asList( "o4", "o5" ), operatorSequences );
    }

    @Test
    public void testFlow7 ()
    {
        /*
         *
         *              /--> O4
         *             /
         *     /--> O2
         *    /        \
         * O1           \--> O5
         *    \
         *     \--> O3
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o1", "o3" );
        flowBuilder.connect( "o2", "o4" );
        flowBuilder.connect( "o2", "o5" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 5 ) );
        assertOperatorSequence( singletonList( "o1" ), operatorSequences );
        assertOperatorSequence( singletonList( "o2" ), operatorSequences );
        assertOperatorSequence( singletonList( "o3" ), operatorSequences );
        assertOperatorSequence( singletonList( "o4" ), operatorSequences );
        assertOperatorSequence( singletonList( "o5" ), operatorSequences );
    }

    @Test
    public void testFlow8 ()
    {
        /*
         *
         *         O5 --\
         *               \
         *     /--> O2 --> O4
         *    /
         * O1
         *    \
         *     \--> O3
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", BeaconOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o1", "o3" );
        flowBuilder.connect( "o2", "o4" );
        flowBuilder.connect( "o5", "o4" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );

        assertThat( operatorSequences, hasSize( 5 ) );
        assertOperatorSequence( singletonList( "o1" ), operatorSequences );
        assertOperatorSequence( singletonList( "o2" ), operatorSequences );
        assertOperatorSequence( singletonList( "o3" ), operatorSequences );
        assertOperatorSequence( singletonList( "o4" ), operatorSequences );
        assertOperatorSequence( singletonList( "o5" ), operatorSequences );
    }

    @Test
    public void test9 ()
    {
         /*
         *
         *     /--\
         *    /    \
         * O1       > O2
         *    \    /
         *     \--/
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", StatefulOperatorWith2OutputPorts.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.connect( "o1", 0, "o2" );
        flowBuilder.connect( "o1", 1, "o2" );

        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );
        assertThat( operatorSequences, hasSize( 1 ) );
        assertOperatorSequence( asList( "o1", "o2" ), operatorSequences );
    }

    @Test
    public void test10 ()
    {
         /*
         * O1 --> O2
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );
        Collection<List<OperatorDef>> split = regionFormer.splitSourceOperators( operatorSequences );

        assertThat( split, hasSize( 2 ) );
        assertOperatorSequence( singletonList( "o1" ), split );
        assertOperatorSequence( singletonList( "o2" ), split );
    }

    @Test
    public void testFlow11 ()
    {
        /*
         *
         * O1 --> O2 --> O3 --> O5
         *             /
         * 06 --> O4 --/
         *
         */

        flowBuilder.add( OperatorDefBuilder.newInstance( "o1", BeaconOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o2", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o3", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o4", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o5", MapperOperator.class ) );
        flowBuilder.add( OperatorDefBuilder.newInstance( "o6", BeaconOperator.class ) );
        flowBuilder.connect( "o1", "o2" );
        flowBuilder.connect( "o2", "o3" );
        flowBuilder.connect( "o4", "o3" );
        flowBuilder.connect( "o3", "o5" );
        flowBuilder.connect( "o6", "o4" );
        final FlowDef flow = flowBuilder.build();

        final Collection<List<OperatorDef>> operatorSequences = regionFormer.createOperatorSequences( flow );
        final Collection<List<OperatorDef>> split = regionFormer.splitSourceOperators( operatorSequences );

        assertThat( split, hasSize( 5 ) );
        assertOperatorSequence( singletonList( "o1" ), split );
        assertOperatorSequence( singletonList( "o2" ), split );
        assertOperatorSequence( asList( "o3", "o5" ), split );
        assertOperatorSequence( singletonList( "o4" ), split );
        assertOperatorSequence( singletonList( "o6" ), split );
    }

    private void assertOperatorSequence ( final List<String> expectedOperatorIds, Collection<List<OperatorDef>> operatorSequences )
    {
        final boolean sequenceExists = operatorSequences.stream().anyMatch( operatorSequence ->
                                                                            {
                                                                                final List<String> sequenceOperatorIds = operatorSequence
                                                                                                                                 .stream()
                                                                                                                                 .map( OperatorDef::getId )
                                                                                                                                 .collect(
                                                                                                                                                 toList() );
                                                                                return sequenceOperatorIds.equals( expectedOperatorIds );
                                                                            } );

        assertTrue( sequenceExists );
    }

    @OperatorSpec( type = STATEFUL, inputPortCount = 0, outputPortCount = 2 )
    public static class StatefulOperatorWith2OutputPorts implements Operator
    {

        @Override
        public SchedulingStrategy init ( final InitializationContext context )
        {
            return null;
        }

        @Override
        public void invoke ( final InvocationContext invocationContext )
        {

        }

    }

}
