package cs.bilkent.joker.engine.tuplequeue;

import javax.annotation.Nullable;

import cs.bilkent.joker.engine.partition.PartitionKey;
import cs.bilkent.joker.operator.impl.TuplesImpl;

/**
 * Drains tuples from given tuple queues and keeps them in internally to be able to return them afterwards.
 * Implementations can be stateful.
 */
public interface TupleQueueDrainer
{

    default void drain ( @Nullable PartitionKey key, TupleQueue[] tupleQueues )
    {
        drain( false, key, tupleQueues );
    }

    /**
     * Drains tuple queues of which tuples have the given partition key
     *
     * @param maySkipBlocking
     *         a boolean flag to specify if the drainer may not block if it is a blocking drainer
     * @param key
     *         partition key of the tuples which reside in the given tuple queues. Allowed to be null if tuples do not have a partition key
     * @param tupleQueues
     *         tuple queues to be drained
     */
    void drain ( boolean maySkipBlocking, @Nullable PartitionKey key, TupleQueue[] tupleQueues );

    /**
     * Returns the tuples drained from the tuple queues using {@link TupleQueueDrainer#drain(PartitionKey, TupleQueue[])} method
     *
     * @return the tuples drained from the tuple queues using {@link TupleQueueDrainer#drain(PartitionKey, TupleQueue[])} method
     */
    TuplesImpl getResult ();

    /**
     * Returns partition key of the tuples drained from the tuple queues
     *
     * @return partition key of the tuples drained from the tuple queues
     */
    PartitionKey getKey ();

    /**
     * Resets the internal state of the drainer
     */
    void reset ();

}
