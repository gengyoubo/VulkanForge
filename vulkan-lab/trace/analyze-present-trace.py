"""
GPU queue timeline from a WPR trace recorded with present.wprp (no GPUView/WPA needed).

  python analyze-present-trace.py <trace.etl | tracerpt.csv> [--pid 0x4870] [--frames 3] [--timeline app-timeline.csv]

--timeline takes SwapchainBenchmark's -Dbench.timeline output (per-frame acquire/submit/present times on the same UTC
system clock) and splits each frame's cycle around the app's own Intel flip packet: submit -> flip done -> present returns ->
next acquire begins -> acquire returns -> next submit, with the render thread's wakeups inside the acquire.

An .etl is first decoded with Windows' tracerpt into %TEMP%. Uses Microsoft-Windows-DxgKrnl events:
  devices 27 (created during the trace) / 29 (rundown): process, adapter;  contexts 30/32: device, node;  engines 250;
  hardware queues 422/424 (GPUs with hardware scheduling submit to these instead of contexts);  VSync 273;  flips 259;
  queue packets 178 (command buffer; last field = progress fence) / 244 (GPU wait on a sync object) / 245 (signal)
    -> 179 ready (dependencies met) -> 180 done;
  software-scheduled GPUs: DMA packets 175 on hardware -> 176 complete;
  hardware-scheduled GPUs: 450 handed to the hardware queue -> 451 its progress fence completed.
The target defaults to the process that created devices during the trace (the benchmark, started after WPR).
"""
import collections, os, statistics, subprocess, sys, tempfile

TICK_MS = 1e-4  # tracerpt Clock-Time is in 100 ns units


def decode(path):
    if path.lower().endswith('.csv'):
        return path
    out = os.path.join(tempfile.gettempdir(), os.path.splitext(os.path.basename(path))[0] + '-dxg.csv')
    if not os.path.exists(out) or os.path.getmtime(out) < os.path.getmtime(path):
        subprocess.run(['tracerpt', path, '-o', out, '-of', 'CSV', '-y'], check=True, stdout=subprocess.DEVNULL)
    return out


def q(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] if values else float('nan')


def fmt(values):
    if not values:
        return 'n/a'
    return f'mean={statistics.mean(values):6.3f} p50={q(values, .5):6.3f} p90={q(values, .9):6.3f} p99={q(values, .99):6.3f}  (n={len(values)})'


def option(args, name, default, parse=str):
    if name not in args:
        return default
    i = args.index(name); value = parse(args[i + 1]); del args[i:i + 2]
    return value


def main():
    args = sys.argv[1:]
    forced_pid = option(args, '--pid', None, lambda v: int(v, 0))
    frames = option(args, '--frames', 3, int)
    timeline_path = option(args, '--timeline', None)
    csv_path = decode(args[0])
    app_frames, render_tid = [], None
    if timeline_path:
        with open(timeline_path) as f:
            header = f.readline()
            forced_pid = int(header.split('pid=')[1].split()[0]); render_tid = int(header.split('renderThread=')[1].split()[0])
            columns = f.readline().strip().split(',')
            app_frames = [dict(zip(columns, map(int, line.split(',')))) for line in f if line.strip()]
    wakeups, render_submits = [], []

    devices, contexts, hw_queues, engines = {}, {}, {}, {}
    created = collections.Counter()
    vsync = collections.defaultdict(list)
    queued, fence_of, ready, done, hw_start, hw_end, hwq_submit, hwq_fence = {}, {}, {}, {}, {}, {}, {}, {}
    flips = []
    with open(csv_path, encoding='utf-8-sig', errors='replace') as f:
        next(f)
        for line in f:
            if render_tid is not None and line.lstrip().startswith('Thread,') and ',ReadyThread' in line.replace(' ', ''):
                c = [x.strip() for x in line.split(',')]
                if int(c[19], 16) == render_tid: wakeups.append(int(c[16]))
                continue
            if not line.startswith('Microsoft-Windows-DxgKrnl'):
                continue
            c = [x.strip() for x in line.split(',')]
            eid, t, d = c[2], int(c[16]), c[19:]
            if render_tid is not None and eid == '178' and int(c[10], 16) == render_tid: render_submits.append(t)
            if eid in ('27', '29'):
                devices[d[3]] = (int(d[0], 16), d[1])
                if eid == '27': created[int(d[0], 16)] += 1
            elif eid in ('30', '32'):
                contexts[d[9]] = (d[0], int(d[1]))
            elif eid in ('422', '424'):
                hw_queues[d[2]] = d[0]
            elif eid == '250':
                engines[(d[0], int(d[1]))] = d[2].strip('" ').replace('DXGK_ENGINE_TYPE_', '')
            elif eid == '273':
                vsync[d[0]].append(t)
            elif eid == '178':
                queued[(d[0], d[2])] = (t, d[1].strip('" ').replace('DXGKETW_', '').replace('_COMMAND_BUFFER', ''))
                if len(d) > 9 and d[9] not in ('0', ''): fence_of[(d[0], d[2])] = d[9]
            elif eid == '244':
                queued[(d[0], d[1])] = (t, 'WAIT')
            elif eid == '245':
                queued[(d[0], d[1])] = (t, 'SIGNAL')
            elif eid == '179':
                ready[(d[0], d[2])] = t
            elif eid == '180':
                done[(d[0], d[2])] = t
            elif eid == '175':
                hw_start[(d[0], d[4])] = t
            elif eid == '176':
                hw_end[(d[0], d[3])] = t
            elif eid == '450':
                hwq_submit[(d[0], d[1])] = t
            elif eid == '451':
                hwq_fence[(d[0], d[1])] = t
            elif eid == '259':
                flips.append((t, d[0], int(d[7]), d[4], int(d[2])))  # time, adapter, present id, flipped-to allocation, plane

    # Hardware-scheduled packets: map their progress fence to the 450/451 pair, so both schedulers expose start/end times.
    for key, fence in fence_of.items():
        if key not in hw_start and (key[0], fence) in hwq_submit: hw_start[key] = hwq_submit[(key[0], fence)]
        if key not in hw_end and (key[0], fence) in hwq_fence: hw_end[key] = hwq_fence[(key[0], fence)]

    def info(ctx):
        context = contexts.get(ctx) or contexts.get(hw_queues.get(ctx))
        if not context: return None, None, None
        pid, adapter = devices.get(context[0], (None, None))
        return pid, adapter, context[1]

    display = max(vsync, key=lambda a: len(vsync[a])) if vsync else None
    per_pid = collections.Counter(info(k[0])[0] for k in queued)
    if forced_pid is not None: target = forced_pid
    elif created: target = max(created, key=lambda p: per_pid.get(p, 0))
    else: target = max((p for p in per_pid if p is not None), key=per_pid.get)
    gpu = lambda a: 'iGPU' if a == display else 'dGPU'
    print(f'trace: {csv_path}')
    print(f'target pid: {hex(target)}  display adapter: {display}')
    vs = vsync.get(display, [])
    if len(vs) > 2:
        print(f'vsync interval ms: {fmt([(b - a) * TICK_MS for a, b in zip(vs, vs[1:])])}')

    times = sorted(t for k, (t, _) in queued.items() if info(k[0])[0] == target)
    if not times:
        sys.exit('no queue packets for the target process')
    lo, hi = times[int(len(times) * .2)], times[int(len(times) * .8)]  # Steady state: middle 60% of the target's activity.
    span_ms = (hi - lo) * TICK_MS
    print(f'steady window: {span_ms:.0f} ms\n')

    groups = collections.defaultdict(list)
    for key, (t, kind) in queued.items():
        if lo <= t <= hi: groups[key[0]].append((t, kind, key))
    rows = []
    for ctx, packets in groups.items():
        pid, adapter, node = info(ctx)
        rate = len(packets) * 1000 / span_ms
        if pid != target and rate < 50: continue  # Quiet contexts of other processes.
        packets.sort()
        kinds = collections.Counter(k for _, k, _ in packets)
        dur = lambda a, b: [(b[k] - a(t, k)) * TICK_MS for t, _, k in packets if k in b and a(t, k) is not None and b[k] >= a(t, k)]
        submitted = lambda t, k: t
        wait_ready = dur(submitted, ready)
        ready_to_hw = dur(lambda t, k: ready.get(k), hw_start)
        on_hw = dur(lambda t, k: hw_start.get(k), hw_end)
        total = dur(submitted, done)
        starts = sorted(hw_start[k] for _, _, k in packets if k in hw_start)
        cadence = [(b - a) * TICK_MS for a, b in zip(starts, starts[1:])]
        who = 'target' if pid == target else (hex(pid) if pid is not None else '?')
        rows.append(dict(who=who, gpu=gpu(adapter), engine=engines.get((adapter, node), f'node{node}'), ctx=ctx, rate=rate, kinds=dict(kinds),
                         wait_ready=wait_ready, ready_to_hw=ready_to_hw, on_hw=on_hw, total=total, cadence=cadence,
                         busy=sum(on_hw) / span_ms * 100))
    rows.sort(key=lambda r: (r['who'] != 'target', r['gpu'], -r['rate']))
    for r in rows:
        print(f"[{r['who']}] {r['gpu']} {r['engine']} ctx={r['ctx']}  {r['rate']:.0f} packets/s  on-GPU={r['busy']:.0f}%  {r['kinds']}")
        print(f"    submitted->ready (deps met)  {fmt(r['wait_ready'])}")
        print(f"    ready->started on GPU        {fmt(r['ready_to_hw'])}")
        print(f"    started->completed           {fmt(r['on_hw'])}")
        print(f"    submitted->done              {fmt(r['total'])}")
        print(f"    start-to-start cadence       {fmt(r['cadence'])}")
    disp_flips = [t for t, a, *_ in flips if a == display and lo <= t <= hi]
    if len(disp_flips) > 2:
        print(f'\nflips on display adapter: {len(disp_flips) * 1000 / span_ms:.0f}/s interval {fmt([(b - a) * TICK_MS for a, b in zip(disp_flips, disp_flips[1:])])}')

    # A few steady-state frames: every packet state change of the target's contexts plus display flips/vsyncs.
    first = times[len(times) // 2]
    end = first + int(4.2 * frames / TICK_MS)
    label = {r['ctx']: f"{r['who'][:6]}:{r['gpu']}:{r['engine'][:6]}:{r['ctx'][-4:]}" for r in rows if r['who'] == 'target'}
    events = []
    for key, (t, kind) in queued.items():
        if key[0] not in label or not first <= t <= end: continue
        name = label[key[0]]
        events.append((t, name, f'submit {kind} #{key[1]}'))
        for table, what in ((ready, 'ready'), (hw_start, 'GPU start'), (hw_end, 'GPU done'), (done, 'retired')):
            if key in table and table[key] <= end: events.append((table[key], name, f'    {what} #{key[1]}'))
    events += [(t, 'display', f'FLIP -> {alloc[-5:]}') for t, a, _, alloc, _ in flips if a == display and first <= t <= end]
    events += [(t, 'display', 'vsync') for t in vs if first <= t <= end]
    print(f'\ntimeline ({frames} frames, ms):')
    for t, who, what in sorted(events):
        print(f'  {(t - first) * TICK_MS:8.3f}  {who:28s} {what}')
    if app_frames:
        breakdown(app_frames, render_submits, wakeups, rows, queued, done, ready, sorted((t, alloc, plane) for t, a, _, alloc, plane in flips if a == display))


def breakdown(app_frames, render_submits, wakeups, rows, queued, done, ready, display_flips):
    import bisect
    render_submits.sort(); wakeups.sort()
    # Clock check: the render thread's own DxgKrnl submit must fall inside the app's vkQueueSubmit2 window.
    inside, lead = 0, []
    for fr in app_frames:
        i = bisect.bisect_left(render_submits, fr['submitBegin'])
        if i < len(render_submits) and render_submits[i] <= fr['submitEnd']:
            inside += 1; lead.append((render_submits[i] - fr['submitBegin']) * TICK_MS)
    print(f'\napp timeline: {len(app_frames)} frames; render-thread DxgKrnl submit inside vkQueueSubmit2 for {inside} '
          f'({100 * inside / len(app_frames):.0f}%), submitBegin->kernel submit {fmt(lead)}')
    flip_ctxs = [r['ctx'] for r in rows if r['who'] == 'target' and r['gpu'] == 'iGPU' and 'SOFTWARE' in r['kinds']]
    if not flip_ctxs:
        print('no Intel flip packets for the target'); return
    flips = sorted(done[k] for k, (t, kind) in queued.items() if k[0] in flip_ctxs and kind == 'SOFTWARE' and k in done)
    flip_ready = sorted(ready[k] for k, (t, kind) in queued.items() if k[0] in flip_ctxs and kind == 'SOFTWARE' and k in ready)
    parts = collections.defaultdict(list); wake_counts = collections.Counter(); last_wake = []; before_present_end = 0; n = 0
    for fr, nxt in zip(app_frames, app_frames[1:]):
        i = bisect.bisect_right(flips, fr['submitBegin'])
        if i >= len(flips) or flips[i] > nxt['submitBegin']: continue  # This frame's flip packet must retire before the next submit.
        flip = flips[i]; n += 1
        ms = lambda a, b: (b - a) * TICK_MS
        parts['A submit -> Intel flip packet done'].append(ms(fr['submitBegin'], flip))
        parts['B flip done -> vkQueuePresentKHR returns'].append(ms(flip, fr['presentEnd']))
        parts['C present returns -> next acquire begins'].append(ms(fr['presentEnd'], nxt['acquireBegin']))
        parts['D vkAcquireNextImageKHR'].append(ms(nxt['acquireBegin'], nxt['acquireEnd']))
        parts['E acquire returns -> next vkQueueSubmit2'].append(ms(nxt['acquireEnd'], nxt['submitBegin']))
        parts['  flip done -> next submit (B+C+D+E)'].append(ms(flip, nxt['submitBegin']))
        parts['  flip done -> acquire returns'].append(ms(flip, nxt['acquireEnd']))
        if flip <= fr['presentEnd']: before_present_end += 1
        lo, hi = bisect.bisect_left(wakeups, nxt['acquireBegin']), bisect.bisect_right(wakeups, nxt['acquireEnd'])
        wake_counts[hi - lo] += 1
        if hi > lo:
            parts['  last wakeup in acquire -> acquire returns'].append(ms(wakeups[hi - 1], nxt['acquireEnd']))
            parts['  acquire begins -> last wakeup in it'].append(ms(nxt['acquireBegin'], wakeups[hi - 1]))
    print(f'frames paired with their flip packet: {n}; flip retired before vkQueuePresentKHR returned in {100 * before_present_end / max(n, 1):.0f}%')
    for name, values in parts.items():
        print(f'  {name:44s} {fmt(values)}')
    print('  render-thread wakeups inside acquire: ' + ', '.join(f'{k}x: {v}' for k, v in sorted(wake_counts.items())))
    lifecycle(app_frames, flips, display_flips)


def lifecycle(app_frames, flip_packets, display_flips):
    """
    Per swapchain image: acquired -> submitted -> presented -> its cross-adapter copy done (the app's Intel flip packet retires
    once the copy has signalled) -> flipped to the screen (DxgKrnl 259, FlipToDriverAllocation) -> released when the next flip on
    that plane shows another allocation -> acquired again. Then: when an acquire returns image j, how long had image j already
    been free, by either definition?
    """
    import bisect
    flip_times = [t for t, _, _ in display_flips]
    ms = lambda a, b: (b - a) * TICK_MS
    life = []  # per app frame: dict with image, packet, flip, alloc, released
    for n, fr in enumerate(app_frames):
        nxt = app_frames[n + 1]['submitBegin'] if n + 1 < len(app_frames) else fr['submitBegin'] + 10 ** 6
        i = bisect.bisect_right(flip_packets, fr['submitBegin'])
        if i >= len(flip_packets) or flip_packets[i] > nxt: life.append(None); continue
        packet = flip_packets[i]
        k = bisect.bisect_left(flip_times, packet - 5000)  # the screen flip is programmed just before the packet retires
        near = [f for f in display_flips[k:bisect.bisect_right(flip_times, packet + 3000)]]
        if not near: life.append(None); continue
        t, alloc, plane = min(near, key=lambda f: abs(f[0] - packet))
        later = [f for f in display_flips[bisect.bisect_right(flip_times, t):bisect.bisect_right(flip_times, t + 10 ** 6)] if f[2] == plane and f[1] != alloc]
        life.append(dict(image=fr['image'], packet=packet, flip=t, alloc=alloc, released=later[0][0] if later else None))
    pairs = collections.Counter((l['image'], l['alloc'][-6:]) for l in life if l)
    print('\nswapchain image -> flipped Intel-side allocation (frames):  ' + ', '.join(f'img{i}->{a}: {c}' for (i, a), c in sorted(pairs.items()) if c > 5))
    order = collections.Counter(tuple(fr['image'] for fr in app_frames[n:n + 4]) for n in range(0, len(app_frames) - 4))
    print('most common image order (4 frames): ' + ', '.join(f'{k}: {v}' for k, v in order.most_common(3)))
    last = {}; reuse = collections.Counter(); parts = collections.defaultdict(list)
    for n, fr in enumerate(app_frames):
        j = fr['image']
        if j in last and life[last[j]]:
            m = last[j]; prev = life[m]; reuse[n - m] += 1
            parts['previous use: copy done (Intel flip packet) -> acquire BEGIN'].append(ms(prev['packet'], fr['acquireBegin']))
            parts['previous use: copy done -> acquire RETURNS'].append(ms(prev['packet'], fr['acquireEnd']))
            if prev['released']:
                parts['previous use: Intel buffer released -> acquire BEGIN'].append(ms(prev['released'], fr['acquireBegin']))
                parts['previous use: Intel buffer released -> acquire RETURNS'].append(ms(prev['released'], fr['acquireEnd']))
        if n > 0 and life[n - 1]:
            parts['last frame flipped -> acquire RETURNS'].append(ms(life[n - 1]['flip'], fr['acquireEnd']))
        # The Intel-side buffer this frame's copy writes into: when was it last released by the display?
        if life[n]:
            p = next((q for q in range(n - 1, max(n - 8, -1), -1) if life[q] and life[q]['alloc'] == life[n]['alloc']), None)
            if p is not None and life[p]['released']:
                parts["this frame's Intel buffer released -> acquire BEGIN"].append(ms(life[p]['released'], fr['acquireBegin']))
        last[j] = n
    print('frames between two uses of the same image: ' + ', '.join(f'{k}: {v}' for k, v in sorted(reuse.items())))
    for name, values in parts.items():
        print(f'  {name:58s} {fmt(values)}')


if __name__ == '__main__':
    main()
