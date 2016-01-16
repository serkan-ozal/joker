package cs.bilkent.zanza.flow;


import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;


public class FlowDefinition
{
    public final Map<String, OperatorDefinition> operators;

    public final Multimap<Port, Port> connections;

    public FlowDefinition ( final Map<String, OperatorDefinition> operators, final Multimap<Port, Port> connections )
    {
        validateFlowDefinition( operators, connections );
        this.operators = Collections.unmodifiableMap( operators );
        this.connections = Multimaps.unmodifiableMultimap( connections );
    }

    // TODO improve flow validation
    private void validateFlowDefinition ( final Map<String, OperatorDefinition> operators, final Multimap<Port, Port> connections )
    {
        checkNotNull( operators );
        checkNotNull( connections );
        checkAllOperatorsHaveConnection( operators, connections );
    }

    private void checkAllOperatorsHaveConnection ( final Map<String, OperatorDefinition> operators, final Multimap<Port, Port> connections )
    {
        final long connectedOperatorCount = connections.entries()
                                                       .stream()
                                                       .flatMap( entry -> Stream.of( entry.getKey().operatorId,
                                                                                     entry.getValue().operatorId ) )
                                                       .distinct()
                                                       .count();
        checkState( operators.size() == connectedOperatorCount, "Invalid flow definition!" );
    }

}