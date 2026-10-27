"""Production terrain + post-chain comparison: original GL, custom GL46, VK14 before/after interop fusion."""
import argparse,csv,datetime as dt,hashlib,json,random,re,shutil,statistics as stats,subprocess,time
from pathlib import Path
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
CASES=['VANILLA_GL','CUSTOM_GL46','VK14_BEFORE','VK14_AFTER']
PHASES={'TERRAIN':0,'TERRAIN_POST3':3}

def props(path):return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
def pixels(case,name):
    if (case/(name+'.rgba')).exists():return np.frombuffer((case/(name+'.rgba')).read_bytes(),np.uint8).copy(),np.frombuffer((case/(name+'-depth.f32')).read_bytes(),'<f4').copy()
    with np.load(case/'pixels.npz') as data:return data[name+'_rgba'],data[name+'_depth']
def validate(folder,reference,expected,case,phase):
    assert (folder/'workload.txt').read_text()==expected,'Actual section/quad manifest mismatch'
    if case=='CUSTOM_GL46':
        detail=(folder/'benchmark.txt').read_text()
        for flag in ['GPU_DRIVEN=true','INDIRECT_COUNT=true','SPIRV=true']:assert flag in detail,flag
        assert 'TERRAIN_INIT shaders=SPIRV' in (folder/'gl46-report.txt').read_text()
    a=props(folder/'pipeline-audit.txt');assert int(a['POST_PASSES_PER_FRAME'])==PHASES[phase]
    if case.startswith('VK'):
        wanted=[2 if case.endswith('BEFORE') else 1,0 if case.endswith('BEFORE') else 1,0 if case.endswith('BEFORE') or phase=='TERRAIN' else 1,0 if case.endswith('BEFORE') else PHASES[phase],3+(PHASES[phase] if case.endswith('BEFORE') else int(PHASES[phase]>0)),4+(4*PHASES[phase] if case.endswith('BEFORE') else 4*int(PHASES[phase]>0))]
        for i,value in enumerate(wanted):assert float(a[f'COUNTER_{i}_PER_FRAME'])==value,(case,phase,i,a,value)
    comparisons=[];saved={}
    for name in ['scene','final','after-final']:
        color,depth=pixels(folder,name);ref_color,ref_depth=pixels(reference,'final' if name=='after-final' else name)
        assert color.size==1920*1080*4 and depth.size==1920*1080 and np.isfinite(depth).all()
        delta=np.abs(color.reshape(-1,4).astype(np.int16)-ref_color.reshape(-1,4).astype(np.int16));z=np.abs(depth-ref_depth)
        mean=float(delta[:,:3].mean());fraction=float(np.any(delta[:,:3]>8,axis=1).mean());zbad=float((z>2e-6).mean())
        assert mean<1 and fraction<.01 and zbad<.001,(case,phase,name,mean,fraction,float(z.max()),zbad)
        comparisons.append(dict(image=name,rgb_mean_error=mean,rgb_pixels_gt8_fraction=fraction,depth_max_error=float(z.max()),depth_pixels_gt2e6_fraction=zbad,color_sha256=hashlib.sha256(color.tobytes()).hexdigest(),depth_sha256=hashlib.sha256(depth.tobytes()).hexdigest()))
        saved[name+'_rgba']=color;saved[name+'_depth']=depth
    assert comparisons[1]['color_sha256']==comparisons[2]['color_sha256'] and comparisons[1]['depth_sha256']==comparisons[2]['depth_sha256'],'Scene changed during timed run'
    np.savez_compressed(folder/'pixels.npz',**saved)
    for name in ['scene','final','after-final']:
        for suffix in ['.rgba','-depth.f32']:
            path=folder/(name+suffix)
            if path.exists():path.unlink()
    (folder/'conformance.json').write_text(json.dumps(comparisons,indent=2));return comparisons

def run(java,case,phase,folder,guard,args,preflight=False):
    for attempt in range(5):
        guard.idle();folder.mkdir(parents=True,exist_ok=True);vk=case.startswith('VK')
        runtime=ROOT/'vulkan-lab/build/trace/bench.args' if vk else ROOT/'build/reports/offscreen-performance/gl.args'
        scene=ROOT/'build/reports/offscreen-performance/matched-scene.bin'
        cmd=[java,'@'+str(runtime),'-Xms512m','-Xmx1g','-Dbench.pipeline=true','-Dbench.matchVulkanRaster=true','-Dbench.width=1920','-Dbench.height=1080','-Dbench.warmup='+str(16 if preflight else args.warmup),'-Dbench.seconds='+str(0 if preflight else args.seconds),'-Dbench.postPasses='+str(PHASES[phase]),'-Dbench.output='+str(folder)]
        if vk:cmd+=['-Dvulkanforge.vulkanVersion=1.4','-Dvulkanforge.noInteropOptimizations='+str(case.endswith('BEFORE')).lower(),'-Dbench.scene='+str(scene),'github.com.gengyoubo.vulkanforge.lab.NativePipelineBenchmark']
        elif case=='VANILLA_GL':cmd+=['github.com.gengyoubo.vulkanforge.client.VanillaGlOffscreenBenchmark',str(scene)]
        else:cmd+=['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark','4.6',str(scene)]
        (folder/'command.json').write_text(json.dumps(cmd,indent=2));print(('PREFLIGHT ' if preflight else 'MEASURE ')+str(folder.relative_to(ROOT)),flush=True)
        with (folder/'stdout.txt').open('w') as log:
            child=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid
            try:child.wait(timeout=180)
            except BaseException:child.terminate();child.wait(timeout=10);raise
        text=(folder/'stdout.txt').read_text(errors='replace')
        if child.returncode:raise RuntimeError(text[-5000:])
        lines=[line for line in text.splitlines() if line.startswith('PIPELINE_RESULT ')];assert len(lines)==1
        row=dict(re.findall(r'(\w+)=([^\s]+)',lines[0]));assert row['API']==case
        for k in row:
            if k!='API':row[k]=float(row[k])
        row.update(case=case,phase=phase)
        if not preflight and guard.collisions(row['START_MS'],row['END_MS']):
            backup=folder.with_name(folder.name+'-concurrent-'+str(int(time.time()*1000)));assert folder.resolve().is_relative_to(ROOT) and backup.resolve().is_relative_to(ROOT);folder.rename(backup);continue
        return row
    raise RuntimeError('Repeated benchmark interference')

def summarize(out,runs):
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(row[2])))
        except(ValueError,IndexError):pass
    summary=[]
    lines=['# 原版 GL / GL 4.6 / VK 1.4：交接与深度复制优化','',
           '相同实际 Section/四边形清单、相机、贴图、光照与透明排序；1920×1080、RGBA8 / D32F、两帧槽，无窗口呈现。每例每轮独立 JVM；五轮内随机顺序。',
           '原版 GL 使用 Minecraft ShaderInstance / VertexBuffer 逐 Section 路径；GL46 使用实际 Gl46Terrain（GPU Driven / Indirect Count / SPIR-V，关闭 Hi-Z）；VK 使用实际 SharedWorldRenderer 原生合成与 NativePost。统一不透明不剔背面、透明剔背面；不混用先前不同管线的 FPS。',
           'TERRAIN 为不透明+透明地形最终合成；TERRAIN_POST3 在同一结果上增加三次相同颜色变换。后者用于检查生产后处理链合并，属于指定负载，不代表所有真实游戏的后处理数量。GPU ms 为共同 GL 时钟上包含交接等待的完整时间跨度；整卡占用来自 nvidia-smi。','']
    for phase in PHASES:
        lines+=['## '+phase,'','| 路径 | FPS 中位数 | 五轮范围 | GPU 时间线 ms | CPU P99 ms | 整卡 GPU |','|---|---:|---:|---:|---:|---:|']
        for case in CASES:
            rows=[r for r in runs if r['case']==case and r['phase']==phase]
            samples=[u for t,u in telemetry if any(r['START_MS']+2000<=t<=r['END_MS']-300 for r in rows)]
            record=dict(case=case,phase=phase,rounds=len(rows),fps=stats.median(r['FPS'] for r in rows),fps_min=min(r['FPS'] for r in rows),fps_max=max(r['FPS'] for r in rows),gpu_ms=stats.median(r['GPU_MS'] for r in rows),cpu_p99_ms=stats.median(r['CPU_P99_MS'] for r in rows),gpu_utilization=stats.mean(samples) if samples else None)
            summary.append(record);util=f'{record["gpu_utilization"]:.1f}%' if samples else 'n/a'
            lines.append(f'| {case} | {record["fps"]:.0f} | {record["fps_min"]:.0f}–{record["fps_max"]:.0f} | {record["gpu_ms"]:.3f} | {record["cpu_p99_ms"]:.3f} | {util} |')
        deltas=[]
        for before in [r for r in runs if r['case']=='VK14_BEFORE' and r['phase']==phase]:
            after=next(r for r in runs if r['case']=='VK14_AFTER' and r['phase']==phase and r['round']==before['round']);deltas.append((after['FPS']/before['FPS']-1)*100)
        lines+=['',f'VK 同轮优化前后 FPS 变化中位数：{stats.median(deltas):+.1f}%；五轮范围 {min(deltas):+.1f}%～{max(deltas):+.1f}%。','']
    lines+=['工作量以实际绘制清单审计，图像/深度在计时外检查。优化前后 Vulkan 必须逐位一致；跨 API 的颜色/深度误差另存 conformance.json，不能把不同 Shader 实现描述为逐位一致。',
            'COUNTER_0..5 分别为深度复制、避免的复制、后处理 batch、batch 中 pass 数、VK queue submit、GL semaphore group 调用。三后处理时，预期优化前/后为：深度复制 2/1，VK submit 6/4，GL semaphore group 16/8；实际值逐例断言。',
            '这是实际渲染实现的受控地形/后处理离屏吞吐，不含世界逻辑、实体、HUD 或呈现，不能当成完整游戏 FPS。原始最长 CPU 帧、最后排空、图像和每轮参数均保留。']
    (out/'summary.json').write_text(json.dumps(summary,indent=2));(out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def archive(out):
    manifest={'git_base':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sha256':{}}
    source_roots=['src/main/java','src/main/resources/coremods','src/smoke/java','vulkan-lab/src/main/java','vulkan-lab/src/main/resources/shaders','vulkan-lab/src/test/java','benchmark-support/src/main/java','gl-interop/src/main/java','bridge-protocol/src/main/java']
    files=[p for directory in source_roots for p in (ROOT/directory).rglob('*') if p.is_file()]
    for directory in ['build/classes/java/main','build/classes/java/smoke','vulkan-lab/build/classes/java/main','vulkan-lab/build/classes/java/test']:
        files += list((ROOT/directory).rglob('*.class'))
    files += [Path(__file__),ROOT/'tools/gpu_benchmark_guard.py']
    for source in files:
        relative=source.relative_to(ROOT);target=out/'runtime-snapshot'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target);manifest['sha256'][str(relative)]=hashlib.sha256(source.read_bytes()).hexdigest()
    (out/'kernel-manifest.json').write_text(json.dumps(manifest,indent=2))

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--seconds',type=float,default=6);p.add_argument('--warmup',type=int,default=1000);p.add_argument('--preflight-only',action='store_true');p.add_argument('--output',default='build/reports/interop-optimized');args=p.parse_args()
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(parents=True,exist_ok=True)
    if (out/'runs.json').exists():raise RuntimeError('Choose a fresh output directory')
    archive(out)
    scene=ROOT/'build/reports/offscreen-performance/matched-scene.bin';expected=scene.with_name('expected-workload.txt').read_text();(out/'scene-sha256.txt').write_text(hashlib.sha256(scene.read_bytes()).hexdigest());(out/'expected-workload.txt').write_text(expected)
    guard=Guard();runs=[];rng=random.Random(20261028)
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            for phase in PHASES:
                reference=out/'preflight'/phase/'VANILLA_GL'
                for case in CASES:
                    folder=out/'preflight'/phase/case;run(args.java,case,phase,folder,guard,args,True);validate(folder,reference,expected,case,phase);print('PREFLIGHT PASS '+phase+' '+case,flush=True)
                before=out/'preflight'/phase/'VK14_BEFORE';after=out/'preflight'/phase/'VK14_AFTER'
                for name in ['scene','final']:
                    a,z=pixels(before,name);b,w=pixels(after,name);assert np.array_equal(a,b) and np.array_equal(z,w),'Vulkan optimization changed output'
            (out/'preflight-pass.txt').write_text('PASS actual draw manifests, full RGB/depth comparisons, Vulkan before/after bit-exact, measured submission/copy counts\n')
            if args.preflight_only:return
            schedule=[]
            for n in range(1,args.rounds+1):
                jobs=[(phase,case) for phase in PHASES for case in CASES];rng.shuffle(jobs);schedule.append(dict(round=n,jobs=jobs));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for phase,case in jobs:
                    folder=out/f'round-{n:02d}'/phase/case;row=run(args.java,case,phase,folder,guard,args);validate(folder,out/'preflight'/phase/'VANILLA_GL',expected,case,phase)
                    if case.startswith('VK'):
                        reference=out/'preflight'/phase/case
                        for name in ['scene','final']:
                            a,z=pixels(folder,name);b,w=pixels(reference,name);assert np.array_equal(a,b) and np.array_equal(z,w),'Vulkan output changed between runs'
                    row['round']=n;runs.append(row);(out/'runs.json').write_text(json.dumps(runs,indent=2));print('PASS '+str(n)+' '+phase+' '+case+' FPS='+str(row['FPS']),flush=True)
        finally:monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)
if __name__=='__main__':main()
