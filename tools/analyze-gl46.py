"""Summarize GL46 same-process ablations; inclusive CPU scopes and GPU timeline spans are kept separate."""
import argparse
import json
from pathlib import Path
import statistics


def summarize(folder):
    rows=json.loads((folder/'results.json').read_text(encoding='utf-8'))
    scene=json.loads((folder/'scene.json').read_text(encoding='utf-8'))
    cases=[]
    for name in dict.fromkeys(r.get('gl46Case','BASELINE') for r in rows):
        samples=[r for r in rows if r.get('gl46Case','BASELINE')==name]
        frames=sum(r['frames'] for r in samples)
        keys=set().union(*(r['gl46Diagnostics'].keys() for r in samples))
        sums={k:sum(r['gl46Diagnostics'].get(k,0) for r in samples) for k in keys}
        cpu_frames=sums.get('entityCpuFrames',0)
        def per(key,denominator):
            return sums.get(key,0)/denominator if denominator else None
        result={'case':name,'intervals':len(samples),'fps':[r['fps'] for r in samples],
                'medianFps':statistics.median(r['fps'] for r in samples),
                'medianFrameP99Ms':statistics.median(r['frameP99Ms'] for r in samples),
                'renderCpuMsPerFrame':sum(r['renderThreadCpuNs'] for r in samples)/frames/1e6,
                'allocatedBytesPerFrame':sum(r['renderThreadAllocatedBytes'] for r in samples)/frames,
                'perFrame':{k:per(k,frames) for k in ['hizBuilds','hizDispatches','hizBarriers','entityCaptured','entityMultiDraws','entitySplits','entityRejected','entityRecordUploads','entityRecordReuses','entityVertexBytes','terrainIndirectCalls','terrainUploadBytes','terrainMeshes','worldPublishedMeshes']},
                'cullPerSample':{k:per(k,sums.get('cullSamples',0)) for k in ['cullTested','cullFrustum','cullOccluded','cullLayerDraws']},
                'entityCpuSampleFrames':cpu_frames,
                'terrainCpuUsPerSampledFrame':{k:per(k,sums.get('terrainCpuFrames',0)*1000) for k in ['terrainOpaqueNs','terrainTranslucentNs','hizCpuNs']},
                'entityCpuUsPerSampledFrame':{k:per(k,cpu_frames*1000) for k in ['entityStateNs','entityCopyNs','entityUniformNs','entityOrderNs','entityCommandNs','entityGlSubmitNs','entityRingFenceNs','entityCaptureNs','entityFlushNs']},
                'gpuTimelineMeanUs':{k:per(k+'TotalNs',sums.get(k+'Samples',0)*1000) for k in ['gpuCull','gpuIndirect','gpuHiz']}}
        cases.append(result)
        print(name,f"FPS={result['medianFps']:.2f}",f"CPU_ms={result['renderCpuMsPerFrame']:.4f}",f"P99_ms={result['medianFrameP99Ms']:.3f}",result['perFrame'],result['cullPerSample'])
    (folder/'summary.json').write_text(json.dumps({'scene':scene,'cases':cases,'notes':[
        'Disabled timing is null, never a measured zero cost.',
        'Capture includes its state/copy/uniform subscopes and fallback flushes; flush includes order/commands/GL/fences. Do not add inclusive scopes.',
        'GPU timestamps are asynchronous timeline spans, not pure shader time. LastFrame fields refer to the producing terrain frame, not the polling frame.',
        'FPS is measured separately from GPU/CPU detailed profiling; compare cases within the same configuration.'
    ]},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folders',type=Path,nargs='+')
    for folder in parser.parse_args().folders:
        summarize(folder)
