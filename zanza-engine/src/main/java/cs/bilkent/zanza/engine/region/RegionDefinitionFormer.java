package cs.bilkent.zanza.engine.region;

import java.util.List;

import cs.bilkent.zanza.flow.FlowDefinition;

public interface RegionDefinitionFormer
{

    List<RegionDefinition> createRegions ( FlowDefinition flowDefinition );

}