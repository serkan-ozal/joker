package cs.bilkent.joker.operator.scheduling;


import java.util.Arrays;
import java.util.List;

import static cs.bilkent.joker.flow.Port.DEFAULT_PORT_INDEX;
import static cs.bilkent.joker.impl.com.google.common.base.Preconditions.checkArgument;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByCount.AT_LEAST;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByCount.AT_LEAST_BUT_SAME_ON_ALL_PORTS;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByPort.ALL_PORTS;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByPort.ANY_PORT;

/**
 * Specifies how an operator should be invoked based on number of tuples that pile up in its input tuple queues.
 */
public final class ScheduleWhenTuplesAvailable implements SchedulingStrategy
{

    /**
     * Specifies which input ports should satisfy the given tuple count requirement.
     */
    public enum TupleAvailabilityByPort
    {
        /**
         * All input ports of an operator must satisfy given tuple counts for an invocation.
         */
        ALL_PORTS,

        /**
         * An operator can be invoked when at least tuple count requirement of a single input is satisfied.
         */
        ANY_PORT
    }


    /**
     * Specifies how many input tuples can be given for invocations of an operator
     */
    public enum TupleAvailabilityByCount
    {
        /**
         * Invocations are done with exact tuple counts specified by the given scheduling strategy.
         */
        EXACT,

        /**
         * Invocations are done with tuple counts that can be greater than or equal to the given tuple count.
         */
        AT_LEAST,

        /**
         * Invocations are done with tuple counts that can be greater than or equal to the given tuple count, but there will be same
         * number of tuples for each input port.
         */
        AT_LEAST_BUT_SAME_ON_ALL_PORTS
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAll ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                                                                 final int portCount,
                                                                                 final int tupleCount,
                                                                                 final int... ports )
    {
        return new ScheduleWhenTuplesAvailable( tupleAvailabilityByCount, ALL_PORTS, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAll ( final int portCount,
                                                                                 final int tupleCount,
                                                                                 final List<Integer> ports )
    {
        return new ScheduleWhenTuplesAvailable( AT_LEAST, ALL_PORTS, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAll ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                                                                 final int portCount,
                                                                                 final int tupleCount,
                                                                                 final List<Integer> ports )
    {
        return new ScheduleWhenTuplesAvailable( tupleAvailabilityByCount, ALL_PORTS, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAny ( final int portCount,
                                                                                 final int tupleCount,
                                                                                 final int... ports )
    {
        return new ScheduleWhenTuplesAvailable( AT_LEAST, ANY_PORT, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAny ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                                                                 final int portCount,
                                                                                 final int tupleCount,
                                                                                 final int... ports )
    {
        checkArgument( tupleAvailabilityByCount != AT_LEAST_BUT_SAME_ON_ALL_PORTS );
        return new ScheduleWhenTuplesAvailable( tupleAvailabilityByCount, ANY_PORT, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAny ( final int portCount,
                                                                                 final int tupleCount,
                                                                                 final List<Integer> ports )
    {
        return new ScheduleWhenTuplesAvailable( AT_LEAST, ANY_PORT, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnAny ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                                                                 final int portCount,
                                                                                 final int tupleCount,
                                                                                 final List<Integer> ports )
    {
        checkArgument( tupleAvailabilityByCount != AT_LEAST_BUT_SAME_ON_ALL_PORTS );
        return new ScheduleWhenTuplesAvailable( tupleAvailabilityByCount, ANY_PORT, portCount, tupleCount, ports );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnDefaultPort ( final int tupleCount )
    {
        return new ScheduleWhenTuplesAvailable( 1, DEFAULT_PORT_INDEX, tupleCount );
    }

    public static ScheduleWhenTuplesAvailable scheduleWhenTuplesAvailableOnDefaultPort ( final TupleAvailabilityByCount
                                                                                                 tupleAvailabilityByCount,
                                                                                         final int tupleCount )
    {
        return new ScheduleWhenTuplesAvailable( tupleAvailabilityByCount, 1, DEFAULT_PORT_INDEX, tupleCount );
    }


    private final int[] tupleCounts;

    private final TupleAvailabilityByCount tupleAvailabilityByCount;

    private final TupleAvailabilityByPort tupleAvailabilityByPort;

    public ScheduleWhenTuplesAvailable ( final int portCount, final int portIndex, final int tupleCount )
    {
        this( AT_LEAST, ANY_PORT, portCount, tupleCount, portIndex );
    }

    public ScheduleWhenTuplesAvailable ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                         final int portCount,
                                         final int portIndex,
                                         final int tupleCount )
    {
        this( tupleAvailabilityByCount, ANY_PORT, portCount, tupleCount, portIndex );
    }

    public ScheduleWhenTuplesAvailable ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                         final TupleAvailabilityByPort tupleAvailabilityByPort,
                                         final int[] tupleCounts )
    {
        checkArgument( tupleAvailabilityByCount != null );
        checkArgument( tupleAvailabilityByPort != null );
        checkArgument( tupleCounts.length == 1 || !( tupleAvailabilityByCount == AT_LEAST_BUT_SAME_ON_ALL_PORTS
                                                     && tupleAvailabilityByPort == ANY_PORT ) );
        this.tupleAvailabilityByCount = tupleAvailabilityByCount;
        this.tupleAvailabilityByPort = tupleAvailabilityByPort;
        this.tupleCounts = Arrays.copyOf( tupleCounts, tupleCounts.length );
        validateTupleCounts();
    }

    public ScheduleWhenTuplesAvailable ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                         final TupleAvailabilityByPort tupleAvailabilityByPort,
                                         final int portCount,
                                         final int tupleCount,
                                         final int... ports )
    {
        checkArgument( tupleAvailabilityByCount != null );
        checkArgument( tupleAvailabilityByPort != null );
        checkArgument( portCount > 0 );
        checkArgument( ports != null && ports.length > 0 );

        this.tupleAvailabilityByCount = tupleAvailabilityByCount;
        this.tupleAvailabilityByPort = tupleAvailabilityByPort;
        this.tupleCounts = new int[ portCount ];
        for ( final int portIndex : ports )
        {
            tupleCounts[ portIndex ] = tupleCount;
        }
        validateTupleCounts();
    }

    public ScheduleWhenTuplesAvailable ( final TupleAvailabilityByCount tupleAvailabilityByCount,
                                         final TupleAvailabilityByPort tupleAvailabilityByPort,
                                         final int portCount,
                                         final int tupleCount,
                                         final List<Integer> ports )
    {
        checkArgument( tupleAvailabilityByCount != null );
        checkArgument( tupleAvailabilityByPort != null );
        checkArgument( portCount > 0 );
        checkArgument( ports != null && ports.size() > 0 );

        this.tupleAvailabilityByCount = tupleAvailabilityByCount;
        this.tupleAvailabilityByPort = tupleAvailabilityByPort;
        this.tupleCounts = new int[ portCount ];
        for ( final int portIndex : ports )
        {
            tupleCounts[ portIndex ] = tupleCount;
        }
        validateTupleCounts();
    }

    private void validateTupleCounts ()
    {
        if ( tupleAvailabilityByPort == ALL_PORTS )
        {
            for ( int tupleCount : tupleCounts )
            {
                checkArgument( tupleCount > 0 );
            }
        }
        else if ( tupleAvailabilityByPort == ANY_PORT )
        {
            for ( int tupleCount : tupleCounts )
            {
                if ( tupleCount > 0 )
                {
                    return;
                }
            }

            throw new IllegalStateException();
        }
        else
        {
            throw new IllegalStateException();
        }
    }

    public int getPortCount ()
    {
        return tupleCounts.length;
    }

    public int[] getTupleCounts ()
    {
        return Arrays.copyOf( tupleCounts, tupleCounts.length );
    }

    public TupleAvailabilityByPort getTupleAvailabilityByPort ()
    {
        return tupleAvailabilityByPort;
    }

    public TupleAvailabilityByCount getTupleAvailabilityByCount ()
    {
        return tupleAvailabilityByCount;
    }

    public int getTupleCount ( final int portIndex )
    {
        return tupleCounts[ portIndex ];
    }

    @Override
    public boolean equals ( final Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        final ScheduleWhenTuplesAvailable that = (ScheduleWhenTuplesAvailable) o;

        return Arrays.equals( tupleCounts, that.tupleCounts ) && tupleAvailabilityByCount == that.tupleAvailabilityByCount
               && tupleAvailabilityByPort == that.tupleAvailabilityByPort;
    }

    @Override
    public int hashCode ()
    {
        int result = Arrays.hashCode( tupleCounts );
        result = 31 * result + tupleAvailabilityByCount.hashCode();
        result = 31 * result + tupleAvailabilityByPort.hashCode();
        return result;
    }

    @Override
    public String toString ()
    {
        return "ScheduleWhenTuplesAvailable{" + "tupleCounts=" + Arrays.toString( tupleCounts ) + ", tupleAvailabilityByCount="
               + tupleAvailabilityByCount + ", tupleAvailabilityByPort=" + tupleAvailabilityByPort + '}';
    }

}
