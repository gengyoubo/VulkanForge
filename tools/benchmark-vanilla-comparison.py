"""Minecraft's actual ShaderInstance/VertexBuffer chunk path vs the custom GL renderer, hidden FBO only."""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import statistics as stats
import subprocess
import time
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--java',required=True)
    parser.add_argument('--rounds',type=int,default=5)
    parser.add_argument('--seconds',type=float,default=6)
    args=parser.parse_args()
    out=ROOT/'build/reports/vanilla-comparison';out.mkdir(parents=True,exist_ok=True)
    source=ROOT/'build/reports/offscreen-performance/matched-scene.bin'
    expected=source.with_name('expected-workload.txt').read_text()
    (out/'expected-workload.txt').write_text(expected)
    (out/'scene-sha256.txt').write_text(hashlib.sha256(source.read_bytes()).hexdigest())
    guard=Guard();records=[]
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr,power.draw','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            time.sleep(2)
            for block in range(1,args.rounds+1):
                order=['VANILLA_GL','CUSTOM_GL46'] if block%2 else ['CUSTOM_GL46','VANILLA_GL']
                for case in order:
                    while True:
                        guard.idle()
                        folder=out/f'block-{block:02d}'/case;folder.mkdir(parents=True,exist_ok=True)
                        command=[args.java,'@'+str(ROOT/'build/reports/offscreen-performance/gl.args'),'-Dbench.width=1920','-Dbench.height=1080','-Dbench.warmup=2000','-Dbench.frames=1','-Dbench.runs=1','-Dbench.audit=true',f'-Dbench.seconds={args.seconds}','-Dbench.output='+str(folder)]
                        if block==1: command.append('-Dbench.capture=true')
                        if case=='VANILLA_GL': command+=['github.com.gengyoubo.vulkanforge.client.VanillaGlOffscreenBenchmark',str(source)]
                        else: command+=['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark','4.6',str(source)]
                        # Both cases are a single RGBA8/D32 FBO, back-face culling on, identical visible geometry.
                        (folder/'command.json').write_text(json.dumps(command,indent=2))
                        print(f'RUNNING block={block} case={case}',flush=True)
                        with (folder/'stdout.txt').open('w') as result:
                            child=subprocess.Popen(command,cwd=ROOT,stdout=result,stderr=subprocess.STDOUT);guard.own=child.pid
                            child.wait(timeout=150);guard.own=None
                        text=(folder/'stdout.txt').read_text(errors='replace')
                        if child.returncode: raise RuntimeError(text[-3500:])
                        if (folder/'workload.txt').read_text()!=expected: raise AssertionError('Draw manifest mismatch: '+case)
                        if case=='VANILLA_GL':
                            if 'CUSTOM_TERRAIN=false' not in (folder/'benchmark.txt').read_text(): raise AssertionError('Unexpected native path')
                        lines=[line for line in text.splitlines() if line.startswith('BENCH_RESULT ')]
                        if len(lines)!=1: raise AssertionError('Missing result')
                        row=dict(re.findall(r'(\w+)=([^\s]+)',lines[0]));row.update(case=case,block=block)
                        for key in ['width','height','frames','START_MS','END_MS']: row[key]=int(row[key])
                        for key in ['seconds','FPS','GPU_MS']: row[key]=float(row[key])
                        collision=guard.collisions(row['START_MS'],row['END_MS'])
                        if collision:
                            (folder/'collision.json').write_text(json.dumps(collision));folder.rename(folder.with_name(case+'-discarded-'+str(int(time.time()*1000))));continue
                        records.append(row);(out/'runs.json').write_text(json.dumps(records,indent=2))
                        print(f'PASS {case} block={block} FPS={row["FPS"]:.1f} GPU_MS={row["GPU_MS"]:.4f}',flush=True)
                        break
        finally:
            monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try: telemetry.append(dict(time=dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,gpu=float(row[2]),memory=float(row[3]),temperature=float(row[4]),clock=float(row[5]),power=float(row[6])))
        except (ValueError,IndexError): pass
    summary=[]
    for case in ['VANILLA_GL','CUSTOM_GL46']:
        rows=[r for r in records if r['case']==case]
        samples=[s for s in telemetry if any(r['START_MS']+1000<=s['time']<=r['END_MS'] for r in rows)]
        if len(rows)!=args.rounds or not samples: raise AssertionError('Incomplete measurements/telemetry')
        summary.append(dict(case=case,fps=stats.median(r['FPS'] for r in rows),fps_min=min(r['FPS'] for r in rows),fps_max=max(r['FPS'] for r in rows),gpu_ms=stats.median(r['GPU_MS'] for r in rows),gpu_percent=stats.mean(s['gpu'] for s in samples),vram_mib_peak=max(s['memory'] for s in samples)))
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    # Raw captures are excluded from timing and are used only to verify the two paths rendered the same image.
    native=(out/'block-01/VANILLA_GL/preview.rgba').read_bytes();custom=(out/'block-01/CUSTOM_GL46/preview.rgba').read_bytes()
    if len(native)!=len(custom) or len(native)!=1920*1080*4: raise AssertionError('Wrong capture size')
    error=sum(abs(a-b) for a,b in zip(native,custom))/len(native)
    rgb_error=sum(abs(native[i+c]-custom[i+c]) for i in range(0,len(native),4) for c in range(3))/(1920*1080*3)
    differing=sum(any(abs(native[i+c]-custom[i+c])>8 for c in range(3)) for i in range(0,len(native),4))/(1920*1080)
    (out/'image-comparison.json').write_text(json.dumps(dict(mean_abs_rgba=error,mean_abs_rgb=rgb_error,pixels_differing_gt8_fraction=differing),indent=2))
    if error>8 or differing>.08: raise AssertionError('Native/custom image discrepancy exceeds tolerance')
    lines=['# Minecraft 原版 GL 地形离屏基线','','1920×1080，RTX 3050 Laptop；原版与自定义 GL 4.6 交替各五轮，每轮预热 2000 帧、测量至少 6 秒。',
           '相同相机、纹理、光照、346 个不透明 Section / 98446 个四边形，131 个透明 Section / 18058 个四边形。',
           '两端均使用单 RGBA8/D32 FBO、背面剔除、LEQUAL 深度测试及相同 RGB 透明混合；Alpha 因子保留各自实现。无 Hi-Z、实体、HUD、窗口呈现。',
           '原版路径直接使用 Minecraft 1.20.1 的 ShaderInstance、rendertype_solid/translucent GLSL、BufferBuilder、VertexBuffer，按 LevelRenderer.renderChunkLayer 的每层 apply / 每 Section ChunkOffset.upload + bind/draw 执行。',
           '原版资源的 moj_import 在初始化阶段展开，避免独立测试运行时触发 Forge 注册表生命周期；着色器逻辑未改写。','',
           '| 路径 | FPS 中位数 | 五轮范围 | GPU 计时 ms/帧 | GPU 平均占用 | 整卡显存峰值 MiB |','| --- | ---: | ---: | ---: | ---: | ---: |']
    for s in summary: lines.append(f'| {s["case"]} | {s["fps"]:.0f} | {s["fps_min"]:.0f}–{s["fps_max"]:.0f} | {s["gpu_ms"]:.3f} | {s["gpu_percent"]:.1f}% | {s["vram_mib_peak"]:.0f} |')
    lines+=['',f'原版与自定义捕获图像：RGB 平均绝对差 {rgb_error:.4f}，RGBA 平均绝对差 {error:.4f}；RGB 任一通道差 >8 的像素占比 {differing:.6%}。Alpha 混合配置存在差别，RGBA 差值单独列出。',
            'GPU 占用是驱动每 200 ms 的整卡采样，排除测量最初 1 秒；GPU 计时包含提交间隙，不能直接推算硬件占用。',
            '这是原版地形绘制路径的离屏基准，没有运行完整 Minecraft 世界/主循环，不能当作实际游玩 FPS。',
            '与上一轮 MATCH 双目标/深度复制/Vulkan 交接组结构不同，比较自定义 GL 时应采用本报告同轮单目标结果。']
    (out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(summary,indent=2),flush=True);print('IMAGE meanAbs='+str(error)+' differing='+str(differing),flush=True)


if __name__=='__main__': main()
