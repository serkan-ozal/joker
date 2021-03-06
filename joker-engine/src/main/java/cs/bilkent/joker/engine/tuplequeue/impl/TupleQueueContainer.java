package cs.bilkent.joker.engine.tuplequeue.impl;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import cs.bilkent.joker.engine.partition.PartitionKey;
import cs.bilkent.joker.engine.tuplequeue.TupleQueue;
import cs.bilkent.joker.engine.tuplequeue.TupleQueueDrainer;
import cs.bilkent.joker.engine.tuplequeue.impl.drainer.GreedyDrainer;
import cs.bilkent.joker.engine.tuplequeue.impl.queue.SingleThreadedTupleQueue;
import cs.bilkent.joker.operator.Tuple;
import cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByPort;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByPort.ALL_PORTS;
import static cs.bilkent.joker.operator.scheduling.ScheduleWhenTuplesAvailable.TupleAvailabilityByPort.ANY_PORT;
import gnu.trove.map.hash.THashMap;
import gnu.trove.set.hash.THashSet;

@NotThreadSafe
public class TupleQueueContainer
{

    private static final Logger LOGGER = LoggerFactory.getLogger( TupleQueueContainer.class );

    private static final int TUPLE_QUEUE_INITIAL_SIZE = 10;


    private final String operatorId;

    private final int inputPortCount;

    private final int partitionId;

    private final Function<Object, TupleQueue[]> tupleQueuesConstructor;

    private final Map<PartitionKey, TupleQueue[]> tupleQueuesByKeys;

    private final Set<PartitionKey> drainableKeys = new THashSet<>();

    private int[] tupleCounts;

    private TupleAvailabilityByPort tupleAvailabilityByPort;

    public TupleQueueContainer ( final String operatorId, final int inputPortCount, final int partitionId )
    {
        this.operatorId = operatorId;
        this.inputPortCount = inputPortCount;
        this.partitionId = partitionId;
        this.tupleQueuesByKeys = new THashMap<>();
        this.tupleQueuesConstructor = ( partitionKey ) ->
        {
            final TupleQueue[] tupleQueues = new TupleQueue[ inputPortCount ];
            for ( int i = 0; i < inputPortCount; i++ )
            {
                tupleQueues[ i ] = new SingleThreadedTupleQueue( TUPLE_QUEUE_INITIAL_SIZE );
            }
            return tupleQueues;
        };
        this.tupleCounts = new int[ inputPortCount ];
        Arrays.fill( this.tupleCounts, 1 );
        this.tupleAvailabilityByPort = ANY_PORT;
    }

    public String getOperatorId ()
    {
        return operatorId;
    }

    public int getPartitionId ()
    {
        return partitionId;
    }

    public boolean offer ( final int portIndex, final Tuple tuple, final PartitionKey key )
    {
        final TupleQueue[] tupleQueues = getTupleQueues( key );
        tupleQueues[ portIndex ].offer( tuple );
        return addToDrainableKeys( key, tupleQueues );
    }

    public int drain ( final boolean maySkipBlocking, final TupleQueueDrainer drainer )
    {
        int nonDrainableKeyCount = 0;
        if ( drainer instanceof GreedyDrainer )
        {
            final Iterator<PartitionKey> it = tupleQueuesByKeys.keySet().iterator();
            while ( it.hasNext() )
            {
                final PartitionKey key = it.next();
                final TupleQueue[] tupleQueues = getTupleQueues( key );

                drainer.drain( maySkipBlocking, key, tupleQueues );
                it.remove();

                if ( drainableKeys.remove( key ) )
                {
                    nonDrainableKeyCount++;
                }

                if ( !drainer.getResult().isEmpty() )
                {
                    return nonDrainableKeyCount;
                }
            }

            drainer.reset();
        }
        else
        {
            // tuple count based draining
            final Iterator<PartitionKey> it = drainableKeys.iterator();
            final boolean drainable = it.hasNext();
            if ( drainable )
            {
                final PartitionKey key = it.next();
                final TupleQueue[] tupleQueues = getTupleQueues( key );
                drainer.drain( maySkipBlocking, key, tupleQueues );
                if ( !checkIfDrainable( tupleQueues ) )
                {
                    it.remove();
                    nonDrainableKeyCount++;
                }
            }
        }

        return nonDrainableKeyCount;
    }

    public int clear ()
    {
        LOGGER.debug( "Clearing partitioned tuple queues of operator: {} partitionId={}", operatorId, partitionId );

        for ( Entry<PartitionKey, TupleQueue[]> e : tupleQueuesByKeys.entrySet() )
        {
            final TupleQueue[] tupleQueues = e.getValue();
            for ( int portIndex = 0; portIndex < tupleQueues.length; portIndex++ )
            {
                final TupleQueue tupleQueue = tupleQueues[ portIndex ];
                final int size = tupleQueue.size();
                if ( size > 0 )
                {
                    if ( LOGGER.isDebugEnabled() )
                    {
                        final List<Tuple> tuples = tupleQueue.poll( Integer.MAX_VALUE );
                        LOGGER.warn( "Tuple queue {} of operator: {} for key: {} has {} tuples before clear: {}",
                                     portIndex,
                                     operatorId,
                                     e.getKey(),
                                     size,
                                     tuples );
                    }
                    else
                    {
                        LOGGER.warn( "Tuple queue {} of operator: {} for key: {} has {} tuples before clear",
                                     portIndex,
                                     operatorId,
                                     e.getKey(),
                                     size );
                    }
                }
                tupleQueue.clear();
            }
        }

        tupleQueuesByKeys.clear();
        final int drainableKeyCount = drainableKeys.size();
        drainableKeys.clear();

        return drainableKeyCount;
    }

    public void setTupleCounts ( final int[] tupleCounts, final TupleAvailabilityByPort tupleAvailabilityByPort )
    {
        checkArgument( inputPortCount == tupleCounts.length,
                       "mismatching input port counts for tuple queue container of operator: %s operator input port count: %s, arg: %s",
                       operatorId,
                       inputPortCount,
                       tupleCounts.length );
        checkArgument( tupleAvailabilityByPort == ANY_PORT || tupleAvailabilityByPort == ALL_PORTS,
                       "invalid %s:%s for tuple queue container of operator: %s operator",
                       TupleAvailabilityByPort.class,
                       tupleAvailabilityByPort,
                       operatorId );
        this.tupleCounts = Arrays.copyOf( tupleCounts, tupleCounts.length );
        this.tupleAvailabilityByPort = tupleAvailabilityByPort;
        this.drainableKeys.clear();
        for ( Entry<PartitionKey, TupleQueue[]> e : tupleQueuesByKeys.entrySet() )
        {
            if ( checkIfDrainable( e.getValue() ) )
            {
                drainableKeys.add( e.getKey() );
            }
        }
    }

    public int getDrainableKeyCount ()
    {
        return drainableKeys.size();
    }

    public void addAvailableTupleCounts ( final int[] availableTupleCounts )
    {
        checkArgument( availableTupleCounts.length == inputPortCount );

        for ( TupleQueue[] tupleQueues : tupleQueuesByKeys.values() )
        {
            for ( int portIndex = 0; portIndex < inputPortCount; portIndex++ )
            {
                availableTupleCounts[ portIndex ] += tupleQueues[ portIndex ].size();
            }
        }
    }

    public boolean isEmpty ()
    {
        for ( TupleQueue[] tupleQueues : tupleQueuesByKeys.values() )
        {
            for ( TupleQueue tupleQueue : tupleQueues )
            {
                if ( tupleQueue.size() > 0 )
                {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean addToDrainableKeys ( final PartitionKey key, final TupleQueue[] tupleQueues )
    {
        if ( drainableKeys.contains( key ) )
        {
            return false;
        }

        if ( checkIfDrainable( tupleQueues ) )
        {
            drainableKeys.add( key );
            return true;
        }

        return false;
    }

    private boolean checkIfDrainable ( final TupleQueue[] tupleQueues )
    {
        if ( tupleAvailabilityByPort == ANY_PORT )
        {
            for ( int i = 0; i < inputPortCount; i++ )
            {
                if ( tupleQueues[ i ].size() >= tupleCounts[ i ] )
                {
                    return true;
                }
            }

            return false;
        }
        else
        {
            for ( int i = 0; i < inputPortCount; i++ )
            {
                if ( tupleQueues[ i ].size() < tupleCounts[ i ] )
                {
                    return false;
                }
            }

            return true;
        }
    }

    private TupleQueue[] getTupleQueues ( final PartitionKey key )
    {
        return tupleQueuesByKeys.computeIfAbsent( key, this.tupleQueuesConstructor );
    }

}
