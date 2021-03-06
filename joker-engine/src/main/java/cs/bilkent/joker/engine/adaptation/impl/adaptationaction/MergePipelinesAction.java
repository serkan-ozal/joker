package cs.bilkent.joker.engine.adaptation.impl.adaptationaction;

import java.util.ArrayList;
import java.util.List;

import cs.bilkent.joker.engine.adaptation.AdaptationAction;
import cs.bilkent.joker.engine.adaptation.AdaptationPerformer;
import cs.bilkent.joker.engine.flow.PipelineId;
import cs.bilkent.joker.engine.flow.RegionExecutionPlan;
import static cs.bilkent.joker.engine.region.impl.RegionExecutionPlanUtil.getMergeablePipelineStartIndices;
import static cs.bilkent.joker.impl.com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toList;

public class MergePipelinesAction implements AdaptationAction
{

    private final RegionExecutionPlan currentRegionExecutionPlan, newRegionExecutionPlan;

    private final List<PipelineId> pipelineIds = new ArrayList<>();

    public MergePipelinesAction ( final RegionExecutionPlan regionExecutionPlan, final List<PipelineId> pipelineIds )
    {
        checkArgument( regionExecutionPlan != null );
        checkArgument( pipelineIds != null && pipelineIds.size() > 0 );
        this.currentRegionExecutionPlan = regionExecutionPlan;
        final List<Integer> startIndicesToMerge = getMergeablePipelineStartIndices( regionExecutionPlan, pipelineIds );
        this.newRegionExecutionPlan = regionExecutionPlan.withMergedPipelines( startIndicesToMerge );
        int pipelineStartIndex = -1;
        for ( PipelineId pipelineId : pipelineIds )
        {
            checkArgument( regionExecutionPlan.getRegionId() == pipelineId.getRegionId() );
            checkArgument( pipelineId.getPipelineStartIndex() > pipelineStartIndex );
            pipelineStartIndex = pipelineId.getPipelineStartIndex();
        }

        this.pipelineIds.addAll( pipelineIds );
    }

    @Override
    public void apply ( final AdaptationPerformer performer )
    {
        performer.mergePipelines( pipelineIds );
    }

    @Override
    public RegionExecutionPlan getCurrentRegionExecutionPlan ()
    {
        return currentRegionExecutionPlan;
    }

    @Override
    public RegionExecutionPlan getNewRegionExecutionPlan ()
    {
        return newRegionExecutionPlan;
    }

    @Override
    public AdaptationAction revert ()
    {
        final List<Integer> pipelineOperatorIndices = getSplitIndices();
        return new SplitPipelineAction( newRegionExecutionPlan, pipelineIds.get( 0 ), pipelineOperatorIndices );
    }

    private List<Integer> getSplitIndices ()
    {
        // op0, op1, op2, op3, op4, op5
        // 0  , 1  , 2  , 3  , 4  , 5
        // merge: (2, 4, 5)
        // split: (2, 3)
        final int base = pipelineIds.get( 0 ).getPipelineStartIndex();

        return pipelineIds.stream().map( p -> ( p.getPipelineStartIndex() - base ) ).filter( i -> i > 0 ).collect( toList() );
    }

    @Override
    public String toString ()
    {
        return "MergePipelineAction{" + "currentRegionExecutionPlan=" + currentRegionExecutionPlan + ", newRegionExecutionPlan="
               + newRegionExecutionPlan + ", pipelineIds=" + pipelineIds + '}';
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

        final MergePipelinesAction action = (MergePipelinesAction) o;

        if ( !currentRegionExecutionPlan.equals( action.currentRegionExecutionPlan ) )
        {
            return false;
        }
        return pipelineIds.equals( action.pipelineIds );
    }

    @Override
    public int hashCode ()
    {
        int result = currentRegionExecutionPlan.hashCode();
        result = 31 * result + pipelineIds.hashCode();
        return result;
    }

}
