"""Read-only Windows Java benchmark detection; never stops another process."""
import ctypes as c
from ctypes import wintypes as w
import json
import re
import subprocess
import threading
import time


def java_pids():
    class Entry(c.Structure):
        _fields_ = [('size', w.DWORD), ('usage', w.DWORD), ('pid', w.DWORD), ('heap', c.c_size_t), ('module', w.DWORD),
                    ('threads', w.DWORD), ('parent', w.DWORD), ('priority', w.LONG), ('flags', w.DWORD), ('name', w.WCHAR * 260)]
    kernel = c.WinDLL('kernel32', use_last_error=True)
    kernel.CreateToolhelp32Snapshot.restype = w.HANDLE
    kernel.Process32FirstW.argtypes = [w.HANDLE, c.POINTER(Entry)]
    kernel.Process32NextW.argtypes = [w.HANDLE, c.POINTER(Entry)]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.CreateToolhelp32Snapshot(2, 0)
    if handle == c.c_void_p(-1).value:
        raise OSError(c.get_last_error())
    result = set()
    try:
        entry = Entry(); entry.size = c.sizeof(entry)
        more = kernel.Process32FirstW(handle, c.byref(entry))
        while more:
            if entry.name.lower() in ('java.exe', 'javaw.exe'):
                result.add(entry.pid)
            more = kernel.Process32NextW(handle, c.byref(entry))
    finally:
        kernel.CloseHandle(handle)
    return result


class Guard:
    def __init__(self):
        self.own = None
        self.kinds = {}
        self.events = []
        self.stop = threading.Event()
        self.error = None
        self.thread = threading.Thread(target=self._monitor, daemon=True)
        self.thread.start()

    def _monitor(self):
        try:
            while not self.stop.is_set():
                pids = java_pids()
                if pids - self.kinds.keys():
                    script = "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | ForEach-Object { [pscustomobject]@{pid=$_.ProcessId;kind=([regex]::Match($_.CommandLine,'(?:[A-Za-z_][A-Za-z_0-9]*\\.)*(?:[A-Za-z_0-9]*Benchmark|[A-Za-z_0-9]*Smoke|net\\.minecraft\\.client\\.main\\.Main)\\b')).Value} } | ConvertTo-Json -Compress"
                    response = subprocess.run(['powershell.exe', '-NoProfile', '-Command', script], capture_output=True, text=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
                    if response.returncode:
                        raise RuntimeError('Could not inspect concurrent Java benchmarks')
                    rows = json.loads(response.stdout or '[]')
                    if isinstance(rows, dict): rows = [rows]
                    for row in rows: self.kinds[int(row['pid'])] = row['kind']
                    # A process can exit between the snapshot and the WMI read.
                    for pid in pids: self.kinds.setdefault(pid, '')
                now = time.time() * 1000
                for pid in pids:
                    kind = self.kinds.get(pid, '')
                    if kind and pid != self.own:
                        self.events.append(dict(time=now, pid=pid, kind=kind))
                self.stop.wait(.25)
        except BaseException as error:
            self.error = error

    def idle(self):
        # Wait for initial process classification, then require a short quiet interval.
        started = time.monotonic(); announced = False
        while True:
            if self.error: raise RuntimeError('Concurrency guard failed') from self.error
            pending = java_pids() - self.kinds.keys()
            busy = [p for p in java_pids() if self.kinds.get(p, '') and p != self.own]
            if not pending and not busy:
                return
            if not announced:
                print('WAITING for other Java GPU benchmarks to finish', flush=True); announced = True
            if time.monotonic() - started > 600:
                raise TimeoutError('Another Java GPU benchmark remained active for ten minutes')
            time.sleep(1)

    def collisions(self, start_ms, end_ms):
        if self.error: raise RuntimeError('Concurrency guard failed') from self.error
        return [e for e in self.events if start_ms <= e['time'] <= end_ms]

    def close(self):
        self.stop.set(); self.thread.join(timeout=25)
