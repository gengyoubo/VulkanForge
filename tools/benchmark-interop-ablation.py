"""Offscreen A/B/C/D interop experiments; preserve every timing and validate before accepting it."""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path
import random
import re
import shutil
import statistics as stats
import subprocess
import time
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
APIS=['GL4.3','GL4.4','GL4.5','GL4.6','VK1.2','VK1.3','VK1.4']
VK_CASES=['A_SHARED_IDLE','A_VK_IDLE','A_HANDOFF_GENERAL','A_HANDOFF_ATTACHMENTS','C_DEPENDENCIES_ONLY','C_VK_COPY','C_GL_COPY','C_BOTH_COPY','D_ROUNDTRIPS_2','D_ROUNDTRIPS_4','B_FULL_ON','B_FULL_OFF','D_ONE_WAY_GL_VK','D_ONE_WAY_VK_GL']
GL_CASES=['A_GL_IDLE','C_GL_NO_COPY','C_GL_COPY','B_FULL_ON','B_FULL_OFF']

def props(path):
    return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)

def validate(folder,reference,api):
    env=props(folder/'environment.txt');ref_env=props(reference/'environment.txt')
    for key in ['DEVICE_UUID','WIDTH','HEIGHT','COLOR','DEPTH','SLOTS','PRESENT_CALLS']:
        assert env[key]==ref_env[key],(api,key)
    checks=[]
    for name in VK_CASES if api.startswith('VK') else GL_CASES:
        case=folder/name
        assert (case/'conformance.txt').read_text().startswith('PASS'),(api,name)
        if name.startswith('B_'):
            a=props(case/'audit.txt');b=props(reference/name/'audit.txt')
            for key in ['SCENE_SHA256','DRAW_IDS_SHA256','CAPTURED_DRAWS','SUBMITTED_DRAWS','CULLED_DRAWS','GL_FALLBACK_DRAWS','INTENTIONALLY_EXCLUDED_DRAWS','DUPLICATED_DRAWS','DRAWN_VERTICES','FALLBACK_VERTICES','DEPTH_COPIES','POST_PASSES']:
                assert a[key]==b[key],(api,name,key,a[key],b[key])
            assert int(a['DUPLICATED_DRAWS'])==0
            assert int(a['CAPTURED_DRAWS'])==sum(int(a[k]) for k in ['SUBMITTED_DRAWS','CULLED_DRAWS','GL_FALLBACK_DRAWS','INTENTIONALLY_EXCLUDED_DRAWS'])
            raw=case/'color.rgba';raw_depth=case/'depth.f32'
            if raw.exists():color=np.frombuffer(raw.read_bytes(),np.uint8);depth=np.frombuffer(raw_depth.read_bytes(),'<f4')
            else:
                with np.load(case/'pixels.npz') as data:color=data['color'];depth=data['depth']
            ref=reference/name
            if (ref/'color.rgba').exists():ref_color=np.frombuffer((ref/'color.rgba').read_bytes(),np.uint8);ref_depth=np.frombuffer((ref/'depth.f32').read_bytes(),'<f4')
            else:
                with np.load(ref/'pixels.npz') as data:ref_color=data['color'];ref_depth=data['depth']
            assert np.isfinite(depth).all()
            color_error=int(np.abs(color.astype(np.int16)-ref_color.astype(np.int16)).max());depth_error=float(np.abs(depth-ref_depth).max())
            assert color_error<=1 and depth_error<=2e-6,(api,name,color_error,depth_error)
            checks.append(dict(case=name,color_max_error=color_error,depth_max_error=depth_error,audit=a))
            np.savez_compressed(case/'pixels.npz',color=color,depth=depth)
            for path in [raw,raw_depth]:
                if path.exists():path.unlink()
        elif not name.startswith('D_ONE_WAY'):
            a=props(case/'audit.txt');assert a['ZERO_DRAW_VERTICES']=='0' and a['DEPTH_PATTERN']=='PASS' and a['COLOR_PATTERN']=='PASS'
            checks.append(dict(case=name,audit=a))
        else:checks.append(dict(case=name,direction_completion=True,reset_outside_sample=True))
    on=props(folder/'B_FULL_ON/audit.txt');off=props(folder/'B_FULL_OFF/audit.txt')
    assert on['SUBMITTED_DRAWS']==off['SUBMITTED_DRAWS'] and on['CULLED_DRAWS']==off['CULLED_DRAWS']
    assert int(on['GL_FALLBACK_DRAWS'])==16 and off['GL_FALLBACK_DRAWS']=='0' and off['INTENTIONALLY_EXCLUDED_DRAWS']=='16'
    (folder/'conformance.json').write_text(json.dumps(checks,indent=2));return checks

def run(java,api,folder,guard,args,preflight,seed):
    for attempt in range(5):
        guard.idle();folder.mkdir(parents=True,exist_ok=True)
        command=[java,'@'+str(ROOT/'vulkan-lab/build/trace/bench.args'),'-Xms512m','-Xmx1g','-Dbench.api='+api,'-Dbench.width='+str(args.width),'-Dbench.height='+str(args.height),'-Dbench.warmup='+str(4 if preflight else args.warmup),'-Dbench.frames='+str(4 if preflight else 128),'-Dbench.seconds='+str(0 if preflight else args.seconds),'-Dbench.seed='+str(seed),'-Dbench.output='+str(folder),'github.com.gengyoubo.vulkanforge.lab.InteropAblationBenchmark']
        (folder/'command.json').write_text(json.dumps(command,indent=2));print(('PREFLIGHT ' if preflight else 'MEASURE ')+str(folder.relative_to(ROOT)),flush=True)
        with (folder/'stdout.txt').open('w') as log:
            child=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid
            try:child.wait(timeout=300)
            except BaseException:
                child.terminate();child.wait(timeout=10);raise
        text=(folder/'stdout.txt').read_text(errors='replace')
        if child.returncode:raise RuntimeError(api+': '+text[-6000:])
        assert 'INTEROP_ABLATION=OK' in text and 'LEDGER_SELF_TEST=OK' in text
        rows=[]
        for line in text.splitlines():
            if line.startswith('ABLATION_RESULT '):
                row=dict(re.findall(r'(\w+)=([^\s]+)',line))
                for key in row:
                    if key not in ['API','CASE','MODE']:row[key]=float(row[key])
                rows.append(row)
        assert {r['CASE'] for r in rows}==set(VK_CASES if api.startswith('VK') else GL_CASES)
        if not preflight and any(guard.collisions(r['START_MS'],r['END_MS']) for r in rows):
            old=folder.resolve();discarded=folder.with_name(folder.name+'-concurrent-'+str(int(time.time()*1000))).resolve()
            assert old.is_relative_to(ROOT) and discarded.is_relative_to(ROOT);old.rename(discarded);continue
        return rows
    raise RuntimeError('Other GPU benchmark repeatedly overlapped; no measurements accepted')

def archive(out):
    destination=out/'runtime-kernels';destination.mkdir(exist_ok=True)
    sources=list((ROOT/'vulkan-lab/src/test/java/github/com/gengyoubo/vulkanforge/lab').glob('*Benchmark.java'))
    sources=[p for p in sources if p.name in ['InteropMicroBenchmark.java','InteropAblationBenchmark.java','UnifiedRenderingBenchmark.java']]
    sources+=list((ROOT/'vulkan-lab/src/test/java/github/com/gengyoubo/vulkanforge/lab').glob('Unified*.java'))
    sources+=list((ROOT/'vulkan-lab/src/test/resources/shaders').glob('unified*'))+[Path(__file__),ROOT/'tools/gpu_benchmark_guard.py']
    sources+=list((ROOT/'vulkan-lab/src/main/java/github/com/gengyoubo/vulkanforge/lab').glob('Gpu*.java'))
    sources+=[ROOT/'vulkan-lab/src/main/java/github/com/gengyoubo/vulkanforge/lab'/name for name in ['VulkanContext.java','VulkanCommands.java','SharedPresentImage.java']]
    sources+=[ROOT/'gl-interop/src/main/java/github/com/gengyoubo/vulkanforge/interop/GlInterop.java']
    files=list((ROOT/'vulkan-lab/build/classes/java/test/github/com/gengyoubo/vulkanforge/lab').glob('Interop*.class'))+list((ROOT/'vulkan-lab/build/classes/java/test/github/com/gengyoubo/vulkanforge/lab').glob('Unified*.class'))
    for stem in ['VulkanContext','SharedPresentImage','VulkanCommands','GpuTimers','GlInterop']:
        files+=list((ROOT/'vulkan-lab/build/classes/java/main').rglob(stem+'*.class'))
    files+=list((ROOT/'vulkan-lab/build/resources/test/shaders').glob('unified*'))
    manifest={'git_base':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sources':{},'runtime':{}}
    for p in set(sources):manifest['sources'][str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
    for p in files:
        relative=p.relative_to(ROOT);target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target);manifest['runtime'][str(relative)]=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'kernel-manifest.json').write_text(json.dumps(manifest,indent=2))

def summarize(out,runs):
    telemetry=[]
    for line in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(line[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(line[2]),float(line[4]),float(line[5])))
        except(ValueError,IndexError):pass
    summaries=[]
    for api in APIS:
        for name in VK_CASES if api.startswith('VK') else GL_CASES:
            cases=[r for r in runs if r['API']==api and r['CASE']==name]
            if not cases:continue
            row=dict(api=api,case=name,rounds=len(cases),mode=cases[0]['MODE'])
            for key in cases[0]:
                if key not in ['API','CASE','MODE','round','START_MS','END_MS']:
                    row[key]=stats.median(r[key] for r in cases);row[key+'_min']=min(r[key] for r in cases);row[key+'_max']=max(r[key] for r in cases)
            gpu=[(util,temp,clock) for stamp,util,temp,clock in telemetry if any(r['START_MS']+300<=stamp<=r['END_MS'] for r in cases)]
            row['gpu_utilization']=stats.mean(r[0] for r in gpu) if gpu else None
            row['gpu_clock_mhz']=stats.mean(r[2] for r in gpu) if gpu else None
            summaries.append(row)
    deltas=[]
    pairs=[('B_FULL_ON','B_FULL_OFF'),('A_SHARED_IDLE','A_HANDOFF_ATTACHMENTS'),('A_HANDOFF_GENERAL','A_HANDOFF_ATTACHMENTS'),('A_HANDOFF_ATTACHMENTS','C_DEPENDENCIES_ONLY'),('C_DEPENDENCIES_ONLY','C_VK_COPY'),('C_DEPENDENCIES_ONLY','C_GL_COPY'),('C_DEPENDENCIES_ONLY','C_BOTH_COPY'),('C_GL_NO_COPY','C_GL_COPY'),('A_HANDOFF_ATTACHMENTS','D_ROUNDTRIPS_2'),('D_ROUNDTRIPS_2','D_ROUNDTRIPS_4')]
    for api in APIS:
        for before,after in pairs:
            changes=[]
            for r in runs:
                if r['API']!=api or r['CASE']!=before:continue
                other=next((b for b in runs if b['API']==api and b['CASE']==after and b['round']==r['round']),None)
                if other:changes.append(dict(round=r['round'],gpu_timeline_ms=other['GPU_TIMELINE_MS']-r['GPU_TIMELINE_MS'],wall_ms=1000/other['FPS']-1000/r['FPS'],fps_percent=(other['FPS']/r['FPS']-1)*100))
            if changes:deltas.append(dict(api=api,before=before,after=after,paired=changes,median_gpu_delta_ms=stats.median(c['gpu_timeline_ms'] for c in changes),median_wall_delta_ms=stats.median(c['wall_ms'] for c in changes),median_fps_percent=stats.median(c['fps_percent'] for c in changes)))
    (out/'summary.json').write_text(json.dumps(summaries,indent=2));(out/'paired-deltas.json').write_text(json.dumps(deltas,indent=2))
    lines=['# 共享图像与 Semaphore：A / B / C / D 实验','',
           '1920×1080，RGBA8 / D32F，两帧槽，隐藏 GL context，无 surface / swapchain / present。受控合成工作量，不是游戏 FPS。每 API 五个独立 JVM，API 与实验顺序打乱；B 开/关相邻且反转顺序。所有慢样本保留。',
           '主表为五轮中位数；范围、逐帧 CPU、最大 CPU 帧、最终排空、原始计数和 GPU 整卡采样均保留。GPU 时间线可能包含排队/等待/空闲，不能等同于 GPU 活跃耗时。局部 VK 时间戳不覆盖其前置外部 semaphore 等待；不跨 GL/VK 时钟相减。',
           '一次单向交接是 GL→VK 或 VK→GL；一次往返为两次单向交接。连续闭环不能每帧只完成一次单向交接又回到原所有者；因此另外同步测两个单向完成延迟，反向复位在样本外，不能将其倒数当作 FPS。','']
    def table(title,predicate):
        lines.extend(['## '+title,'','| API / 实验 | FPS | 五轮范围 | GPU 时间线 ms | CPU P99 ms | 最大 CPU 帧 ms | 排空 ms | 整卡 GPU |','|---|---:|---:|---:|---:|---:|---:|---:|'])
        for r in summaries:
            if not predicate(r) or r['mode']!='PIPELINED':continue
            gpu=f'{r["gpu_utilization"]:.1f}%' if r['gpu_utilization'] is not None else 'n/a'
            lines.append(f'| {r["api"]} / {r["case"]} | {r["FPS"]:.0f} | {r["FPS_min"]:.0f}–{r["FPS_max"]:.0f} | {r["GPU_TIMELINE_MS"]:.4f} | {r["CPU_P99_MS"]:.4f} | {r["CPU_MAX_MS"]:.3f} | {r["DRAIN_MS"]:.3f} | {gpu} |')
        lines.append('')
    table('A. 无绘制交接',lambda r:r['case'].startswith('A_'))
    lines+=['A 的共享图像在计时外初始化并逐像素验证保留；计时内无 Draw、无 Clear、无 Copy、无后处理。GENERAL 与 ATTACHMENTS 分别保留所有权转换 / 外部 semaphore，后者另保留 Full 的附件布局转换。A_SHARED_IDLE 保留共享分配但不提交 VK；A_VK_IDLE 是不碰共享图像的空 Vulkan 提交。GPU 硬件 IA 计数逐帧必须为零。','']
    table('B. 16 个 GL 兼容 Draw',lambda r:r['case'].startswith('B_'))
    lines+=['两边保持核心绘制 ID / 剔除、完整往返、两次深度复制和相同后处理。OFF 明确记 16 个 INTENTIONALLY_EXCLUDED_DRAWS；图像只与相同 ON/OFF 模式的 GL4.6 参考比较，不能要求 ON 与 OFF 图像相同。','']
    table('C. 深度复制与依赖',lambda r:r['case'].startswith('C_'))
    lines+=['VK 使用与 Full 相同 D32F 尺寸、附件→传输→附件布局和屏障。DEPENDENCIES_ONLY 保留屏障但不复制；VK_COPY / GL_COPY / BOTH_COPY 分别增加实际复制。完整源图与所有有效目标逐浮点值验证。VK_DEPTH_MS 包含复制前后屏障；GL_DEPTH_MS 覆盖 GL 复制命令区间，详见 summary.json。GL 的 NO_COPY 控制保留相同 FBO 绑定，不添加虚构 API 同步。','']
    table('D. 往返次数与完整流程',lambda r:r['api'].startswith('VK') and r['case'] in ['A_HANDOFF_ATTACHMENTS','D_ROUNDTRIPS_2','D_ROUNDTRIPS_4','B_FULL_ON'])
    lines+=['A_HANDOFF_ATTACHMENTS 为 1 次往返 / 2 次单向；D_ROUNDTRIPS_2 为 2 / 4；D_ROUNDTRIPS_4 为 4 / 8。均无绘制与复制，改变的只有闭环次数及其必要命令提交。B_FULL_ON 是有完整绘制/复制/后处理的 1 次往返参考，不能仅按交接次数解释其总成本。','',
            '| API / 单向 | 完成延迟 P50 ms | P99 ms | 最长 ms |','|---|---:|---:|---:|']
    for r in summaries:
        if r['mode']=='SYNCHRONIZED_DIRECTION':lines.append(f'| {r["api"]} / {r["case"]} | {r["COMPLETION_P50_MS"]:.4f} | {r["COMPLETION_P99_MS"]:.4f} | {r["COMPLETION_MAX_MS"]:.4f} |')
    lines+=['','单向表使用 CPU 同步完成延迟，包括命令录制、提交及 fence / glFinish。关闭流水线是测量方法的一部分，不与上面的两帧流水 FPS 直接比较。','',
            '## 配对差值','', '| API | 从 → 到 | GPU 时间线增量 ms | 墙钟帧增量 ms | FPS 变化 |','|---|---|---:|---:|---:|']
    for r in deltas:lines.append(f'| {r["api"]} | {r["before"]} → {r["after"]} | {r["median_gpu_delta_ms"]:+.4f} | {r["median_wall_delta_ms"]:+.4f} | {r["median_fps_percent"]:+.1f}% |')
    lines+=['','配对差值为同轮差值的中位数，不是两列中位数相减。负值不代表负成本；需同时查看五轮波动与局部时间戳。仅这组实验不能证明真实游戏具有相同瓶颈。',
            '验证层可用性见各 JVM 日志；计时关闭 validation。GPU 占用来自 nvidia-smi，属于整卡采样。原始资料与测量时内核及源码指纹保留在当前目录。']
    (out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--seconds',type=float,default=3);p.add_argument('--warmup',type=int,default=1000);p.add_argument('--width',type=int,default=1920);p.add_argument('--height',type=int,default=1080);p.add_argument('--preflight-only',action='store_true');p.add_argument('--summarize-only',action='store_true');p.add_argument('--output',default='build/reports/interop-ablation');args=p.parse_args()
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(parents=True,exist_ok=True)
    if args.summarize_only:summarize(out,json.loads((out/'runs.json').read_text()));return
    if (out/'runs.json').exists():raise RuntimeError('Use a new output directory; existing runs are preserved')
    archive(out);guard=Guard();runs=[];rng=random.Random(20261028)
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            reference=out/'preflight/GL4.6'
            for api in ['GL4.6']+[a for a in APIS if a!='GL4.6']:
                folder=out/'preflight'/api;run(args.java,api,folder,guard,args,True,20261028);validate(folder,reference,api);print('PREFLIGHT PASS '+api,flush=True)
            (out/'preflight-pass.txt').write_text('PASS all APIs; actual zero-draw queries, full shared patterns, depth copies, B on/off IDs and full RGBA/D32 conformance\n')
            if args.preflight_only:return
            schedule=[]
            for n in range(1,args.rounds+1):
                apis=APIS.copy();rng.shuffle(apis);schedule.append(dict(round=n,apis=apis));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for api in apis:
                    folder=out/f'round-{n:02d}'/api;rows=run(args.java,api,folder,guard,args,False,20261028+n);validate(folder,reference,api)
                    for row in rows:row['round']=n
                    runs+=rows;(out/'runs.json').write_text(json.dumps(runs,indent=2));print('PASS round='+str(n)+' '+api,flush=True)
        finally:
            monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)

if __name__=='__main__':main()
