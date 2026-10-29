"""Compare native-mod submission diagnostics without treating gauges or nested CPU scopes as additive frame time."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def summarize(folder):
    rows=json.loads((folder/'results.json').read_text(encoding='utf-8'))
    scene=json.loads((folder/'scene.json').read_text(encoding='utf-8'))
    frames=sum(row['frames'] for row in rows)
    counters={key:sum(row['nativeDiagnostics'].get(key,0) for row in rows) for key in rows[0]['nativeDiagnostics']}
    visible=[]
    for trace in folder.glob('timeline-*.csv'):
        if not trace.stem.removeprefix('timeline-').isdigit():
            continue
        with trace.open(encoding='utf-8',newline='') as stream:
            visible.extend(int(row['visibleSections']) for row in csv.DictReader(stream))
    result=dict(case=folder.name,frames=frames,fps=[row['fps'] for row in rows],medianFps=statistics.median(row['fps'] for row in rows),
                allocatedBytesPerFrame=sum(row['renderThreadAllocatedBytes'] for row in rows)/frames,
                renderThreadCpuMsPerFrame=sum(row['renderThreadCpuNs'] for row in rows)/frames/1e6,
                adapterAndBridgeMsPerFrame=(counters['adapterInclusiveNs']+counters.get('regionBridgeInclusiveNs',0))/frames/1e6,
                visibleSectionsRange=[min(visible),max(visible)] if visible else None,
                countsPerFrame={key:counters.get(key,0)/frames for key in ['draws','terrainDraws','entityDraws','regionBridgeBatches','regionBridgeSubDraws','regionBridgeIndices','dynamicMergedDraws','dynamicGpuDraws','chlorideLimitCalls','chlorideLimitCandidates','chlorideLimitSelected','chlorideCullingCalls','extrasCullingCalls','overlappingEntityCullingChecks','visibilityGraphCalls','regionUploadResultsUnits','frameSplits']},
                msPerFrame={key:value/frames/1e6 for key,value in counters.items() if key.endswith('Ns')},
                totalCounters=counters,scene=scene)
    for row in rows:
        assert row['forbiddenGlCalls']==0 and row['openGlContexts']==0
    features=scene.get('nativeModFeatures',dict(regionBridge=not scene['legacyModSubmission'],chlorideTopK=not scene['legacyModSubmission']))
    if features['regionBridge']:
        assert counters.get('regionBridgeBatches',0)>0,'Region entry was not exercised'
    if features['chlorideTopK'] and scene['entityLimitOverride']>=0:
        assert counters.get('chlorideLimitCalls',0)>0,'Top-K entry was not exercised'
    if scene['blockUpdates']:
        assert counters.get('visibilityGraphCalls',0)>0 and counters.get('regionUploadResultsUnits',0)>0,'Update pressure was not exercised'
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    args=parser.parse_args()
    results=[summarize(args.root/name) for name in ['legacy-normal','native-normal','native-stress','legacy-stress']]
    output=dict(cases=results,notes=['Two intervals within each independent JVM, not randomized paired blocks; clocks were not locked.',
                                  'Detailed profiling is enabled. Logical draws are preserved; dynamicGpuDraws records actual commands for eligible dynamic shaders.',
                                  'visibleSections is a gauge. Callback overlap does not prove that both culling features are enabled.',
                                  'Chloride Top-K CPU counters cover the replacement only; zero on the legacy path is not zero sorting cost.'])
    (args.root/'comparison.json').write_text(json.dumps(output,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    for row in results:
        print(row['case'],f"FPS={row['medianFps']:.2f}",f"allocation={row['allocatedBytesPerFrame']:.0f}",f"adapter+bridge_ms={row['adapterAndBridgeMsPerFrame']:.4f}",row['visibleSectionsRange'])
