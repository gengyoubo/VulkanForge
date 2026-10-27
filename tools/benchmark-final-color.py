"""Paired final-color throughput and separate gate ablations. Never remove semaphore dependencies."""
import argparse,csv,datetime as dt,hashlib,importlib.util,json,random,re,statistics as st,subprocess,time
from pathlib import Path
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('three',ROOT/'tools/benchmark-three-modes.py');three=importlib.util.module_from_spec(spec);spec.loader.exec_module(three);common=three.common
PRIMARY=['GL46_NATIVE','GLVK_INTERVAL','VK_NATIVE_OFFSCREEN','FINAL_COLOR_ONLY','FINAL_COLOR_GL','FINAL_COLOR_EARLY']
DIAGNOSTIC=['GATE_NORMAL','GATE_MINIMAL','GATE_MINIMAL_EARLY','GATE_EARLY','GATE_INPUT_READY','GATE_OUTPUT_READY','GATE_BOTH_READY','GATE_EARLY_FIXED','GATE_MINIMAL_EARLY_FIXED']

def run(java,case,folder,guard,args,preflight=False,version='1.4'):
    if case in PRIMARY[:3]:return three.run(java,case,'TERRAIN_POST3',folder,guard,args,preflight,version)
    diagnostic=case.startswith('GATE_');minimal=case.startswith('GATE_MINIMAL');early='EARLY' in case
    mode=case.removeprefix('GATE_') if diagnostic else 'NONE'
    if mode in ['EARLY','MINIMAL','MINIMAL_EARLY'] or mode.endswith('_FIXED'):mode='NORMAL'
    for attempt in range(5):
        guard.idle();folder.mkdir(parents=True,exist_ok=True)
        cmd=[java,'@'+str(ROOT/'vulkan-lab/build/trace/bench.args'),'-Xms512m','-Xmx1g','-Dbench.width=1920','-Dbench.height=1080','-Dbench.output='+str(folder),'-Dbench.warmup='+str(16 if preflight else (args.gate_warmup if diagnostic else args.warmup)),'-Dbench.seconds='+str(0 if preflight else (args.gate_seconds if diagnostic else args.seconds)),'-Dvulkanforge.interopProfile=true','-Dvulkanforge.vulkanVersion='+version,'-Dbench.label='+case,'-Dbench.noConsumer='+str(case=='FINAL_COLOR_ONLY' or minimal).lower(),'-Dbench.minimalHandoff='+str(minimal).lower(),'-Dbench.probe='+mode,'-Dbench.releaseEarly='+str(early).lower(),'-Dbench.probeBeforeEarlyRelease='+str(case.endswith('_FIXED')).lower(),'github.com.gengyoubo.vulkanforge.lab.FinalColorBenchmark']
        (folder/'command.json').write_text(json.dumps(cmd,indent=2));print(('PREFLIGHT ' if preflight else 'MEASURE ')+str(folder.relative_to(ROOT)),flush=True)
        with (folder/'stdout.txt').open('w') as log:
            child=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid
            try:child.wait(timeout=240)
            except BaseException:child.terminate();child.wait(timeout=10);raise
        text=(folder/'stdout.txt').read_text(errors='replace')
        if child.returncode:raise RuntimeError(text[-6000:])
        line=next(x for x in text.splitlines() if x.startswith('PIPELINE_RESULT '));row=dict(re.findall(r'(\w+)=([^\s]+)',line));assert row['API']==case
        for key in row:
            if key!='API':row[key]=float(row[key])
        row.update(case=case,phase='TERRAIN_POST3' if not minimal else 'MINIMAL_HANDOFF',version=version)
        if not preflight and guard.collisions(row['START_MS'],row['END_MS']):
            target=folder.with_name(folder.name+'-concurrent-'+str(int(time.time()*1000)));assert target.resolve().is_relative_to(ROOT);folder.rename(target);continue
        return row
    raise RuntimeError('Repeated concurrent GPU workload')

def validate(folder,reference,expected,case):
    if case in PRIMARY[:3]:
        result=three.validate(folder,reference.parent/'GL46_NATIVE',expected,case,'TERRAIN_POST3')
        if case!='GL46_NATIVE':
            for name in ['scene','final','after-final']:
                a,z=common.pixels(folder,name);b,w=common.pixels(reference,name);assert np.array_equal(a,b) and np.array_equal(z,w),('Vulkan reference mismatch',case,name)
        if case=='GLVK_INTERVAL':
            p=common.props(folder/'phase-profile.txt')
            for key,n in [('VK_OPAQUE_MS',1),('VK_TRANSLUCENT_MS',1),('VK_CULL_MS',1),('VK_DEPTH_COPY_MS',1),('VK_COMPOSITE_MS',2),('VK_POST_MS',3)]:assert float(p[key+'_SAMPLES_PER_FRAME'])==n,(key,p)
        return result
    minimal=case.startswith('GATE_MINIMAL');a=common.props(folder/'pipeline-audit.txt')
    wanted={'SHARED_IMAGES':'2','EXTERNAL_SEMAPHORES':'4','SHARED_DEPTH_IMAGES':'0','DEPTH_COPIES':'0','GL_FALLBACK_DRAWS':'0','FINAL_DEPTH_EXPORTED':'false','GL_GROUP_CALLS_PER_FRAME':'2.0','GL_IMAGE_REFERENCES_PER_FRAME':'2.0','VK_FRAME_SUBMITS_PER_FRAME':'1.0','FINAL_COLOR_EXPORTS_PER_FRAME':'1','PRESENT_CALLS':'0','DRAWN_VERTICES':'0' if minimal else '699024','POST_PASSES_PER_FRAME':'0' if minimal else '3'}
    for key,value in wanted.items():assert a[key]==value,(case,key,a[key],value)
    assert a['VK_PROBE_SUBMITS_PER_FRAME']==('1.0' if case.startswith('GATE_') else '0.0')
    assert a['GL_CONSUMER_DRAWS_PER_FRAME']==('0' if minimal or case=='FINAL_COLOR_ONLY' else '1')
    assert a['EARLY_RELEASE']==str('EARLY' in case).lower()
    if case.endswith('_FIXED'):assert a['PROBE_BEFORE_EARLY_RELEASE']=='true'
    if not minimal:assert (folder/'workload.txt').read_text()==expected
    comparisons=[];saved={}
    if minimal:
        initial=np.frombuffer((folder/'initial.rgba').read_bytes(),np.uint8).copy();assert initial.size==1920*1080*4
        assert np.all(np.abs(initial.reshape(-1,4).astype(np.int16)-np.array([32,64,128,255]))<=1),'Bad initialized image'
        saved['initial_rgba']=initial
    for name in (['final','after-final'] if minimal else ['scene','final','after-final']):
        color=np.frombuffer((folder/(name+'.rgba')).read_bytes(),np.uint8).copy();assert color.size==1920*1080*4
        if minimal:
            assert np.array_equal(color,initial),'Minimal interop changed initialized image'
        else:
            ref,_=common.pixels(reference,'final' if name=='after-final' else name);assert np.array_equal(color,ref),('Final color differs from native',case,name,int(np.abs(color.astype(int)-ref.astype(int)).max()))
        saved[name+'_rgba']=color;comparisons.append(dict(image=name,color_sha256=hashlib.sha256(color.tobytes()).hexdigest(),bit_exact=True))
    if not minimal:
        depth=np.frombuffer((folder/'scene-depth.f32').read_bytes(),'<f4').copy();_,ref=common.pixels(reference,'scene');assert np.array_equal(depth,ref);saved['scene_depth']=depth
    assert np.array_equal(saved['final_rgba'],saved['after-final_rgba'])
    np.savez_compressed(folder/'pixels.npz',**saved)
    for name in ['initial','scene','final','after-final']:
        for suffix in ['.rgba','-depth.f32']:
            p=folder/(name+suffix)
            if p.exists():p.unlink()
    (folder/'conformance.json').write_text(json.dumps(comparisons,indent=2));return comparisons

def summarize(out,rows):
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(row[2])))
        except(ValueError,IndexError):pass
    summary=[];lines=['# GL-VK 最终颜色交接与 Gate 消融','',
        '1920×1080、RGBA8/D32F、两帧槽，同一实际 Draw ID / 699024 个地形输入装配顶点和三次后处理。GL46、区间混合、私有 VK、最终颜色交接、交接加一次 GL 全屏消费、提前归还 GL 租约以随机顺序配对测试。所有 FPS 计入排空；无窗口呈现。',
        'FINAL_COLOR_* 与 VK Native 复用同一 NativeWorldRenderer / VulkanSceneCore / NativePost。仅两张最终颜色图被共享，深度与中间图私有。每帧一次 VK→GL 最终输出，复用前仍有必要的 GL→VK 归还；一组外部 wait/signal、两次 GL 组调用，未删除 Semaphore。',
        'FINAL_COLOR_ONLY 保留完整 Vulkan 绘制而不执行 GL 消费；FINAL_COLOR_GL 增加一次全屏 GL 采样到本地颜色 FBO；EARLY 只将 GL 归还与 flush 提前到 CPU 录制之前，以便重叠，不增加 host wait，不改变图像依赖。',
        '当前区间混合仍保留两次全屏 VK 合成和一次深度复制；最终颜色实验省去这些兼容中间阶段，没有中途 GL 回退也不导出深度，因此不是完整 Forge 游戏的替代开关。',
        'GPU_MS 为 GL 时钟的完整跨度，纯 VK 为自身时钟绘制跨度；不能跨时钟相减。Gate 与绘制跨度可能重叠，不将 Gate + Body + GL span 直接求和。GPU 占用是整卡采样，不是单 Pass 活跃率。','',
        '| 路径 | 轮数 | FPS 中位数 | 范围 | GPU span ms | CPU P99 ms | 整卡 GPU |','|---|---:|---:|---:|---:|---:|---:|']
    for case in PRIMARY:
        r=[x for x in rows if x['case']==case]
        if not r:continue
        util=[u for t,u in telemetry if any(x['START_MS']+2000<=t<=x['END_MS']-300 for x in r)]
        item=dict(case=case,rounds=len(r),fps=st.median(x['FPS'] for x in r),fps_min=min(x['FPS'] for x in r),fps_max=max(x['FPS'] for x in r),gpu_ms=st.median(x['GPU_MS'] for x in r),cpu_p99_ms=st.median(x['CPU_P99_MS'] for x in r),cpu_max_ms=max(x['CPU_MAX_MS'] for x in r),gpu_utilization=st.mean(util) if util else None);summary.append(item)
        usage=f'{item["gpu_utilization"]:.1f}%' if util else 'n/a';lines.append(f'| {case} | {len(r)} | {item["fps"]:.0f} | {item["fps_min"]:.0f}–{item["fps_max"]:.0f} | {item["gpu_ms"]:.3f} | {item["cpu_p99_ms"]:.3f} | {usage} |')
    lines+=['','## 分段时间（轮间中位数）','','| 路径 | 不透明 ms | 透明 ms | 剔除 ms | 后处理 ms | VK 合成 ms | 深度复制 ms | GL 消费 ms | CPU 录制 ms | CPU 准备/上传 ms | CPU 提交 ms |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for case in PRIMARY[1:]:
        r=[x for x in rows if x['case']==case]
        if not r:continue
        p=[common.props(out/f'round-{x["round"]:02d}'/case/'phase-profile.txt') for x in r];v=lambda k:st.median(float(a.get(k,0)) for a in p)
        prep=v('CPU_PREPARE_MS')+v('CPU_UPLOAD_FLUSH_MS');lines.append(f'| {case} | {v("VK_OPAQUE_MS"):.4f} | {v("VK_TRANSLUCENT_MS"):.4f} | {v("VK_CULL_MS"):.4f} | {v("VK_POST_MS"):.4f} | {v("VK_COMPOSITE_MS"):.4f} | {v("VK_DEPTH_COPY_MS"):.4f} | {v("GL_CONSUMER_MS"):.4f} | {v("CPU_RECORD_MS"):.4f} | {prep:.4f} | {v("CPU_QUEUE_SUBMIT_MS"):.4f} |')
    if any(x['case'].startswith('GATE_') for x in rows):
        lines+=['','## Gate 诊断（单独增加探针提交）','',
            'VK Gate 是同一 VK 时钟的“无等待探针 → 外部等待后第一标记”；包括信号可用性、CPU 发信号/提交间隙、驱动调度和资源边界，不能称为纯 GPU idle 或 Semaphore 固定延迟。GL Gate 是同一 GL 时钟等待指令前后的可见跨度；驱动可能在前标记可执行之前就推迟 GL 批次，小数值不能证明输出交接免费。',
            'INPUT_READY/BOTH_READY 在 probe 前用 glFinish 保证输入已就绪；OUTPUT_READY/BOTH_READY 在 GL 等待标记前用 VkFence 保证输出已就绪。这些是强制排空的因果对照，额外 host wait 单列，不是性能优化。MINIMAL 无地形、后处理和 GL 消费，测量内只有同步、所有权和时间戳/零顶点统计；共享图像只在初始化时清除，计时前后核对内容。','',
            '| 诊断 | VK Gate span ms | GL Gate span ms | VK Body ms | GL frame span ms | 强制 Host Wait CPU ms |','|---|---:|---:|---:|---:|---:|']
        for case in DIAGNOSTIC:
            r=[x for x in rows if x['case']==case]
            if not r:continue
            p=[common.props(out/'gate'/f'round-{x["round"]:02d}'/case/'phase-profile.txt') for x in r];v=lambda k:st.median(float(a[k]) for a in p)
            item=dict(case=case,rounds=len(r),vk_gate_ms=v('VK_GATE_SPAN_MS'),gl_gate_ms=v('GL_GATE_SPAN_MS'),vk_body_ms=v('VK_BODY_MS'),gl_frame_ms=v('GL_FRAME_SPAN_MS'),control_cpu_ms=v('DIAGNOSTIC_CONTROL_CPU_MS'));summary.append(item);lines.append(f'| {case} | {item["vk_gate_ms"]:.4f} | {item["gl_gate_ms"]:.4f} | {item["vk_body_ms"]:.4f} | {item["gl_frame_ms"]:.4f} | {item["control_cpu_ms"]:.4f} |')
    lines+=['','每帧 GPU 时间线原始数据在 gpu-timeline.csv，CPU 原始帧与 API 调用在 cpu-frames.csv / phase-profile.txt。只在已完成的帧槽读取查询。完整世界路径校验真实绘制清单、硬件顶点和最终全部 RGBA；最终颜色路径没有声称向 GL 提供最终深度，私有诊断场景深度另核对。保留全部慢样本；验证层在本机不可用。',
        '方法依据：[Vulkan 时间戳查询](https://docs.vulkan.org/spec/latest/chapters/queries.html)、[GL 外部对象/信号量](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt)、[GL Timer Query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt)。没有跨 API 时钟相减，也没有把 CPU 调用耗时当作 GPU 等待。']
    (out/'summary.json').write_text(json.dumps(summary,indent=2));(out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--seconds',type=float,default=8);p.add_argument('--warmup',type=int,default=5000);p.add_argument('--gate-rounds',type=int,default=3);p.add_argument('--gate-seconds',type=float,default=3);p.add_argument('--gate-warmup',type=int,default=1000);p.add_argument('--preflight-only',action='store_true');p.add_argument('--output',default='build/reports/final-color-gate');args=p.parse_args()
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(parents=True,exist_ok=True)
    if (out/'runs.json').exists():raise RuntimeError('Choose a fresh output directory')
    common.archive(out);manifest=json.loads((out/'kernel-manifest.json').read_text())
    for source in [Path(__file__).resolve(),ROOT/'tools/benchmark-three-modes.py']:
        relative=source.relative_to(ROOT);target=out/'runtime-snapshot'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes());manifest['sha256'][str(relative)]=hashlib.sha256(source.read_bytes()).hexdigest()
    (out/'kernel-manifest.json').write_text(json.dumps(manifest,indent=2));expected=(ROOT/'build/reports/offscreen-performance/expected-workload.txt').read_text();(out/'expected-workload.txt').write_text(expected)
    guard=Guard();rows=[];rng=random.Random(20261030)
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            reference=out/'preflight'/'VK_NATIVE_OFFSCREEN'
            for case in ['GL46_NATIVE','VK_NATIVE_OFFSCREEN','GLVK_INTERVAL']+PRIMARY[3:]+DIAGNOSTIC:
                folder=out/'preflight'/case;run(args.java,case,folder,guard,args,True);validate(folder,reference,expected,case);print('PREFLIGHT PASS '+case,flush=True)
            if args.preflight_only:return
            schedule=[]
            for n in range(1,args.rounds+1):
                cases=PRIMARY.copy();rng.shuffle(cases);schedule.append(dict(kind='primary',round=n,cases=cases));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for case in cases:
                    folder=out/f'round-{n:02d}'/case;row=run(args.java,case,folder,guard,args);validate(folder,reference,expected,case);row['round']=n;rows.append(row);(out/'runs.json').write_text(json.dumps(rows,indent=2));print('PASS '+str(n)+' '+case+' FPS='+str(row['FPS']),flush=True)
            for n in range(1,args.gate_rounds+1):
                cases=DIAGNOSTIC.copy();rng.shuffle(cases);schedule.append(dict(kind='gate',round=n,cases=cases));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for case in cases:
                    folder=out/'gate'/f'round-{n:02d}'/case;row=run(args.java,case,folder,guard,args);validate(folder,reference,expected,case);row['round']=n;rows.append(row);(out/'runs.json').write_text(json.dumps(rows,indent=2));print('GATE PASS '+str(n)+' '+case,flush=True)
        finally:monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,rows)
if __name__=='__main__':main()
