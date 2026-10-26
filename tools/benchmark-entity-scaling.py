"""Five independent capture/replay diagnostics; all GPU work is offscreen, with guarded paired A/B replay."""
import argparse
import csv
import json
from pathlib import Path
import random
import re
import statistics as stats
import subprocess
import time
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]

def summarize(out,runs):
    result=[]
    for kind in ['capture','replay']:
        for count in [0,50,100,200,500]:
            variants=[('CAPTURE','CAPTURE')] if kind=='capture' else [(u,m) for u in ['SHARED','UNIQUE'] for m in ['LEGACY','STREAM']]
            for uniform,mode in variants:
                rows=[r for run in runs if run['kind']==kind for r in run['rows'] if int(r['count'])==count and (kind=='capture' or (r['uniforms']==uniform and r['mode']==mode))]
                if not rows:continue
                row=dict(kind=kind,count=count,uniforms=uniform,mode=mode,rounds=len(rows))
                for key in rows[0]:
                    if key not in ['count','uniforms','mode','crc32']:row[key]=stats.median(float(r[key]) for r in rows)
                if kind=='replay':
                    means=[]
                    for run in runs:
                        if run['kind']!='replay':continue
                        text=(out/f'round-{run["round"]:02d}'/'replay/stdout.txt').read_text()
                        match=re.search(r'ENTITY_SCALE_GPU uniforms='+str(uniform=='UNIQUE').lower()+r' count='+str(count)+r' mode='+mode+r' GPU_ENTITY_MS samples=\d+ mean=([\d.]+)',text)
                        if not match:raise AssertionError('Missing GPU metric')
                        means.append(float(match.group(1)))
                    row['gpu_mean_ms']=stats.median(means)
                result.append(row)
    (out/'summary.json').write_text(json.dumps(result,indent=2))
    lines=['# 实体路径离屏分项测试','','RTX 3050 Laptop；合成 Draw 数 0/50/100/200/500，每 Draw 96 个四边形、384 个 NEW_ENTITY 顶点。对象计数是绘制包数，不是完整游戏实体数。',
           '每种测试五个独立 JVM；先以 500 Draw 全局预热 300 帧，再对每个数量预热 100 帧、采集 200 帧，数量顺序打乱。回放的两种写入方式在同一设备内逐帧成对随机顺序交错。',
           'LEGACY：先分配合并后的 byte[] 再复制到映射缓冲；STREAM：原数组分段直接写入相同映射缓冲。相同 Uniform 允许合并，不同 Uniform 保留逐 Draw 提交；未增加重排。',
           '精确 JVM / 堆参数见每轮 command.json；GC 原始日志见 gc.log。下表为各轮分位数的中位数，非所有帧合并的分位数。','']
    if any(r['kind']=='capture' for r in result):
        lines+=['| Draw | 捕获 P50 ms | 捕获 P99 ms | 复制均值 ms | 状态查询均值 ms | 捕获分配 B/帧 |','| --- | ---: | ---: | ---: | ---: | ---: |']
        for r in result:
            if r['kind']=='capture':lines.append(f'| {r["count"]} | {r["capture_p50_ms"]:.3f} | {r["capture_p99_ms"]:.3f} | {r["copy_mean_ms"]:.3f} | {r["state_mean_ms"]:.3f} | {r["allocated_bytes_per_frame"]:.0f} |')
        lines+=['','捕获使用真实 ShaderInstance 和 EntityDraws.capture；排除模型构建、游戏逻辑、纹理镜像、GPU 回放和屏幕呈现。反射调用开销包含在捕获计时内。复制与状态查询是捕获的子项，不能相加后再与捕获相加。','']
    if any(r['kind']=='replay' for r in result):
        lines+=['| Uniform | Draw | 方式 | 录制 P50 ms | 录制 P99 ms | 分配 B/次 | GPU 均值 ms |','| --- | --- | --- | ---: | ---: | ---: | ---: |']
        for r in result:
            if r['kind']=='replay':lines.append(f'| {r["uniforms"]} | {r["count"]} | {r["mode"]} | {r["record_p50_ms"]:.3f} | {r["record_p99_ms"]:.3f} | {r["allocated_bytes_per_record"]:.0f} | {r["gpu_mean_ms"]:.3f} |')
        lines+=['','回放使用 EntityPass，预先生成输入包；CPU 包含合批、缓冲写入和 Vulkan 命令录制，排除帧 fence 等待、捕获和初始化。GPU 使用独立 timestamp，按既有 fence 完成后读取。最终图像 CRC32 在每种数量和 Uniform 模式下都与另一写入方式一致。','']
    lines+=['所有非空稳态回放的 pipeline_creations=0、buffer_growths=0，扩容与管线编译在全局预热完成。原始 CSV 的累计上传字节、复制字节和 Draw 数包含该数量自身的 100 帧预热。',
            '这是诊断与分项 A/B，不是完整游戏 FPS；未将捕获和回放两个独立测试相加来推算游戏 FPS。不能把合成 Draw 数直接换算为牛、箱子或模组机器数量。',
            '首次未全局预热的数据保存在 entity-scaling/，包含明显 JIT 阶段变化，不作为稳态性能依据。512 MiB 堆的 entity-scaling-steady/ 出现 G1 Humongous Allocation；1 GiB 最大堆与 2 MiB G1 区域的 entity-scaling-large-region/ 用于复核，具体 GC 行为不能外推到用户游戏。']
    (out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--java',required=True);parser.add_argument('--rounds',type=int,default=5);parser.add_argument('--output',default='build/reports/entity-scaling');parser.add_argument('--heap',default='512m');parser.add_argument('--region');parser.add_argument('--only',choices=['all','capture','replay'],default='all');args=parser.parse_args()
    out=ROOT/args.output;out.mkdir(exist_ok=True,parents=True);guard=Guard();runs=[]
    with(out/'telemetry.csv').open('w') as telemetry:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=telemetry,stderr=subprocess.STDOUT)
        try:
            rng=random.Random(20261027)
            for round_number in range(1,args.rounds+1):
                kinds=['capture','replay'] if args.only=='all' else [args.only];rng.shuffle(kinds)
                for kind in kinds:
                    for attempt in range(5):
                        guard.idle();folder=out/f'round-{round_number:02d}'/kind;folder.mkdir(exist_ok=True,parents=True)
                        runtime=ROOT/('build/reports/offscreen-performance/gl.args' if kind=='capture' else 'vulkan-lab/build/trace/bench.args')
                        main_class='github.com.gengyoubo.vulkanforge.'+('client.EntityCaptureBenchmark' if kind=='capture' else 'lab.EntityScalingBenchmark')
                        gc=(folder/'gc.log').relative_to(ROOT).as_posix()
                        cmd=[args.java,'@'+str(runtime),'-Xms256m','-Xmx'+args.heap,'-Xlog:gc:file='+gc+':time,uptime,level,tags','-Dvulkanforge.entityProfile=true','-Dbench.quads=96','-Dbench.prewarm=300','-Dbench.warmup=100','-Dbench.samples=200','-Dbench.seed='+str(20261027+round_number),'-Dbench.output='+str(folder)]
                        if args.region:cmd+=['-XX:G1HeapRegionSize='+args.region]
                        cmd+=[main_class]
                        (folder/'command.json').write_text(json.dumps(cmd,indent=2));print(f'RUNNING round={round_number} {kind}',flush=True)
                        with(folder/'stdout.txt').open('w') as log:
                            child=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid;start=time.time()*1000;child.wait(timeout=180);end=time.time()*1000
                        text=(folder/'stdout.txt').read_text(errors='replace')
                        if child.returncode:raise RuntimeError(kind+': '+text[-2500:])
                        if guard.collisions(start,end):
                            folder.rename(folder.with_name(kind+'-discarded-'+str(int(end))));continue
                        rows=list(csv.DictReader((folder/'results.csv').open()))
                        assert len(rows)==(5 if kind=='capture' else 20)
                        if kind=='replay':
                            for row in rows:
                                pair=next(r for r in rows if r['uniforms']==row['uniforms'] and r['count']==row['count'] and r['mode']!=row['mode'])
                                assert pair['crc32']==row['crc32']
                            assert 'ENTITY_SCALING=OK' in text
                        else:assert 'ENTITY_CAPTURE=OK' in text and 'ENTITY_GEOMETRY_CONVERSIONS=OK' in text
                        runs.append(dict(round=round_number,kind=kind,start_ms=start,end_ms=end,rows=rows));(out/'runs.json').write_text(json.dumps(runs,indent=2))
                        print(f'PASS round={round_number} {kind}',flush=True);break
                    else:raise RuntimeError('Repeated concurrent benchmark interference')
        finally:
            monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)

if __name__=='__main__':main()
