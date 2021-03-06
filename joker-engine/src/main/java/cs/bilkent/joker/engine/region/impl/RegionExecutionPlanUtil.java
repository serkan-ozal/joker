package cs.bilkent.joker.engine.region.impl;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import cs.bilkent.joker.engine.flow.PipelineId;
import cs.bilkent.joker.engine.flow.RegionExecutionPlan;
import static java.util.stream.Collectors.toList;

public class RegionExecutionPlanUtil
{

    private static List<PipelineId> getMergeablePipelineIds ( final List<PipelineId> pipelineIds )
    {
        checkArgument( pipelineIds != null && pipelineIds.size() > 1 );

        final List<PipelineId> pipelineIdsSorted = new ArrayList<>( pipelineIds );
        pipelineIdsSorted.sort( PipelineId::compareTo );

        checkArgument( pipelineIdsSorted.get( 0 ).getRegionId() == pipelineIdsSorted.get( pipelineIdsSorted.size() - 1 ).getRegionId(),
                       "multiple region ids in %s",
                       pipelineIds );
        checkArgument( pipelineIdsSorted.stream().map( PipelineId::getPipelineStartIndex ).distinct().count() == pipelineIds.size(),
                       "duplicate pipeline ids in %s",
                       pipelineIds );

        return pipelineIdsSorted;
    }


    public static List<Integer> getMergeablePipelineStartIndices ( final RegionExecutionPlan regionExecutionPlan,
                                                                   final List<PipelineId> pipelineIds )
    {
        final List<Integer> startIndicesToMerge = getMergeablePipelineIds( pipelineIds ).stream()
                                                                                        .map( PipelineId::getPipelineStartIndex )
                                                                                        .collect( toList() );

        checkArgument( checkPipelineStartIndicesToMerge( regionExecutionPlan, startIndicesToMerge ),
                       "invalid pipeline start indices to merge: %s current pipeline start indices: %s region=%s",
                       startIndicesToMerge,
                       regionExecutionPlan.getPipelineStartIndices(),
                       regionExecutionPlan.getRegionId() );

        return startIndicesToMerge;
    }

    public static boolean checkPipelineStartIndicesToMerge ( final RegionExecutionPlan regionExecutionPlan,
                                                             final List<Integer> pipelineStartIndicesToMerge )
    {
        if ( pipelineStartIndicesToMerge.size() < 2 )
        {
            return false;
        }

        final List<Integer> pipelineStartIndices = regionExecutionPlan.getPipelineStartIndices();

        int index = pipelineStartIndices.indexOf( pipelineStartIndicesToMerge.get( 0 ) );
        if ( index < 0 )
        {
            return false;
        }

        for ( int i = 1; i < pipelineStartIndicesToMerge.size(); i++ )
        {
            final int j = pipelineStartIndices.indexOf( pipelineStartIndicesToMerge.get( i ) );
            if ( j != ( index + 1 ) )
            {
                return false;
            }
            index = j;
        }

        return true;
    }

    public static List<Integer> getPipelineStartIndicesToSplit ( final RegionExecutionPlan regionExecutionPlan,
                                                                 final PipelineId pipelineId,
                                                                 final List<Integer> pipelineOperatorIndicesToSplit )
    {
        checkArgument( pipelineId != null, "pipeline id to split cannot be null" );
        checkArgument( pipelineOperatorIndicesToSplit != null && pipelineOperatorIndicesToSplit.size() > 0,
                       "there must be at least 1 operator split index for Pipeline %s",
                       pipelineId );

        int curr = 0;
        final int operatorCount = regionExecutionPlan.getOperatorCountByPipelineStartIndex( pipelineId.getPipelineStartIndex() );
        for ( int p : pipelineOperatorIndicesToSplit )
        {
            checkArgument( p > curr && p < operatorCount );
            curr = p;
        }

        final List<Integer> pipelineStartIndicesToSplit = new ArrayList<>();
        pipelineStartIndicesToSplit.add( pipelineId.getPipelineStartIndex() );
        for ( int i : pipelineOperatorIndicesToSplit )
        {
            pipelineStartIndicesToSplit.add( pipelineId.getPipelineStartIndex() + i );
        }

        return pipelineStartIndicesToSplit;
    }

    public static boolean checkPipelineStartIndicesToSplit ( final RegionExecutionPlan regionExecutionPlan,
                                                             final List<Integer> pipelineStartIndicesToSplit )
    {
        if ( pipelineStartIndicesToSplit.size() < 2 )
        {
            return false;
        }

        final List<Integer> pipelineStartIndices = regionExecutionPlan.getPipelineStartIndices();

        int start = pipelineStartIndices.indexOf( pipelineStartIndicesToSplit.get( 0 ) );
        if ( start < 0 )
        {
            return false;
        }

        final int limit = ( start < pipelineStartIndices.size() - 1 )
                          ? pipelineStartIndices.get( start + 1 )
                          : regionExecutionPlan.getRegionDef().getOperatorCount();

        for ( int i = 1; i < pipelineStartIndicesToSplit.size(); i++ )
        {
            final int index = pipelineStartIndicesToSplit.get( i );
            if ( index <= pipelineStartIndicesToSplit.get( i - 1 ) || index >= limit )
            {
                return false;
            }
        }

        return true;
    }

}
