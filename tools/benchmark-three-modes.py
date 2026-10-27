"""Same Draw IDs and outputs: GL46_NATIVE, GLVK_HYBRID, VK_NATIVE_OFFSCREEN. Presentation is a separate contract."""
import argparse,csv,datetime as dt,hashlib,importlib.util,json,random,re,statistics as stats,subprocess,time
from pathlib import Path
import numpy as np
from gpu_benchmark_guard import Guard

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('interop_optimized',ROOT/'tools/benchmark-interop-optimized.py');common=importlib.util.module_from_spec(spec);spec.loader.exec_module(common)
CASES=['GL46_NATIVE','GLVK_HYBRID','VK_NATIVE_OFFSCREEN'];PHASES={'TERRAIN':0,'TERRAIN_POST3':3}

def run(java,case,phase,folder,guard,args,preflight=False,version='1.4',*,jvm_properties=None):
    for attempt in range(5):
        guard.idle();folder.mkdir(parents=True,exist_ok=True);native=case=='VK_NATIVE_OFFSCREEN';gl=case=='GL46_NATIVE';scene=ROOT/'build/reports/offscreen-performance/matched-scene.bin'
        runtime=ROOT/'build/reports/offscreen-performance/gl.args' if gl else ROOT/'vulkan-lab/build/trace/bench.args'
        cmd=[java,'@'+str(runtime),'-Xms512m','-Xmx1g','-Dbench.pipeline=true','-Dbench.matchVulkanRaster=true','-Dbench.width=1920','-Dbench.height=1080','-Dbench.warmup='+str(16 if preflight else args.warmup),'-Dbench.seconds='+str(0 if preflight else args.seconds),'-Dbench.postPasses='+str(PHASES[phase]),'-Dbench.output='+str(folder),'-Dbench.label='+case,'-Dvulkanforge.vulkanVersion='+version,'-Dvulkanforge.nvidiaExtensions=','-Dvulkanforge.interopProfile=true','-Dbench.nativeInterval='+str(case=='GLVK_INTERVAL').lower()]
        if jvm_properties:cmd += ['-D'+key+'='+str(value) for key,value in jvm_properties.items()]
        if gl:cmd+=['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark','4.6',str(scene)]
        elif native:cmd+=['-Dbench.scene='+str(scene),'github.com.gengyoubo.vulkanforge.lab.VkOnlyLauncher']
        else:cmd+=['-Dvulkanforge.noInteropOptimizations=false','-Dbench.scene='+str(scene),'github.com.gengyoubo.vulkanforge.lab.NativePipelineBenchmark']
        (folder/'command.json').write_text(json.dumps(cmd,indent=2));print(('PREFLIGHT ' if preflight else 'MEASURE ')+str(folder.relative_to(ROOT)),flush=True)
        with (folder/'stdout.txt').open('w') as log:
            child=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);guard.own=child.pid
            try:child.wait(timeout=180)
            except BaseException:child.terminate();child.wait(timeout=10);raise
        text=(folder/'stdout.txt').read_text(errors='replace')
        if child.returncode:raise RuntimeError(text[-5500:])
        lines=[line for line in text.splitlines() if line.startswith('PIPELINE_RESULT ')];assert len(lines)==1
        row=dict(re.findall(r'(\w+)=([^\s]+)',lines[0]));assert row['API']==case
        for key in row:
            if key!='API':row[key]=float(row[key])
        row.update(case=case,phase=phase,version=version)
        if native:
            assert 'VK_ONLY_CLASSLOADER=PASS GL_GLFW_INTEROP_LOAD_ATTEMPTS=0' in text
            a=common.props(folder/'pipeline-audit.txt')
            for key in ['GL_CONTEXTS','SHARED_IMAGES','EXTERNAL_SEMAPHORES','GL_FALLBACK_DRAWS','DEPTH_COPIES','PRESENT_CALLS']:assert a[key]=='0',(case,key)
            assert a['VK_SUBMITS_PER_FRAME']=='1.0' and a['DRAWN_VERTICES']=='699024'
        if not preflight and guard.collisions(row['START_MS'],row['END_MS']):
            backup=folder.with_name(folder.name+'-concurrent-'+str(int(time.time()*1000)));assert backup.resolve().is_relative_to(ROOT);folder.rename(backup);continue
        return row
    raise RuntimeError('Repeated concurrent GPU benchmark interference')

def validate(folder,reference,expected,case,phase):
    alias={'GL46_NATIVE':'CUSTOM_GL46','GLVK_HYBRID':'VK14_AFTER','GLVK_INTERVAL':'NATIVE_PRIVATE','VK_NATIVE_OFFSCREEN':'NATIVE_PRIVATE'}[case]
    if case=='GLVK_INTERVAL':
        audit=common.props(folder/'pipeline-audit.txt');posts=PHASES[phase]
        wanted=[1,1,int(posts>0),posts,2,4 if posts else 2,1,2,8 if posts else 4]
        for i,value in enumerate(wanted):assert float(audit[f'COUNTER_{i}_PER_FRAME'])==value,(case,phase,i,audit,value)
    return common.validate(folder,reference,expected,alias,phase)

def compare_vulkan(native,hybrid):
    result=[]
    for name in ['scene','final']:
        a,z=common.pixels(native,name);b,w=common.pixels(hybrid,name);delta=np.abs(a.astype(np.int16)-b.astype(np.int16));depth=np.abs(z-w)
        assert int(delta.max())<=1 and float(depth.max())<=2e-6,('native/hybrid mismatch',name,int(delta.max()),float(depth.max()))
        result.append(dict(image=name,color_max_error=int(delta.max()),depth_max_error=float(depth.max()),bit_exact=bool(np.array_equal(a,b) and np.array_equal(z,w))))
    (native/'native-hybrid-conformance.json').write_text(json.dumps(result,indent=2));return result

def summarize(out,runs):
    telemetry=[]
    for row in csv.reader((out/'telemetry.csv').read_text().splitlines()):
        try:telemetry.append((dt.datetime.strptime(row[0].strip(),'%Y/%m/%d %H:%M:%S.%f').timestamp()*1000,float(row[2])))
        except(ValueError,IndexError):pass
    summary=[];lines=['# 三种模式：共享 Vulkan 内核，分离跨 API 成本','',
    '1920×1080，RGBA8 / D32F，两帧槽；同一实际 Section/四边形 Draw ID 清单、相机、纹理、光照和透明顺序；五轮随机顺序、独立 JVM。不是完整游戏 FPS。',
    'GL46_NATIVE 使用生产 Gl46Terrain；GLVK_HYBRID 使用共享图像的生产混合执行器；VK_NATIVE_OFFSCREEN 使用同一 VulkanSceneCore / WorldScene / GpuCuller / NativePost，全部图像私有，帧内一次 VK 提交。',
    '每个原生样本均由隔离类加载器禁止加载 OpenGL、GLFW 和 GlInterop，任何加载尝试都会失败；不仅仅是把 GL 绘制关掉。Native 的私有设备没有启用 Win32 外部内存/信号量扩展。硬件地形输入装配顶点数逐帧核对为 699024。',
    'GPU ms：GL/混合由同一 GL 时钟首尾计时（包含交接等待），Native 由 VK 时钟覆盖完整绘制命令缓冲。两种时钟不相减；Native 数值不包含 CPU 提交/查询，不代表 GPU 活跃百分比。FPS 均计入最终排空。',
    'Native 的颜色与深度仅在计时外读回，并翻转行以对齐 GL 抓图约定；被计时的路径没有 GL 或图像读回。','']
    for phase in PHASES:
        lines+=['## '+phase,'','| 执行器 | FPS 中位数 | 五轮范围 | GPU 时间线 ms | CPU P99 ms | 整卡 GPU |','|---|---:|---:|---:|---:|---:|']
        for case in CASES:
            rows=[r for r in runs if r['phase']==phase and r['case']==case];gpu=[u for t,u in telemetry if any(r['START_MS']+2000<=t<=r['END_MS']-300 for r in rows)]
            item=dict(case=case,phase=phase,rounds=len(rows),fps=stats.median(r['FPS'] for r in rows),fps_min=min(r['FPS'] for r in rows),fps_max=max(r['FPS'] for r in rows),gpu_ms=stats.median(r['GPU_MS'] for r in rows),cpu_p99_ms=stats.median(r['CPU_P99_MS'] for r in rows),cpu_max_ms=max(r['CPU_MAX_MS'] for r in rows),gpu_utilization=stats.mean(gpu) if gpu else None);summary.append(item)
            usage=f'{item["gpu_utilization"]:.1f}%' if gpu else 'n/a';lines.append(f'| {case} | {item["fps"]:.0f} | {item["fps_min"]:.0f}–{item["fps_max"]:.0f} | {item["gpu_ms"]:.3f} | {item["cpu_p99_ms"]:.3f} | {usage} |')
        lines.append('')
    lines+=['所有计时前后实际 Draw ID、顶点数、完整颜色和深度检查位于 conformance.json / native-hybrid-conformance.json。Native 中未实现的实体/GUI/模组绘制必须显式拒绝；当前负载只请求地形和指定后处理，没有以省略请求内容换取 FPS。',
    'VK_NATIVE_SWAPCHAIN 是下一项独立合同；此报告没有创建窗口或 Swapchain，也没有测得 Present 延迟。当前完整游戏的 VK 模式仍由兼容性门禁拒绝启动，不能把本报告描述为纯 VK 游戏已全部实现。',
    '保留所有慢样本、逐帧 CPU、最后排空、每轮参数和测量内核归档。验证层状态见 JVM 原始日志，计时关闭验证层。']
    (out/'summary.json').write_text(json.dumps(summary,indent=2));(out/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--seconds',type=float,default=6);p.add_argument('--warmup',type=int,default=1000);p.add_argument('--preflight-only',action='store_true');p.add_argument('--output',default='build/reports/three-modes');args=p.parse_args()
    out=(ROOT/args.output).resolve();assert out.is_relative_to(ROOT);out.mkdir(parents=True,exist_ok=True)
    if (out/'runs.json').exists():raise RuntimeError('Choose a new output directory')
    common.archive(out);manifest=json.loads((out/'kernel-manifest.json').read_text());path=Path(__file__).resolve();relative=path.relative_to(ROOT);target=out/'runtime-snapshot'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(path.read_bytes());manifest['sha256'][str(relative)]=hashlib.sha256(path.read_bytes()).hexdigest();(out/'kernel-manifest.json').write_text(json.dumps(manifest,indent=2))
    expected=(ROOT/'build/reports/offscreen-performance/expected-workload.txt').read_text();(out/'expected-workload.txt').write_text(expected);guard=Guard();runs=[];rng=random.Random(20261028)
    with (out/'telemetry.csv').open('w') as log:
        monitor=subprocess.Popen(['nvidia-smi','--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr','--format=csv,noheader,nounits','--loop-ms=200'],stdout=log,stderr=subprocess.STDOUT)
        try:
            for phase in PHASES:
                reference=out/'preflight'/phase/'GL46_NATIVE'
                for case in CASES:
                    folder=out/'preflight'/phase/case;run(args.java,case,phase,folder,guard,args,True);validate(folder,reference,expected,case,phase);print('PREFLIGHT PASS '+phase+' '+case,flush=True)
                compare_vulkan(out/'preflight'/phase/'VK_NATIVE_OFFSCREEN',out/'preflight'/phase/'GLVK_HYBRID')
                for version in ['1.2','1.3']:
                    folder=out/'api-preflight'/version/phase;run(args.java,'VK_NATIVE_OFFSCREEN',phase,folder,guard,args,True,version);validate(folder,reference,expected,'VK_NATIVE_OFFSCREEN',phase);compare_vulkan(folder,out/'preflight'/phase/'GLVK_HYBRID');print('NATIVE API PASS '+version+' '+phase,flush=True)
            (out/'preflight-pass.txt').write_text('PASS three execution modes, native VK1.2/1.3/1.4, GL-free class loading, actual Draw IDs and vertices, color/depth agreement\n')
            if args.preflight_only:return
            schedule=[]
            for n in range(1,args.rounds+1):
                jobs=[(phase,case) for phase in PHASES for case in CASES];rng.shuffle(jobs);schedule.append(dict(round=n,jobs=jobs));(out/'schedule.json').write_text(json.dumps(schedule,indent=2))
                for phase,case in jobs:
                    folder=out/f'round-{n:02d}'/phase/case;row=run(args.java,case,phase,folder,guard,args);validate(folder,out/'preflight'/phase/'GL46_NATIVE',expected,case,phase)
                    if case=='VK_NATIVE_OFFSCREEN':compare_vulkan(folder,out/'preflight'/phase/'GLVK_HYBRID')
                    row['round']=n;runs.append(row);(out/'runs.json').write_text(json.dumps(runs,indent=2));print('PASS '+str(n)+' '+phase+' '+case+' FPS='+str(row['FPS']),flush=True)
        finally:monitor.terminate();monitor.wait(timeout=10);guard.close();(out/'other-java-benchmarks.json').write_text(json.dumps(guard.events,indent=2))
    summarize(out,runs)
if __name__=='__main__':main()
