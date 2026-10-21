"""Rebuild a dev-named source file from the remapped snapshot in build/libs.

The file was truncated to nothing by a bad patch script; the pre-patch content is in the sources jar, but
that jar went through the deploy remap, so every vanilla type and member in it is an intermediary name
(class_2561, method_43471). This converts them back using loom's own layered mappings.

The tiny v2 payload is read by column name from the header, and member rows are matched on whichever field
looks like the intermediary name and take the field after it as the named one -- the file's member rows are
indented inside a class block and omit the owner, so their column count is not fixed.
"""
import io
import re
import sys
import zipfile

TINY = ('C:/Users/BigMer/.gradle/caches/fabric-loom/1.21.1/'
        'loom.mappings.1_21_1.layered+hash.2198-v2/mappings.tiny')
JAR = 'build/libs/myavalon-fabric-1.21.1-1.0.0-sources.jar'
TARGET = sys.argv[1] if len(sys.argv) > 1 else 'dev/myavalon/game/AvalonGame.java'
OUT = 'src/main/java/' + TARGET

classes = {}
members = {}
missing = []
INTERMEDIARY_NAME = re.compile(r'^(?:method|field|comp)_\d+$')

with io.open(TINY, encoding='utf-8') as handle:
    header = handle.readline().rstrip('\n').split('\t')
    inter = header.index('intermediary') - 3
    named = header.index('named') - 3
    for line in handle:
        parts = line.rstrip('\n').split('\t')
        kind = next((field.strip() for field in parts[:2] if field.strip() in ('c', 'm', 'f')), '')
        if kind == 'c' and len(parts) >= 3:
            payload = parts[1:]
            if len(payload) > max(inter, named):
                classes[payload[inter]] = payload[named]
        elif kind in ('m', 'f'):
            for i, field in enumerate(parts):
                if INTERMEDIARY_NAME.match(field) and i + 1 < len(parts):
                    members.setdefault(field, parts[i + 1])
                    break

source = zipfile.ZipFile(JAR).read(TARGET).decode('utf-8')


def swap_dotted(match):
    named = classes.get('net/minecraft/' + match.group(1))
    if named is None:
        missing.append(match.group(0))
        return match.group(0)
    return named.replace('/', '.')


def swap_bare(match):
    named = classes.get('net/minecraft/' + match.group(0))
    if named is None:
        missing.append(match.group(0))
        return match.group(0)
    return named.replace('/', '.')


def swap_member(match):
    named = members.get(match.group(0))
    if named is None:
        missing.append(match.group(0))
        return match.group(0)
    return named


source = re.sub(r'net\.minecraft\.(class_\d+)', swap_dotted, source)
source = re.sub(r'\bclass_\d+\b', swap_bare, source)
source = re.sub(r'\b(?:method|field|comp)_\d+\b', swap_member, source)

io.open(OUT, 'w', encoding='utf-8', newline='\n').write(source)
print('wrote', OUT, len(source.split('\n')), 'lines;', len(classes), 'classes,', len(members), 'members')
if missing:
    print('unmapped:', sorted(set(missing))[:25])
