"""Verified three-level GL/Vulkan comparison. No surface/presentation; hashes, GPU counters and pixels gate every result."""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path
import random
import re
import statistics as stats
import subprocess
import time
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
APIS=['GL4.3','GL4.4','GL4.5','GL4.6','VK1.2','VK1.3','VK1.4']
LEVELS=['BASELINE','GPU_DRIVEN','FULL_PIPELINE']

def properties(path):
    return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line and not ' ' in line.split('=',1)[0])

def pixels(folder):
    if (folder/'color.rgba').exists():
        return np.frombuffer((folder/'color.rgba').read_bytes(),dtype=np.uint8),np.frombuffer((folder/'depth.f32').read_bytes(),dtype='<f4')
    with np.load(folder/'pixels.npz') as saved:return saved['color'],saved['depth']

def validate(folder,reference,api):
    env=properties(folder/'environment.txt');ref_env=properties(reference/'environment.txt')
    for key in ['DEVICE_UUID','SCENE_SHA256','CAPTURE_SOURCE']:assert env[key]==ref_env[key],(key,api)
    checks=[]
    for level in LEVELS:
        case=folder/level;ref=reference/level;a=properties(case/'audit.txt');b=properties(ref/'audit.txt');prefix='VK' if api.startswith('VK') else 'GL'
        for key in ['SCENE_SHA256','DRAW_IDS_SHA256','CAPTURED_DRAWS','GL_FALLBACK_DRAWS','DUPLICATED_DRAWS','INDIRECT_COMMAND_SLOTS','DEPTH_COPIES','POST_PASSES','RESOURCE_GRAPH_BOUNDARIES']:assert a[key]==b[key],(api,level,key,a[key],b[key])
        assert int(a['DUPLICATED_DRAWS'])==0
        for suffix in ['SUBMITTED_DRAWS','CULLED_DRAWS','DRAWN_VERTICES']:assert a[prefix+'_'+suffix]==b['GL_'+suffix],(api,level,suffix)
        assert int(a['CAPTURED_DRAWS'])==int(a[prefix+'_SUBMITTED_DRAWS'])+int(a[prefix+'_CULLED_DRAWS'])+int(a['GL_FALLBACK_DRAWS'])
        assert (case/'draw-ids.txt').read_bytes()==(ref/'draw-ids.txt').read_bytes()
        color,z=pixels(case);ref_color,ref_z=pixels(ref);delta=np.abs(color.astype(np.int16)-ref_color.astype(np.int16));depth_delta=np.abs(z-ref_z)
        assert color.shape==ref_color.shape and z.shape==ref_z.shape and np.isfinite(z).all()
        max_color=int(delta.max());max_depth=float(depth_delta.max())
        assert max_color<=1 and max_depth<=2e-6,(api,level,'pixel mismatch',max_color,max_depth)
        check=dict(level=level,color_max_error=max_color,color_mean_error=float(delta.mean()),depth_max_error=max_depth,color_sha256=hashlib.sha256(color.tobytes()).hexdigest(),depth_sha256=hashlib.sha256(z.tobytes()).hexdigest(),draw_ids_sha256=a['DRAW_IDS_SHA256'],captured=int(a['CAPTURED_DRAWS']),submitted=int(a[prefix+'_SUBMITTED_DRAWS']),culled=int(a[prefix+'_CULLED_DRAWS']),fallback=int(a['GL_FALLBACK_DRAWS']),duplicates=0,vertices=int(a[prefix+'_DRAWN_VERTICES']),vs_invocations=int(a[prefix+'_VERTEX_SHADER_INVOCATIONS']))
        checks.append(check)
        np.savez_compressed(case/'pixels.npz',color=color,depth=z)
        # Only these two exact files created by this invocation are removed; PNG and compressed raw pixels remain.
        for name in ['color.rgba','depth.f32']:
            path=case/name
            if path.exists():path.unlink()
    base_color,base_depth=pixels(folder/'BASELINE');driven_color,driven_depth=pixels(folder/'GPU_DRIVEN')
    assert np.max(np.abs(base_color.astype(np.int16)-driven_color.astype(np.int16)))<=1,'Culling changed visible color'
    assert np.max(np.abs(base_depth-driven_depth))<=2e-6,'Culling changed visible depth'
    (folder/'conformance.json').write_text(json.dumps(checks,indent=2));return checks

def run(java,api,folder,guard,args,preflight,seed):
    for attempt in range(5):
        guard.idle();folder.mkdir(exist_ok=True,parents=True)
        cmd=[java,'@'+str(ROOT/'vulkan-lab/build/trace/bench.args'),'-Xms512m','-Xmx1g','-Dbench.api='+api,'-Dbench.width='+str(args.width),'-Dbench.height='+str(args.height),'-Dbench.draws='+str(args.draws),'-Dbench.warmup='+str(32 if preflight else args.warmup),'-Dbench.frames='+str(4 if preflight else 128),'-Dbench.seconds='+str(0 if preflight else args.seconds),'-Dbench.seed='+str(seed),'-Dbench.output='+str(folder),'github.com.gengyoubo.vulkanforge.lab.UnifiedRenderingBenchmark']
        (folder/'command.json').write_text(json.dumps(cmd,indent=2))
        print(('PREFLIGHT' if preflight else 'MEASURING')+' '+str(folder.relative_to(ROOT)),flush=True)
        with(folder/'stdout.txt').open('w') as log:
            child=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid;child.wait(timeout=240)
        text=(folder/'stdout.txt').read_text(errors='replace')
        if child.returncode:raise RuntimeError(api+': '+text[-5000:])
        assert 'UNIFIED_BENCHMARK=OK' in text and 'LEDGER_SELF_TEST=OK' in text
        results=[]
        for line in text.splitlines():
            if line.startswith('UNIFIED_RESULT '):
                row=dict(re.findall(r'(\w+)=([^\s]+)',line))
                for key in row:
                    if key not in ['API','LEVEL']:row[key]=float(row[key])
                results.append(row)
        assert {r['LEVEL'] for r in results}==set(LEVELS)
        if not preflight and any(guard.collisions(r['START_MS'],r['END_MS']) for r in results):
            old=folder.resolve();discarded=folder.with_name(folder.name+'-discarded-'+str(int(time.time()*1000))).resolve()
            assert old.is_relative_to(ROOT) and discarded.is_relative_to(ROOT)
            old.rename(discarded);continue
        return results
    raise RuntimeError('Repeated concurrent Java GPU benchmark interference')

def summarize(out,runs):
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(row[2])))
        except(ValueError,IndexError):pass
    summaries=[]
    for level in LEVELS:
        for api in APIS:
            cases=[r for r in runs if r['API']==api and r['LEVEL']==level]
            if not cases:continue
            gpu=[value for stamp,value in telemetry if any(r['START_MS']+500<=stamp<=r['END_MS'] for r in cases)]
            delta=[]
            for case in cases:
                ref=next(r for r in runs if r['round']==case['round'] and r['API']=='GL4.6' and r['LEVEL']==level)
                delta.append((case['FPS']/ref['FPS']-1)*100)
            summaries.append(dict(api=api,level=level,rounds=len(cases),fps=stats.median(r['FPS'] for r in cases),fps_min=min(r['FPS'] for r in cases),fps_max=max(r['FPS'] for r in cases),gpu_entity_ms=stats.median(r['GPU_ENTITY_MS'] for r in cases),gpu_timeline_ms=stats.median(r['GPU_TIMELINE_MS'] for r in cases),cpu_p99_ms=stats.median(r['CPU_P99_MS'] for r in cases),gpu_utilization=stats.mean(gpu) if gpu else None,paired_fps_change_vs_gl46=stats.median(delta)))
    (out/'summary.json').write_text(json.dumps(summaries,indent=2))
    lines=['# 统一 GL / Vulkan 三层基准','','受控合成 Draw 回放；同一 GPU UUID、几何、Shader 语义、RGBA8 / D32F、分辨率、两帧槽、实例记录和输入 ID。不是 Minecraft 或 Mekanism 实景 FPS。',
           '所有 API 先经过同分辨率预检：完整颜色/深度图、GPU 输入装配顶点查询、GPU 输出的绘制 ID、剔除 ID 和兼容 ID 必须一致。输入 ID 守恒且无重复；审计还通过故意注入重复/漏画的自检。',
           '每 API 五个独立 JVM 轮次；API 与层次顺序打乱；每层预热后计时，最终排空两帧槽。精确参数见各轮 command.json。颜色/深度读回、图像编码、审计及资源初始化不计入时间。',
           'Baseline：同一 CPU 逐 Draw 命令，无额外 GPU 剔除。GPU Driven：同一 AABB 六面剔除、固定槽位五字段 indexed indirect 命令，不使用 Hi-Z、Indirect Count 或 Mesh Shader。',
           'Full Pipeline：同一 GL 清屏生产 → 主后端剔除/不透明 → 深度复制 → 透明 → GL 深度复制/后处理/兼容绘制。Vulkan 使用真实外部共享图像/semaphore，GL 在同 API 内完成相同语义边界。跨 API 交接数是架构差异，GL 不额外插入虚构交接。',
           'GPU_TIMELINE_MS：GPU timestamp 时间跨度；Full 的两端均为 GL 时间戳，涵盖跨 API 等待与可能的 GPU 空闲。GPU_ENTITY_MS 仅主后端不透明和透明通道时间戳区间；不等于纯 GPU 活跃时间或整卡利用率。GPU 占用为 nvidia-smi 整卡均值。','']
    for level in LEVELS:
        lines += ['## '+level,'','| API | FPS 中位数 | 五轮范围 | 实体 GPU ms | GPU 时间线 ms | CPU P99 ms | 整卡 GPU | 同轮相对 GL4.6 |','| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
        for r in summaries:
            if r['level']==level:lines.append(f'| {r["api"]} | {r["fps"]:.0f} | {r["fps_min"]:.0f}–{r["fps_max"]:.0f} | {r["gpu_entity_ms"]:.3f} | {r["gpu_timeline_ms"]:.3f} | {r["cpu_p99_ms"]:.3f} | '+(f'{r["gpu_utilization"]:.1f}%' if r['gpu_utilization'] is not None else 'n/a')+f' | {r["paired_fps_change_vs_gl46"]:+.1f}% |')
        lines+=['']
    slow=[dict(round=r['round'],api=r['API'],level=r['LEVEL'],seconds=r['seconds'],fps=r['FPS'],cpu_p99_ms=r['CPU_P99_MS']) for r in runs if r['seconds']>4.5]
    (out/'long-intervals.json').write_text(json.dumps(slow,indent=2))
    lines+=['## 计时波动','',
            '五轮范围显示较大波动，不能据此可靠排列相邻 GL / Vulkan 小版本。所有慢样本保留，未按 FPS 好坏剔除。',
            '超过 4.5 秒的实际计时区间列在 long-intervals.json（目标为至少 3 秒）。总时间包含最后排空；本批日志没有逐帧最大值/末尾排空的分项，不能把长区间直接归因于某个 API 或资源交接。',
            '工作量一致性检查与计时稳定性是两件事：当前证明了前者，FPS 和 CPU P99 应结合范围阅读。','']
    lines += ['审计位置：preflight/ 和 round-*/API/LEVEL/audit.txt、draw-ids.txt、conformance.json。color.png 为可视预览，pixels.npz 保留完整原始颜色和深度。',
              'CAPTURED_DRAWS 在本实验表示合成标准化输入包数，来源明确标记 SYNTHETIC_CANONICAL_TAPE；没有将其冒充实际游戏捕获量。SUBMITTED_DRAWS 是非零逻辑 Draw 数，API 调用数与 indirect command slot 数另记。',
              'DRAWN_VERTICES 来自硬件 pipeline statistics 的输入装配计数，按索引引用计数；另列真实 vertex shader invocation 数，不能把唯一网格顶点数、索引引用数与 shader 调用数混用。',
              'VALIDATION 以原始日志为准；常规计时关闭校验。当前结果不能证明 Mek 实景不存在多余回退、模型构建或纹理镜像开销。']
    (out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--java',required=True);parser.add_argument('--rounds',type=int,default=5);parser.add_argument('--seconds',type=float,default=3);parser.add_argument('--warmup',type=int,default=1000);parser.add_argument('--width',type=int,default=1920);parser.add_argument('--height',type=int,default=1080);parser.add_argument('--draws',type=int,default=1024);parser.add_argument('--preflight-only',action='store_true');parser.add_argument('--output',default='build/reports/unified-rendering');args=parser.parse_args()
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(exist_ok=True,parents=True);guard=Guard();runs=[];rng=random.Random(20261027)
    with(out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            reference=out/'preflight/GL4.6'
            for api in ['GL4.6']+[a for a in APIS if a!='GL4.6']:
                folder=out/'preflight'/api;run(args.java,api,folder,guard,args,True,20261027);validate(folder,reference,api);print('PREFLIGHT PASS '+api,flush=True)
            (out/'preflight-pass.txt').write_text('PASS all 7 APIs, all 3 levels: matching IDs, counts, GPU IA vertices, complete RGBA8 and D32F\n')
            if args.preflight_only:return
            schedule=[]
            for round_number in range(1,args.rounds+1):
                apis=APIS.copy();rng.shuffle(apis);schedule.append(dict(round=round_number,apis=apis));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for api in apis:
                    folder=out/f'round-{round_number:02d}'/api;rows=run(args.java,api,folder,guard,args,False,20261027+round_number);checks=validate(folder,reference,api)
                    for row in rows:row.update(round=round_number,conformance=next(c for c in checks if c['level']==row['LEVEL']))
                    runs.extend(rows);(out/'runs.json').write_text(json.dumps(runs,indent=2));print('PASS round='+str(round_number)+' '+api,flush=True)
        finally:
            monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)

if __name__=='__main__':main()
