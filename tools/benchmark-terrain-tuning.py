"""Original source baseline plus isolated telemetry/restoration/residency switches, with GPU correctness audits."""
import argparse
import csv
import datetime as dt
import json
from pathlib import Path
import random
import re
import statistics as stats
import subprocess
import time
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
CONFIGS={
 'ORIGINAL':None,
 'SAFE_EVERY_FRAME':(1,False,False),
 'COUNTERS_60':(60,False,False),
 'SKIP_EMPTY':(1,True,False),
 'DIFF_STATE':(1,False,True),
 'DEFAULT_60_SKIP':(60,True,False),
 'ALL_THREE':(60,True,True),
}


def compile_reference(java, revision):
    out=ROOT/'build/terrain-opt-baseline';source=out/'src/github/com/gengyoubo/vulkanforge/client';source.mkdir(parents=True,exist_ok=True)
    files=[]
    for name in ['Gl46Terrain.java','Gl46State.java','GlStateCache.java','GlCompat.java']:
        text=subprocess.check_output(['git','show',revision+':src/main/java/github/com/gengyoubo/vulkanforge/client/'+name],cwd=ROOT)
        if b'counterInterval' in text:raise AssertionError('Reference already includes the proposed tuning')
        path=source/name;path.write_bytes(text);files.append(path)
    runtime=(ROOT/'build/reports/offscreen-performance/gl.args').read_text()
    cp=re.search(r'-cp "([^"]+)"',runtime).group(1);classes=out/'classes';classes.mkdir(exist_ok=True)
    args=['-encoding UTF-8','-implicit:none','-classpath "'+cp+'"','-d "'+classes.as_posix()+'"']+['"'+p.as_posix()+'"' for p in files]
    (out/'javac.args').write_text('\n'.join(args))
    subprocess.run([str(Path(java).with_name('javac.exe')),'@'+str(out/'javac.args')],cwd=ROOT,check=True,capture_output=True)
    (out/'revision.txt').write_text(revision)
    (out/'gl.args').write_text(runtime.replace('-cp "','-cp "'+classes.as_posix()+';',1))
    return out/'gl.args'


def summarize(out,records):
    samples=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:samples.append(dict(time=dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,gpu=float(row[2]),memory=float(row[3]),temperature=float(row[4]),clock=float(row[5])))
        except(ValueError,IndexError):pass
    summary=[]
    for name in CONFIGS:
        rows=[r for r in records if r['case']==name]
        points=[s for s in samples if any(r['START_MS']+1000<=s['time']<=r['END_MS'] for r in rows)]
        delta=[]
        for r in rows:
            base=next(b for b in records if b['case']=='ORIGINAL' and b['block']==r['block'])
            delta.append((r['FPS']/base['FPS']-1)*100)
        summary.append(dict(case=name,n=len(rows),fps=stats.median(r['FPS'] for r in rows),fps_min=min(r['FPS'] for r in rows),fps_max=max(r['FPS'] for r in rows),gpu_ms=stats.median(r['GPU_MS'] for r in rows),gpu_percent=stats.mean(s['gpu'] for s in points) if points else None,paired_change_vs_original=stats.median(delta)))
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    lines=['# 计数采样、状态差异恢复与空层跳过 A/B','','1920×1080，RTX 3050 Laptop，固定相机；每项五个独立 JVM 轮次，预热 2000 帧，每轮至少 6 秒，按 block 打乱顺序。',
           '同一可见场景：346 个不透明 Section / 98446 个四边形，131 个透明 Section / 18058 个四边形；单 FBO；三层中只有 Solid 有网格。',
           '全部配置先让 Minecraft GL 缓存与真实状态一致，再在 TRUSTED 模式下进入渲染，模拟实际游戏的缓存快路径。',
           'ORIGINAL 是改动前源码的独立编译覆盖层，revision 见 baseline-revision.txt；其余是新实现的显式开关。',
           'SAFE_EVERY_FRAME：每帧尝试采样，零等待 fence 轮询，忙槽丢弃；用于隔离新的异步完成校验开销，不冒充原版性能。',
           'DIFF_STATE：每通道入口读取真实 GL 状态、检测缓存失效，通道内部跟踪自己的写入；异常/未知回调保守全量恢复。',
           '跳过空层只依据 CPU 网格驻留计数，仍然让 GPU 决定各非空层可见数量；所有配置的实际绘制清单必须完全一致。','',
           '| 配置 | FPS 中位数 | 五轮范围 | GPU ms/帧 | GPU 占用 | 相对同 block 原源码变化中位数 |','| --- | ---: | ---: | ---: | ---: | ---: |']
    for s in summary:
        gpu='n/a' if s['gpu_percent'] is None else f'{s["gpu_percent"]:.1f}%'
        lines.append(f'| {s["case"]} | {s["fps"]:.0f} | {s["fps_min"]:.0f}–{s["fps_max"]:.0f} | {s["gpu_ms"]:.3f} | {gpu} | {s["paired_change_vs_original"]:+.1f}% |')
    lines+=['','COUNTERS_60 / SKIP_EMPTY / DIFF_STATE 各自只改变 SAFE_EVERY_FRAME 的一个开关。DEFAULT_60_SKIP 合并低频计数和空层跳过，ALL_THREE 再启用差异恢复。',
            'SUBMISSION_STATS = {frames,counterCopies,collectedSamples,busyDrops,indirectCalls,emptyLayerSkips}；STATE_RESTORE_STATS = {fullScopes,diffScopes,nativeRestores,avoidedRestores}。',
            '统计采样没有 CPU fence 等待；忙槽位丢弃统计样本，绝不让统计阻塞绘制。GL4.3 也等 copy fence 完成后才刷新 CPU 镜像。',
            'cullStats[0] 是实际采样数，平均值按采样数计算；降低频率只让统计更新变慢，不改变地形、实体、Hi-Z 或 GPU 可见计数。',
            '空层跳过的收益与场景有关；三层均有网格时不会跳过任何层。',
            '差异模式额外支付真实边界查询成本，避免任意第三方原生 GL 调用造成缓存失配；不能只按减少的 glSet 数量推算收益。',
            'GPU 占用来自 nvidia-smi 整卡采样。测量不包含编译、加载、预热和诊断读回；结果不是完整游戏 FPS。']
    (out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(summary,indent=2),flush=True)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--java',required=True);parser.add_argument('--revision',default='89e19184da4671d9bf92719986a22d9cb0ce4914');parser.add_argument('--rounds',type=int,default=5);parser.add_argument('--seconds',type=float,default=6);parser.add_argument('--smoke',action='store_true');args=parser.parse_args()
    reference=compile_reference(args.java,args.revision)
    out=ROOT/('build/reports/terrain-tuning-smoke' if args.smoke else 'build/reports/terrain-tuning');out.mkdir(parents=True,exist_ok=True)
    (out/'baseline-revision.txt').write_text(args.revision)
    source=ROOT/'build/reports/offscreen-performance/matched-scene.bin';expected=source.with_name('expected-workload.txt').read_text();(out/'expected-workload.txt').write_text(expected)
    rng=random.Random(20261027);schedule=[]
    for block in range(1,(1 if args.smoke else args.rounds)+1):
        order=list(CONFIGS);rng.shuffle(order);schedule.append(dict(block=block,order=order))
    (out/'schedule.json').write_text(json.dumps(schedule,indent=2));records=[];guard=Guard()
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            time.sleep(2)
            for block in schedule:
                for name in block['order']:
                    while True:
                        guard.idle();folder=out/f'block-{block["block"]:02d}'/name;folder.mkdir(parents=True,exist_ok=True)
                        runtime=reference if name=='ORIGINAL' else ROOT/'build/reports/offscreen-performance/gl.args'
                        cmd=[args.java,'@'+str(runtime),'-Dbench.width=1920','-Dbench.height=1080','-Dbench.frames=1','-Dbench.warmup='+('100' if args.smoke else '2000'),'-Dbench.seconds='+('.2' if args.smoke else str(args.seconds)),'-Dbench.runs=1','-Dbench.audit=true','-Dbench.trustedState=true','-Dbench.output='+str(folder)]
                        if CONFIGS[name] is not None:
                            interval,skip,diff=CONFIGS[name];cmd+=['-Dvulkanforge.gl46.counterInterval='+str(interval),'-Dvulkanforge.gl46.skipEmptyLayers='+str(skip).lower(),'-Dvulkanforge.gl46.diffState='+str(diff).lower()]
                        cmd+=['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark','4.6',str(source)]
                        (folder/'command.json').write_text(json.dumps(cmd,indent=2));print(f'RUNNING block={block["block"]} case={name}',flush=True)
                        with (folder/'stdout.txt').open('w') as f:
                            child=subprocess.Popen(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT);guard.own=child.pid;child.wait(timeout=180);guard.own=None
                        text=(folder/'stdout.txt').read_text(errors='replace')
                        if child.returncode:raise RuntimeError(name+': '+text[-3500:])
                        if(folder/'workload.txt').read_text()!=expected:raise AssertionError(name+': actual draw manifest mismatch')
                        detail=(folder/'benchmark.txt').read_text()
                        if'BENCH_STATE_CACHE_TRUSTED=true' not in detail:raise AssertionError('Cache fast path not established')
                        lines=[x for x in text.splitlines() if x.startswith('BENCH_RESULT ')]
                        if len(lines)!=1:raise AssertionError('Missing result')
                        row=dict(re.findall(r'(\w+)=([^\s]+)',lines[0]));row.update(case=name,block=block['block'])
                        for key in ['width','height','frames','START_MS','END_MS']:row[key]=int(row[key])
                        for key in ['seconds','FPS','GPU_MS']:row[key]=float(row[key])
                        for key in ['SUBMISSION_STATS','STATE_RESTORE_STATS']:
                            match=re.search(key+r'=(\[[^\n]+\]|LEGACY)',detail);row[key]=json.loads(match.group(1)) if match and match.group(1)!='LEGACY' else None
                        collision=guard.collisions(row['START_MS'],row['END_MS'])
                        if collision:(folder/'collision.json').write_text(json.dumps(collision));folder.rename(folder.with_name(name+'-discarded-'+str(int(time.time()*1000))));continue
                        records.append(row);(out/'runs.json').write_text(json.dumps(records,indent=2));print(f'PASS {name} FPS={row["FPS"]:.1f} GPU_MS={row["GPU_MS"]:.4f}',flush=True);break
        finally:
            monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    if not args.smoke:summarize(out,records)


if __name__=='__main__':main()
