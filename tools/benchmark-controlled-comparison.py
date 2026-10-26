"""Five randomized blocks, matched actual draw manifests, and independent GL 4.6 A/B switches."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import random
import re
import statistics as stats
import subprocess
import time
from gpu_benchmark_guard import Guard

ROOT = Path(__file__).resolve().parents[1]
CASES = ['GL4.4', 'GL4.5', 'GL4.6', 'GL4.6-noGpuDriven', 'GL4.6-noIndirectCount', 'GL4.6-noSpirv',
         'MATCH-VK1.2', 'MATCH-VK1.3', 'MATCH-VK1.4', 'MATCH-GL4.6']


def run_case(java, case, block, seconds, warmup, out, source, expected=None, guard=None):
    folder = out / f'block-{block:02d}' / case
    folder.mkdir(parents=True, exist_ok=True)
    matched = case.startswith('MATCH-')
    api = case.removeprefix('MATCH-').split('-')[0]
    vk = api.startswith('VK')
    runtime = ROOT / 'vulkan-lab/build/trace/bench.args' if vk else ROOT / 'build/reports/offscreen-performance/gl.args'
    command = [java, '@' + str(runtime), '-Dbench.width=1920', '-Dbench.height=1080', '-Dbench.radius=12',
               '-Dbench.frames=1', f'-Dbench.warmup={warmup}', '-Dbench.runs=1', f'-Dbench.seconds={seconds}',
               '-Dbench.output=' + str(folder), '-Dbench.audit=true', '-Dbench.matchVulkanTargets=' + str(matched).lower()]
    if vk:
        command += ['-Dvulkanforge.vulkanVersion=' + api[2:], '-Dbench.scene=' + str(source), 'github.com.gengyoubo.vulkanforge.lab.OffscreenBenchmark']
    else:
        flag = {'GL4.6-noGpuDriven': 'noGpuDriven', 'GL4.6-noIndirectCount': 'noIndirectCount', 'GL4.6-noSpirv': 'glsl'}.get(case)
        if flag:
            command.append('-Dvulkanforge.gl46.' + flag + '=true')
        command += ['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark', api[2:], str(source)]
    (folder / 'command.json').write_text(json.dumps(command, indent=2), encoding='utf-8')
    print(f'RUNNING block={block} case={case}', flush=True)
    if guard: guard.idle()
    with (folder / 'stdout.txt').open('w', encoding='utf-8') as log:
        child = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if guard: guard.own = child.pid
        child.wait(timeout=150)
        if guard: guard.own = None
    output = (folder / 'stdout.txt').read_text(encoding='utf-8', errors='replace')
    if child.returncode:
        raise RuntimeError(f'{case} failed; {folder}:\n{output[-4000:]}')
    workload = (folder / 'workload.txt').read_text(encoding='utf-8')
    if expected is not None and workload != expected:
        raise AssertionError(f'Actual section IDs / quad counts differ in {case}: {workload.splitlines()[0]} != {expected.splitlines()[0]}')
    if not vk:
        detail = (folder / 'benchmark.txt').read_text(encoding='utf-8')
        if api == 'GL4.6':
            desired = {'GPU_DRIVEN': 'noGpuDriven' not in case, 'INDIRECT_COUNT': 'noIndirectCount' not in case, 'SPIRV': 'noSpirv' not in case}
            for key, enabled in desired.items():
                if key + '=' + str(enabled).lower() not in detail:
                    raise AssertionError(f'{case}: switch {key} not as requested')
            shaders = (folder / 'gl46-report.txt').read_text(encoding='utf-8')
            if ('TERRAIN_INIT shaders=' + ('GLSL' if 'noSpirv' in case else 'SPIRV')) not in shaders:
                raise AssertionError(f'{case}: actual shader format differs')
    lines = [line for line in output.splitlines() if line.startswith('BENCH_RESULT ')]
    if len(lines) != 1:
        raise AssertionError(f'{case}: missing or duplicate result')
    row = dict(re.findall(r'(\w+)=([^\s]+)', lines[0]))
    for key in ['run', 'width', 'height', 'frames', 'START_MS', 'END_MS']:
        row[key] = int(row[key])
    for key in ['seconds', 'FPS', 'GPU_MS']:
        row[key] = float(row[key])
    row.update(case=case, block=block, workload=workload.splitlines()[0])
    if guard:
        collision = guard.collisions(row['START_MS'], row['END_MS'])
        if collision:
            (folder / 'concurrent-processes.json').write_text(json.dumps(collision, indent=2), encoding='utf-8')
            folder.rename(folder.with_name(folder.name + '-discarded-' + str(int(time.time() * 1000))))
            print(f'DISCARD concurrent benchmark during {case}; waiting and repeating this case', flush=True)
            return run_case(java, case, block, seconds, warmup, out, source, expected, guard)
    print(f'PASS {case} block={block} FPS={row["FPS"]:.1f} GPU_MS={row["GPU_MS"]:.4f} WORKLOAD_MATCH={expected is not None}', flush=True)
    return row


def summarize(out, records, rounds, seconds):
    telemetry = []
    telemetry_lines = []
    for file in out.glob('telemetry*.csv'):
        telemetry_lines.extend(file.read_text(encoding='utf-8').splitlines())
    for row in csv.reader(telemetry_lines):
        try:
            timestamp = dt.datetime.strptime(row[0].strip(), '%Y/%m/%d %H:%M:%S.%f').timestamp() * 1000
            telemetry.append(dict(time=timestamp, gpu=float(row[2]), memory=float(row[3]), temperature=float(row[4]), clock=float(row[5]), power=float(row[6])))
        except (ValueError, IndexError):
            pass
    summaries = []
    for case in CASES:
        rows = [r for r in records if r['case'] == case]
        if not rows:
            continue
        if len(rows) != rounds:
            raise AssertionError(f'{case}: {len(rows)} rounds instead of {rounds}')
        samples = [s for s in telemetry if any(r['START_MS'] + 1000 <= s['time'] <= r['END_MS'] for r in rows)]
        summaries.append(dict(case=case, fps=stats.median(r['FPS'] for r in rows), fps_min=min(r['FPS'] for r in rows), fps_max=max(r['FPS'] for r in rows),
                              gpu_ms=stats.median(r['GPU_MS'] for r in rows), gpu_percent=stats.mean(s['gpu'] for s in samples) if samples else None,
                              temperature_max=max(s['temperature'] for s in samples) if samples else None, clock_mean=stats.mean(s['clock'] for s in samples) if samples else None))
    by_case = {s['case']: s for s in summaries}
    pairs = []
    for variant in ['GL4.6-noGpuDriven', 'GL4.6-noIndirectCount', 'GL4.6-noSpirv']:
        ratios = []
        for block in range(1, rounds + 1):
            baseline = next(r for r in records if r['case'] == 'GL4.6' and r['block'] == block)
            disabled = next(r for r in records if r['case'] == variant and r['block'] == block)
            ratios.append((disabled['FPS'] / baseline['FPS'] - 1) * 100)
        pairs.append(dict(case=variant, paired_change_percent=stats.median(ratios), per_block_percent=ratios))
    comparisons = []
    for block in range(1, rounds + 1):
        gl44 = next(r for r in records if r['case'] == 'GL4.4' and r['block'] == block)
        gl45 = next(r for r in records if r['case'] == 'GL4.5' and r['block'] == block)
        comparisons.append((gl45['FPS'] / gl44['FPS'] - 1) * 100)
    data = dict(summary=summaries, ab=pairs, gl45_vs_gl44_per_block_percent=comparisons)
    (out / 'summary.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    expected = (out / 'expected-workload.txt').read_text(encoding='utf-8').splitlines()[0]
    original_expected = (out / 'expected-original-workload.txt').read_text(encoding='utf-8').splitlines()[0]
    lines = ['# 随机顺序、绘制量核对与 GL 4.6 单项 A/B', '',
             f'RTX 3050 Laptop，1920×1080；每项 {rounds} 个独立 JVM 轮次，每轮至少 {seconds:g} 秒；各 block 内打乱顺序。',
             '固定相机、相同 scene.bin/纹理/光照；无 Hi-Z、原版遮挡、雾、实体、HUD、屏幕呈现。',
             'GL 顺序重复与 A/B 保留上一轮单 FBO/原始场景；MATCH 组使用相同可见场景，两帧目标、不透明 RGBA8/D32 + 独立透明 RGBA8/D32，并进行两次 D32 深度复制。',
             'MATCH 组统一不透明背面剔除关闭、透明背面剔除开启、LEQUAL 深度测试/写入；原始 GL 组保留不透明背面剔除。',
             '每次预热后核对真实提交清单：不透明读取 GPU 间接命令；GL 透明读取 MDI 命令，Vulkan 透明核对实际逐 Section 提交计数。',
             '每个 Section ID 和不透明/透明四边形数都必须与所在组的预期清单完全相同，否则终止测试。', '',
             '单目标顺序复测 / A/B：`' + original_expected + '`', '', '统一通道 MATCH：`' + expected + '`']
    for matched, title in [(False, 'GL 原结构顺序复测与单项 A/B'), (True, '统一绘制内容与离屏通道后的后端对比')]:
        lines += ['', '## ' + title, '', '| 配置 | FPS 中位数 | 五轮范围 | GPU 计时 ms/帧 | GPU 占用均值 |', '| --- | ---: | ---: | ---: | ---: |']
        for s in summaries:
            if s['case'].startswith('MATCH-') != matched:
                continue
            gpu = 'n/a' if s['gpu_percent'] is None else f'{s["gpu_percent"]:.1f}%'
            lines.append(f'| {s["case"]} | {s["fps"]:.0f} | {s["fps_min"]:.0f}–{s["fps_max"]:.0f} | {s["gpu_ms"]:.3f} | {gpu} |')
    lines += ['', f'GL 4.5 相对 4.4：{sum(x > 0 for x in comparisons)}/{rounds} 轮更快；配对变化中位数 {stats.median(comparisons):+.1f}%。', '',
              '| GL 4.6 单项开关 | 相对同 block 基线的 FPS 变化中位数 |', '| --- | ---: |']
    for p in pairs:
        lines.append(f'| {p["case"]} | {p["paired_change_percent"]:+.1f}% |')
    lines += ['', 'noGpuDriven：CPU 每帧执行相同视锥测试并生成相同 compact 间接命令；MDI、Indirect Count、SPIR-V 保持开启，独立于另外两项。',
              'noIndirectCount：GPU 剔除/命令生成与 SPIR-V 保持开启，计数改为 CPU 读回，再调用普通 MDI。',
              'noSpirv：其他机制保持开启，仅改成相同 GLSL 4.60 源码，由驱动编译；必须验证实际 shader 格式。', '',
              'GPU 占用是 nvidia-smi 整卡采样；GPU 时间是各后端自身命令计时范围，可能含等待，并不表示活跃占用百分比。',
              '仍有实现差异：Vulkan 执行 GL/VK 共享信号量交接，GL 没有；Vulkan 为非索引三角形，GL 为四边形共享索引。',
              '因此只解释当前实现；即使绘制清单和通道结构匹配，也不把 FPS 比值当作纯图形 API 效率或实际 Minecraft 游戏 FPS。',
              '随机顺序及配对减轻热状态/调度偏差，不能完全消除；每轮温度、GPU 时钟与功耗保存在 telemetry.csv。', '',
              '检测到同时运行的其他 Java GPU 基准时等待；测量窗口内出现并发则丢弃该轮并重测，保留 discarded 目录及并发进程记录。',
              '原始轮次见 runs.json；schedule.json 是计划顺序，executed-order.json 是包含断点续跑后的实际时间顺序；每轮 workload.txt 为真实绘制清单，command.json 为实际启动参数。']
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps(data, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--seconds', type=float, default=6)
    parser.add_argument('--warmup', type=int, default=2000)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--invalidate', action='append', default=[])
    args = parser.parse_args()
    out = ROOT / 'build/reports/controlled-comparison'
    if args.smoke:
        out = out / 'smoke'
    out.mkdir(parents=True, exist_ok=True)
    scene = ROOT / 'build/reports/offscreen-performance/matched-scene.bin'
    expected = scene.resolve().with_name('expected-workload.txt').read_text(encoding='utf-8')
    original_scene = scene.with_name('scene.bin')
    original_expected = scene.with_name('expected-original-workload.txt').read_text(encoding='utf-8')
    (out / 'expected-workload.txt').write_text(expected, encoding='utf-8')
    (out / 'expected-original-workload.txt').write_text(original_expected, encoding='utf-8')
    (out / 'scene-sha256.txt').write_text(hashlib.sha256(scene.read_bytes()).hexdigest() + '\n', encoding='utf-8')
    rng = random.Random(20261026)
    schedule = []
    for block in range(1, args.rounds + 1):
        order = CASES.copy()
        rng.shuffle(order)
        # Counterbalance the order of the specific 4.4/4.5 comparison across blocks.
        if (order.index('GL4.5') < order.index('GL4.4')) != (block % 2 == 1):
            i, j = order.index('GL4.5'), order.index('GL4.4'); order[i], order[j] = order[j], order[i]
        schedule.append(dict(block=block, order=order))
    if args.smoke:
        schedule = [dict(block=0, order=['GL4.6', 'MATCH-VK1.4', 'MATCH-GL4.6', 'GL4.6-noGpuDriven', 'GL4.6-noIndirectCount', 'GL4.6-noSpirv'])]
    (out / 'schedule.json').write_text(json.dumps(schedule, indent=2), encoding='utf-8')
    records = []
    if args.resume and (out / 'runs.json').exists():
        records = json.loads((out / 'runs.json').read_text(encoding='utf-8'))
        for row in records:
            if row['case'] in args.invalidate:
                folder = out / f'block-{row["block"]:02d}' / row['case']
                if folder.exists(): folder.rename(folder.with_name(folder.name + '-before-raster-alignment'))
        records = [row for row in records if row['case'] not in args.invalidate]
        if (out / 'telemetry.csv').exists():
            (out / 'telemetry.csv').rename(out / ('telemetry-pass-' + str(int(time.time() * 1000)) + '.csv'))
    guard = Guard()
    with (out / 'telemetry.csv').open('w', encoding='utf-8') as log:
        monitor = subprocess.Popen(['nvidia-smi', '--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr,power.draw', '--format=csv,noheader,nounits', '--loop-ms=200'], stdout=log, stderr=subprocess.STDOUT)
        try:
            time.sleep(2)
            for block in schedule:
                for case in block['order']:
                    if any(row['block'] == block['block'] and row['case'] == case for row in records):
                        continue
                    matched = case.startswith('MATCH-')
                    records.append(run_case(args.java, case, block['block'], .2 if args.smoke else args.seconds, 100 if args.smoke else args.warmup, out,
                                            scene if matched else original_scene, expected if matched else original_expected, guard))
                    (out / 'runs.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        finally:
            monitor.terminate(); monitor.wait(timeout=10)
            guard.close()
            (out / 'other-java-benchmarks.json').write_text(json.dumps(guard.events, indent=2), encoding='utf-8')
    if not args.smoke:
        actual = sorted([dict(block=r['block'], case=r['case'], start=r['START_MS'], end=r['END_MS']) for r in records], key=lambda r:r['start'])
        (out / 'executed-order.json').write_text(json.dumps(actual, indent=2), encoding='utf-8')
        summarize(out, records, args.rounds, args.seconds)


if __name__ == '__main__':
    main()
