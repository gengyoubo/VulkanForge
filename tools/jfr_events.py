"""Read JFR events with complete stack traces, one event at a time.

`jfr print` keeps only 5 frames per stack unless --stack-depth is given, which hides every frame
below the top of the stack. Full-depth JSON reaches several GB, so it is streamed instead of loaded.
"""
import json
import os
from pathlib import Path
import subprocess

STACK_DEPTH = '2048'  # Above any recording's own limit; JFR's default recording depth is 64.
_EVENTS_START = b'    "events": [{'
_EVENT_BREAK = b'    }, {'
_EVENTS_END = b'    }]'


def _jfr_tool():
    home = os.environ.get('JAVA_HOME')
    return str(Path(home) / 'bin' / ('jfr.exe' if os.name == 'nt' else 'jfr')) if home else 'jfr'


def iter_events(recording, *event_types):
    """Yield decoded events, relying on the fixed indentation of `jfr print --json` between events."""
    command = [_jfr_tool(), 'print', '--json', '--stack-depth', STACK_DEPTH,
               '--events', ','.join(event_types), str(recording)]
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        lines = None
        for line in process.stdout:
            stripped = line.rstrip()
            if stripped == _EVENTS_START:
                lines = [b'{']
            elif lines is None:
                continue
            elif stripped == _EVENT_BREAK or stripped == _EVENTS_END:
                lines.append(b'}')
                yield json.loads(b''.join(lines).decode('utf-8', 'replace'))
                lines = [b'{'] if stripped == _EVENT_BREAK else None
            else:
                lines.append(line)
    if process.returncode:
        raise subprocess.CalledProcessError(process.returncode, command)


def frame_names(stack):
    """Return `type.method` names, leaf first. Hidden classes print dotted, so normalize to slashes."""
    return [frame['method']['type']['name'].replace('.', '/') + '.' + frame['method']['name']
            for frame in stack['frames']]
