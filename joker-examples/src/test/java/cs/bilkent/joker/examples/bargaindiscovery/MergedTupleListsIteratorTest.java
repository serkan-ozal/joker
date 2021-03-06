package cs.bilkent.joker.examples.bargaindiscovery;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Before;
import org.junit.Test;

import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.test.AbstractJokerTest;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;

public class MergedTupleListsIteratorTest extends AbstractJokerTest
{

    private final static String FIELD_NAME = "val";

    private static final Comparator<Tuple> COMPARATOR = Comparator.comparing( left -> left.getInteger( FIELD_NAME ) );


    private final Tuple val1 = new Tuple();

    private final Tuple val2 = new Tuple();

    private final Tuple val3 = new Tuple();

    private final Tuple val4 = new Tuple();

    @Before
    public void init ()
    {
        val1.set( FIELD_NAME, 1 );
        val2.set( FIELD_NAME, 2 );
        val3.set( FIELD_NAME, 3 );
        val4.set( FIELD_NAME, 4 );
    }

    @Test( expected = NoSuchElementException.class )
    public void shouldHaveNoNextValueWithEmptyContent ()
    {
        final MergedTupleListsIterator it = new MergedTupleListsIterator( emptyList(), emptyList(), COMPARATOR );
        assertFalse( it.hasNext() );
        it.next();
    }

    @Test
    public void shouldIterateWithOnlyLeftList ()
    {
        shouldIterateSingleList( true );
    }

    @Test
    public void shouldIterateWithOnlyRightList ()
    {
        shouldIterateSingleList( false );
    }

    private void shouldIterateSingleList ( final boolean left )
    {
        final List<Tuple> vals = Arrays.asList( val1, val2, val3 );

        final MergedTupleListsIterator it = new MergedTupleListsIterator( left ? vals : emptyList(),
                                                                          left ? emptyList() : vals,
                                                                          COMPARATOR );
        assertVals( it, val1, val2, val3 );
    }

    @Test
    public void test1 ()
    {
        final List<Tuple> left = Arrays.asList( val1, val2, val3 );
        final List<Tuple> right = singletonList( val4 );
        assertVals( new MergedTupleListsIterator( left, right, COMPARATOR ), val1, val2, val3, val4 );
    }

    @Test
    public void test2 ()
    {
        final List<Tuple> left = singletonList( val4 );
        final List<Tuple> right = Arrays.asList( val1, val2, val3 );
        assertVals( new MergedTupleListsIterator( left, right, COMPARATOR ), val1, val2, val3, val4 );
    }

    @Test
    public void test3 ()
    {
        final List<Tuple> left = Arrays.asList( val1, val3 );
        final List<Tuple> right = Arrays.asList( val2, val4 );
        assertVals( new MergedTupleListsIterator( left, right, COMPARATOR ), val1, val2, val3, val4 );
    }

    @Test
    public void test4 ()
    {
        final List<Tuple> left = Arrays.asList( val2, val4 );
        final List<Tuple> right = Arrays.asList( val1, val3 );
        assertVals( new MergedTupleListsIterator( left, right, COMPARATOR ), val1, val2, val3, val4 );
    }

    @Test
    public void test5 ()
    {
        final Tuple val1Left = new Tuple();
        val1.consumeEntries( val1Left::set );

        assertVals( new MergedTupleListsIterator( singletonList( val1Left ), singletonList( val1 ), COMPARATOR ), val1Left, val1 );
    }

    private void assertVals ( final Iterator<Tuple> it, final Tuple... expectedVals )
    {
        for ( Tuple expected : expectedVals )
        {
            assertThat( it.next(), equalTo( expected ) );
        }

        assertFalse( it.hasNext() );
    }

}
