"""Explicit native-only intervals: production hybrid vs merged composite/post leases, paired references."""
import argparse,csv,datetime as dt,hashlib,importlib.util,json,random,statistics as stats,subprocess
from pathlib import Path
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('three_modes',ROOT/'tools/benchmark-three-modes.py')
three=importlib.util.module_from_spec(spec);spec.loader.exec_module(three)
common=three.common
CASES=['GL46_NATIVE','GLVK_HYBRID','GLVK_INTERVAL','VK_NATIVE_OFFSCREEN']
PHASES=three.PHASES

def exact(folder,reference):
    for name in ['scene','final','after-final']:
        a,z=common.pixels(folder,name);b,w=common.pixels(reference,name)
        assert np.array_equal(a,b) and np.array_equal(z,w),('Vulkan interval changed output',folder,name)

def summarize(out,runs):
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(row[2])))
        except(ValueError,IndexError):pass
    summary=[];lines=['# GL-VK 跨 Pass 资源租用区间实验','',
        '1920×1080，RGBA8 / D32F，两帧槽，无窗口呈现；同一 Draw ID、几何、光照、相机、透明顺序和后处理语义。两条 GL-VK 路径保留完全相同的实际 Shader、深度复制与合成工作。每轮随机顺序，独立 JVM，计时包含最后排空。',
        'GLVK_HYBRID 是当前生产混合路径；GLVK_INTERVAL 是显式连续 Vulkan 区间，将两个地形合成与后处理在同一命令缓冲内执行，目标只在区间入口/出口交接。地形仍为独立提交。未省略 GL 背景清除、合成、深度或后处理。',
        '区间调用者必须保证没有未声明的 GL 图像访问；任何 GL 读写、回退、帧结束和销毁前先排空。当前游戏没有默认开启跨阶段区间：Forge/模组可能在两次地形合成之间绘制 GL 内容，不能直接套用本实验的连续区间。',
        '本实验同时减少交接和提交，不能将全部收益归因于单独某一次 semaphore 调用。GL/混合 GPU ms 为 GL 同一时钟首尾跨度（包括交接等待）；原生 VK 使用自己的 GPU 时间戳，不跨时钟相减。整卡 GPU 占用来自 nvidia-smi，不是单 Pass 活跃率。',
        'GL46 / Native 直接绘入相同目标，没有混合架构保留的两次全屏合成和一次深度复制。它们是同内容/同输出语义的架构参考；Native 与混合路径的差值不能全部算成交接开销，本实验没有执行只增加最终颜色交接的 D 对照。','']
    for phase in PHASES:
        if not any(r['phase']==phase for r in runs):continue
        lines+=['## '+phase,'','| 执行器 | FPS 中位数 | 轮间范围 | GPU 时间线 ms | CPU P99 ms | 整卡 GPU |','|---|---:|---:|---:|---:|---:|']
        for case in CASES:
            rows=[r for r in runs if r['case']==case and r['phase']==phase]
            if not rows:continue
            util=[u for t,u in telemetry if any(r['START_MS']+2000<=t<=r['END_MS']-300 for r in rows)]
            item=dict(case=case,phase=phase,rounds=len(rows),fps=stats.median(r['FPS'] for r in rows),fps_min=min(r['FPS'] for r in rows),fps_max=max(r['FPS'] for r in rows),gpu_ms=stats.median(r['GPU_MS'] for r in rows),cpu_p99_ms=stats.median(r['CPU_P99_MS'] for r in rows),cpu_max_ms=max(r['CPU_MAX_MS'] for r in rows),gpu_utilization=stats.mean(util) if util else None)
            summary.append(item);usage=f'{item["gpu_utilization"]:.1f}%' if util else 'n/a'
            lines.append(f'| {case} | {item["fps"]:.0f} | {item["fps_min"]:.0f}–{item["fps_max"]:.0f} | {item["gpu_ms"]:.3f} | {item["cpu_p99_ms"]:.3f} | {usage} |')
        deltas=[]
        for before in [r for r in runs if r['phase']==phase and r['case']=='GLVK_HYBRID']:
            after=next(r for r in runs if r['phase']==phase and r['case']=='GLVK_INTERVAL' and r['round']==before['round'])
            deltas.append((after['FPS']/before['FPS']-1)*100)
        lines+=['',f'同轮合并区间 FPS 变化中位数 {stats.median(deltas):+.1f}%，范围 {min(deltas):+.1f}%～{max(deltas):+.1f}%。','',
            '| 混合路径 | VK 提交/帧 | GL semaphore 组调用/帧 | 组内图像引用/帧 | CPU QueueSubmit µs | CPU VkFenceWait µs | CPU glWait µs | CPU glSignal µs | CPU glFlush µs |',
            '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
        for case in ['GLVK_HYBRID','GLVK_INTERVAL']:
            audits=[common.props(out/f'round-{r["round"]:02d}'/phase/case/'pipeline-audit.txt') for r in runs if r['phase']==phase and r['case']==case]
            v=lambda i:stats.median(float(a[f'COUNTER_{i}_PER_FRAME']) for a in audits)
            lines.append(f'| {case} | {v(4):.0f} | {v(5):.0f} | {v(8):.0f} | {v(9)/1000:.2f} | {v(10)/1000:.2f} | {v(15)/1000:.2f} | {v(16)/1000:.2f} | {v(17)/1000:.2f} |')
        lines.append('')
    lines+=['GL-VK 两条路径所有完整 RGBA 与 D32F 输出逐位一致；计时前后检查，并校验实际地形 Draw ID。计时内不读回图像。原生 VK 每帧硬件输入装配顶点数 699024，严格类加载器确认没有 OpenGL/GLFW/GlInterop 加载。',
        'COUNTER_0..17：深度复制、避免复制、post batch、批中 post、queue submit、GL 组调用、含合成 batch、批中合成、组内图像引用、CPU queue submit ns、CPU fence wait ns、GL 组 CPU ns、glWait/glSignal/glFlush 次数及各自 CPU ns。图像引用是逐次组参数计数，不代表不同图像数量；本实现每方向都发出对应 ownership barrier。',
        'CPU API 调用耗时与 GPU 等待跨度分开报告；没有声称 CPU glWait 时间就是 GPU 等待。保留全部慢样本、CPU 原始帧、温度/频率采样和源码/class 归档。这是受控离屏地形与后处理吞吐，不是完整游戏 FPS。验证层在本机不可用，不能声称通过 Vulkan validation。']
    (out/'summary.json').write_text(json.dumps(summary,indent=2));(out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--seconds',type=float,default=6);p.add_argument('--warmup',type=int,default=1000);p.add_argument('--preflight-only',action='store_true');p.add_argument('--hybrid-only',action='store_true');p.add_argument('--post-only',action='store_true');p.add_argument('--output',default='build/reports/native-lease-interval');args=p.parse_args()
    selected_phases=['TERRAIN_POST3'] if args.post_only else list(PHASES)
    selected_cases=['GLVK_HYBRID','GLVK_INTERVAL'] if args.hybrid_only else CASES
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(parents=True,exist_ok=True)
    if (out/'runs.json').exists():raise RuntimeError('Choose a fresh output directory')
    common.archive(out)
    manifest=json.loads((out/'kernel-manifest.json').read_text())
    for source in [Path(__file__).resolve(),ROOT/'tools/benchmark-three-modes.py']:
        relative=source.relative_to(ROOT);target=out/'runtime-snapshot'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes());manifest['sha256'][str(relative)]=hashlib.sha256(source.read_bytes()).hexdigest()
    (out/'kernel-manifest.json').write_text(json.dumps(manifest,indent=2))
    expected=(ROOT/'build/reports/offscreen-performance/expected-workload.txt').read_text();(out/'expected-workload.txt').write_text(expected)
    (out/'scene-sha256.txt').write_text(hashlib.sha256((ROOT/'build/reports/offscreen-performance/matched-scene.bin').read_bytes()).hexdigest())
    guard=Guard();runs=[];rng=random.Random(20261029)
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            for phase in selected_phases:
                reference=out/'preflight'/phase/'GL46_NATIVE'
                for case in CASES:
                    folder=out/'preflight'/phase/case;three.run(args.java,case,phase,folder,guard,args,True);three.validate(folder,reference,expected,case,phase)
                    if case=='GLVK_INTERVAL':exact(folder,out/'preflight'/phase/'GLVK_HYBRID')
                    if case=='VK_NATIVE_OFFSCREEN':three.compare_vulkan(folder,out/'preflight'/phase/'GLVK_HYBRID')
                    print('PREFLIGHT PASS '+phase+' '+case,flush=True)
            (out/'preflight-pass.txt').write_text('PASS actual Draw IDs, full color/depth, hybrid bit-exact, measured semaphore/submit/image counts\n')
            if args.preflight_only:return
            schedule=[]
            for n in range(1,args.rounds+1):
                jobs=[(phase,case) for phase in selected_phases for case in selected_cases];rng.shuffle(jobs);schedule.append(dict(round=n,jobs=jobs));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for phase,case in jobs:
                    folder=out/f'round-{n:02d}'/phase/case;row=three.run(args.java,case,phase,folder,guard,args);three.validate(folder,out/'preflight'/phase/'GL46_NATIVE',expected,case,phase)
                    if case.startswith('GLVK'):exact(folder,out/'preflight'/phase/'GLVK_HYBRID')
                    if case=='VK_NATIVE_OFFSCREEN':three.compare_vulkan(folder,out/'preflight'/phase/'GLVK_HYBRID')
                    row['round']=n;runs.append(row);(out/'runs.json').write_text(json.dumps(runs,indent=2));print('PASS '+str(n)+' '+phase+' '+case+' FPS='+str(row['FPS']),flush=True)
        finally:monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)
if __name__=='__main__':main()
