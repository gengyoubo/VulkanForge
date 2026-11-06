"""Count saved block entities by type in a world directory. Read-only; safe to run on a copy or a live save."""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path
import struct
import zlib

SIZES = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
SECTOR = 4096
ZLIB = 2


class Nbt:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def _unpack(self, fmt):
        value = struct.unpack_from(fmt, self.data, self.pos)[0]
        self.pos += struct.calcsize(fmt)
        return value

    def _string(self):
        size = self._unpack('>H')
        self.pos += size
        return self.data[self.pos - size:self.pos].decode('utf-8', 'replace')

    def _value(self, kind):
        """Decode strings, lists, compounds and ints; other payloads are skipped as this tool never reads them."""
        if kind == 3:
            return self._unpack('>i')
        if kind in SIZES:
            self.pos += SIZES[kind]
        elif kind == 7:
            size = self._unpack('>i')
            self.pos += size
        elif kind == 8:
            return self._string()
        elif kind == 9:
            item = self.data[self.pos]
            self.pos += 1
            return [self._value(item) for _ in range(self._unpack('>i'))]
        elif kind == 10:
            result = {}
            while (item := self.data[self.pos]) != 0:
                self.pos += 1
                name = self._string()
                result[name] = self._value(item)
            self.pos += 1
            return result
        elif kind in (11, 12):
            size = self._unpack('>i') * (4 if kind == 11 else 8)
            self.pos += size
        else:
            raise ValueError(f'unknown NBT tag {kind}')
        return None

    def root(self):
        kind = self.data[0]
        self.pos = 1
        self._string()
        return self._value(kind)


def chunks(region):
    data = region.read_bytes()
    for index in range(1024 if len(data) >= 2 * SECTOR else 0):
        offset = int.from_bytes(data[4 * index:4 * index + 3], 'big') * SECTOR
        if offset and data[offset + 4] == ZLIB:
            size = struct.unpack_from('>i', data, offset)[0]
            yield zlib.decompress(data[offset + 5:offset + 4 + size])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('world', type=Path)
    args = parser.parse_args()
    if not (args.world / 'level.dat').is_file():
        parser.error('not a world directory: level.dat is missing')
    player = Nbt(gzip.decompress((args.world / 'level.dat').read_bytes())).root()['Data'].get('Player', {})
    dimensions = {}
    for directory in sorted(args.world.rglob('region')):
        counts, chunk_count = Counter(), 0
        for region in sorted(directory.glob('*.mca')):
            for raw in chunks(region):
                chunk_count += 1
                # Most chunks have no entries, so avoid decoding them in Python.
                if b'block_entities' in raw:
                    counts.update(entry.get('id', '?') for entry in Nbt(raw).root().get('block_entities') or [])
        dimensions[directory.parent.relative_to(args.world).as_posix()] = {
            'chunks': chunk_count, 'block_entities': sum(counts.values()), 'by_type': dict(counts.most_common())}
    print(json.dumps({'note': 'Saved chunks at scan time, not the chunks loaded or ticking during a recording.',
                      'player_dimension': player.get('Dimension'), 'dimensions': dimensions}, indent=2))


if __name__ == '__main__':
    main()
