"""Summarize independent HUD ablations or same-JVM GUI path comparisons; never add nested HUD scopes."""
import argparse
import json
from pathlib import Path
import statistics


def summarize(folder):
    rows=json.loads((folder/'results.json').read_text(encoding='utf-8'))
    scene=json.loads((folder/'scene.json').read_text(encoding='utf-8'))
    scope_profiling=scene.get('hudScopeProfiling',any(key.startswith('hud_') for row in rows for key in row['nativeDiagnostics']))
    result=[]
    for name in dict.fromkeys(row['hudCase'] for row in rows):
        samples=[row for row in rows if row['hudCase']==name]
        frames=sum(row['frames'] for row in samples)
        keys=set().union(*(row['nativeDiagnostics'] for row in samples))
        counters={key:sum(row['nativeDiagnostics'].get(key,0) for row in samples) for key in keys}
        value=dict(case=name,intervals=len(samples),fps=[row['fps'] for row in samples],medianFps=statistics.median(row['fps'] for row in samples),
                   renderCpuMsPerFrame=sum(row['renderThreadCpuNs'] for row in samples)/frames/1e6,
                   allocatedBytesPerFrame=sum(row['renderThreadAllocatedBytes'] for row in samples)/frames,
                   countersPerFrame={key:counters.get(key,0)/frames for key in ['draws','terrainDraws','entityDraws','guiFastQuads','guiFastFallbacks','guiMergedDraws','guiGpuDraws','hotbarCacheHits','hotbarCacheMisses','hotbarCacheBuilds','hotbarCacheFallbacks','hotbarCacheReplayedDraws']},
                   scopes={scope:dict(callsPerFrame=counters.get('hud_'+scope+'Calls',0)/frames,skippedPerFrame=counters.get('hud_'+scope+'Skipped',0)/frames,
                                     msPerFrame=counters.get('hud_'+scope+'Ns',0)/frames/1e6,packetsPerFrame=counters.get('hud_'+scope+'Packets',0)/frames)
                           for scope in ['hands','hotbar','vanillaHealth','hud','forgeHealth','forgeFood','forgeArmor','forgeAir']} if scope_profiling else None)
        result.append(value)
    for row in rows:
        assert row['forbiddenGlCalls']==0 and row['openGlContexts']==0
        d=row['nativeDiagnostics']
        if scope_profiling:
            if row['hudCase']=='NO_HANDS': assert d['hud_handsSkipped']>0
            if row['hudCase']=='NO_HOTBAR': assert d['hud_hotbarSkipped']>0
            if row['hudCase']=='NO_HEALTH': assert d['hud_forgeHealthSkipped']>0 and d['hud_forgeFoodSkipped']>0
            if row['hudCase']=='NO_HUD': assert d['hud_hudSkipped']>0 and d['hud_hotbarCalls']==0
        if row['hudCase']=='FAST_GUI': assert d.get('guiFastQuads',0)>0,'Fast GUI path was not exercised'
        if row['hudCase']=='ITEM_CACHE': assert d.get('hotbarCacheHits',0)>0,'Item cache was not exercised'
        if row['hudCase']=='NO_ITEM_CACHE': assert d.get('hotbarCacheHits',0)==0,'Uncached case used the item cache'
        if row['hudCase']=='LEGACY_GUI': assert d.get('guiFastQuads',0)==0,'Legacy GUI case used the fast path'
    output=dict(scene=scene,cases=result,notes=['HUD scope includes hotbar and health scopes; do not sum those inclusive timings.',
                                              'Packet counts are frontend submissions; guiGpuDraws counts GPU commands for marked fast sprites.',
                                              'The ablation screenshots intentionally omit graphics and are not correctness evidence for an optimization.'])
    (folder/'summary.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for row in result:
        print(row['case'],f"FPS={row['medianFps']:.2f}",f"CPU_ms={row['renderCpuMsPerFrame']:.4f}",f"allocated_B={row['allocatedBytesPerFrame']:.0f}",row['countersPerFrame'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folders',nargs='+',type=Path)
    args=parser.parse_args()
    for folder in args.folders:
        summarize(folder)
