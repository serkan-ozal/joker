package cs.bilkent.joker.engine.region.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;
import cs.bilkent.joker.engine.flow.RegionDef;
import cs.bilkent.joker.engine.region.RegionDefFormer;
import cs.bilkent.joker.flow.FlowDef;
import cs.bilkent.joker.flow.Port;
import cs.bilkent.joker.operator.OperatorDef;
import cs.bilkent.joker.operator.schema.runtime.PortRuntimeSchema;
import cs.bilkent.joker.operator.spec.OperatorType;
import static cs.bilkent.joker.operator.spec.OperatorType.PARTITIONED_STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATEFUL;
import static cs.bilkent.joker.operator.spec.OperatorType.STATELESS;
import static java.util.Collections.emptyList;
import static java.util.Collections.reverse;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@Singleton
@NotThreadSafe
public class RegionDefFormerImpl implements RegionDefFormer
{

    private static final Logger LOGGER = LoggerFactory.getLogger( RegionDefFormerImpl.class );


    private final IdGenerator idGenerator;

    @Inject
    public RegionDefFormerImpl ( final IdGenerator idGenerator )
    {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<RegionDef> createRegions ( final FlowDef flow )
    {
        final List<RegionDef> regions = new ArrayList<>();

        for ( List<OperatorDef> operatorSequence : splitSourceOperators( createOperatorSequences( flow ) ) )
        {
            regions.addAll( createRegions( operatorSequence ) );
        }

        return regions;
    }

    List<RegionDef> createRegions ( final List<OperatorDef> operatorSequence )
    {
        LOGGER.debug(
                "Creating regions for operator sequence: " + operatorSequence.stream().map( OperatorDef::getId ).collect( toList() ) );
        final List<RegionDef> regions = new ArrayList<>();

        OperatorType regionType = null;
        List<String> regionPartitionFieldNames = new ArrayList<>();
        List<OperatorDef> regionOperators = new ArrayList<>();

        for ( OperatorDef currentOperator : operatorSequence )
        {
            LOGGER.debug( "current operator={}", currentOperator.getId() );

            final OperatorType operatorType = currentOperator.getOperatorType();

            if ( operatorType == STATEFUL )
            {
                if ( regionType != null ) // finalize current region
                {
                    regions.add( new RegionDef( idGenerator.nextId(), regionType, regionPartitionFieldNames, regionOperators ) );
                    regionType = null;
                    regionPartitionFieldNames = new ArrayList<>();
                    regionOperators = new ArrayList<>();
                }

                regions.add( new RegionDef( idGenerator.nextId(),
                                            STATEFUL,
                                            emptyList(),
                                            singletonList( currentOperator ) ) ); // add operator as a region
            }
            else if ( operatorType == STATELESS )
            {
                if ( regionType == null )
                {
                    regionType = STATELESS;
                    regionOperators.add( currentOperator );
                }
                else if ( regionType == STATELESS || ( regionType == PARTITIONED_STATEFUL && containsAllFieldNamesOnInputPort(
                        currentOperator,
                        regionPartitionFieldNames ) ) )
                {
                    regionOperators.add( currentOperator );
                }
                else
                {
                    regions.add( new RegionDef( idGenerator.nextId(), regionType, regionPartitionFieldNames, regionOperators ) );
                    regionType = STATELESS;
                    regionPartitionFieldNames = new ArrayList<>();
                    regionOperators = new ArrayList<>();
                    regionOperators.add( currentOperator );
                }
            }
            else if ( operatorType == PARTITIONED_STATEFUL )
            {
                if ( regionType == null )
                {
                    regionType = PARTITIONED_STATEFUL;
                    regionPartitionFieldNames.addAll( currentOperator.getPartitionFieldNames() );
                    regionOperators.add( currentOperator );
                }
                else if ( regionType == PARTITIONED_STATEFUL )
                {
                    if ( !currentOperator.getPartitionFieldNames().containsAll( regionPartitionFieldNames ) )
                    {
                        regions.add( new RegionDef( idGenerator.nextId(),
                                                    PARTITIONED_STATEFUL,
                                                    regionPartitionFieldNames,
                                                    regionOperators ) );
                        regionPartitionFieldNames = new ArrayList<>( currentOperator.getPartitionFieldNames() );
                        regionOperators = new ArrayList<>();
                    }

                    regionOperators.add( currentOperator );
                }
                else
                {
                    // regionType is STATELESS
                    final ListIterator<OperatorDef> it = regionOperators.listIterator( regionOperators.size() );
                    final List<OperatorDef> newRegionOperators = new ArrayList<>();
                    while ( it.hasPrevious() )
                    {
                        final OperatorDef prev = it.previous();
                        if ( containsAllFieldNamesOnInputPort( prev, currentOperator.getPartitionFieldNames() ) )
                        {
                            newRegionOperators.add( prev );
                            it.remove();
                        }
                        else
                        {
                            break;
                        }
                    }

                    if ( !regionOperators.isEmpty() )
                    {
                        regions.add( new RegionDef( idGenerator.nextId(), STATELESS, emptyList(), regionOperators ) );
                    }

                    regionType = PARTITIONED_STATEFUL;
                    reverse( newRegionOperators );
                    newRegionOperators.add( currentOperator );
                    regionOperators = newRegionOperators;
                    regionPartitionFieldNames = new ArrayList<>( currentOperator.getPartitionFieldNames() );
                }
            }
            else
            {
                throw new IllegalArgumentException( "Invalid operator type for " + currentOperator.getId() );
            }
        }

        if ( regionType != null )
        {
            regions.add( new RegionDef( idGenerator.nextId(), regionType, regionPartitionFieldNames, regionOperators ) );
        }

        return regions;
    }

    Collection<List<OperatorDef>> createOperatorSequences ( final FlowDef flow )
    {

        final Collection<List<OperatorDef>> sequences = new ArrayList<>();

        final Set<OperatorDef> processedSequenceStartOperators = new HashSet<>();
        final Set<OperatorDef> sequenceStartOperators = flow.getSourceOperators();
        List<OperatorDef> currentOperatorSequence = new ArrayList<>();

        OperatorDef operator;
        while ( ( operator = removeRandomOperator( sequenceStartOperators ) ) != null )
        {
            processedSequenceStartOperators.add( operator );
            LOGGER.debug( "Starting new sequence with operator={}", operator.getId() );

            while ( true )
            {
                currentOperatorSequence.add( operator );
                LOGGER.debug( "Adding operator={} to current sequence", operator.getId() );

                final Collection<OperatorDef> downstreamOperators = getDownstreamOperators( flow, operator );
                final OperatorDef downstreamOperator = getSingleDownstreamOperatorWithSingleUpstreamOperator( flow, downstreamOperators );
                if ( downstreamOperator != null )
                {
                    operator = downstreamOperator;
                }
                else
                {
                    downstreamOperators.removeAll( processedSequenceStartOperators );
                    sequenceStartOperators.addAll( downstreamOperators );
                    sequences.add( currentOperatorSequence );

                    LOGGER.debug( "Sequence Completed! Sequence={}. New sequence starts={}",
                                  getOperatorIds( currentOperatorSequence ).collect( toList() ),
                                  getOperatorIds( downstreamOperators ).collect( toList() ) );
                    currentOperatorSequence = new ArrayList<>();
                    break;
                }
            }
        }

        return sequences;
    }

    Collection<List<OperatorDef>> splitSourceOperators ( final Collection<List<OperatorDef>> sequences )
    {
        final Collection<List<OperatorDef>> split = new ArrayList<>();
        for ( List<OperatorDef> operatorDefs : sequences )
        {
            checkState( operatorDefs.size() > 0 );
            if ( operatorDefs.size() == 1 || operatorDefs.get( 0 ).getInputPortCount() > 0 )
            {
                split.add( operatorDefs );
            }
            else
            {
                split.add( operatorDefs.subList( 0, 1 ) );
                split.add( operatorDefs.subList( 1, operatorDefs.size() ) );
            }
        }

        return split;
    }

    private Collection<OperatorDef> getDownstreamOperators ( final FlowDef flow, final OperatorDef operator )
    {
        final Set<OperatorDef> downstream = new HashSet<>();
        for ( Collection<Port> v : flow.getOutboundConnections( operator.getId() ).values() )
        {
            for ( Port p : v )
            {
                downstream.add( flow.getOperator( p.getOperatorId() ) );
            }
        }

        return downstream;
    }

    private boolean containsAllFieldNamesOnInputPort ( final OperatorDef operator, final List<String> fieldNames )
    {
        checkState( operator.getOperatorType() == STATELESS && operator.getInputPortCount() == 1 );

        final PortRuntimeSchema inputSchema = operator.getSchema().getInputSchema( 0 );
        return fieldNames.stream().allMatch( fieldName -> inputSchema.getField( fieldName ) != null );
    }

    private OperatorDef removeRandomOperator ( final Set<OperatorDef> operators )
    {
        OperatorDef operator = null;
        final Iterator<OperatorDef> it = operators.iterator();
        if ( it.hasNext() )
        {
            operator = it.next();
            it.remove();
        }

        return operator;
    }

    private OperatorDef getSingleDownstreamOperatorWithSingleUpstreamOperator ( final FlowDef flow,
                                                                                final Collection<OperatorDef> downstreamOperators )
    {
        if ( downstreamOperators.size() == 1 )
        {
            final OperatorDef downstream = downstreamOperators.iterator().next();
            final Map<Port, Set<Port>> upstream = flow.getInboundConnections( downstream.getId() );
            final Set<String> upstreamOperatorIds = new HashSet<>();
            for ( Collection<Port> u : upstream.values() )
            {
                for ( Port p : u )
                {
                    upstreamOperatorIds.add( p.getOperatorId() );
                }
            }
            return upstreamOperatorIds.size() == 1 ? downstream : null;
        }
        else
        {
            return null;
        }
    }

    private Stream<String> getOperatorIds ( final Collection<OperatorDef> operators )
    {
        return operators.stream().map( OperatorDef::getId );
    }

}
