#!/usr/bin/env python3
"""Source-only guards. No Gradle, network, credentials or Android runtime required."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
app = root / 'app/src/main'
ui = app / 'java/app/trove/debrid/ui'
resources = set()
for path in app.glob('res/**/*.xml'):
    tree = ET.parse(path)
    if path.name == 'strings.xml':
        names = [node.attrib['name'] for node in tree.getroot()]
        assert len(names) == len(set(names)), f'Duplicate resource in {path}'
        resources.update(names)
for path in (app / 'java').rglob('*.kt'):
    source = path.read_text()
    imports = re.findall(r'^import .+$', source, re.M)
    assert len(imports) == len(set(imports)), f'Duplicate import: {path}'
    for name in re.findall(r'R\.string\.(\w+)', source):
        assert name in resources, f'Missing string {name} in {path}'
for path in ui.rglob('*.kt'):
    if path.name == 'Theme.kt':
        continue
    assert not re.search(r'Color\(0x|Color\.(White|Black|Red|Blue|Green)', path.read_text()), path

activity = (app / 'java/app/trove/debrid/MainActivity.kt').read_text()
playback = re.split(r'private (?:suspend )?fun playUrl\(', activity)[1].split('private class SimpleFactory')[0]
for required in ['Intent(Intent.ACTION_VIEW)', 'playbackMimeType(fileName)', 'Intent.createChooser']:
    assert required in playback
for forbidden in ['ACTION_SEND', 'EXTRA_TEXT', 'putExtra', 'Log.', 'Timber', 'println']:
    assert forbidden not in playback
provider = (root / 'core/src/main/java/app/trove/debrid/core/TorBoxProvider.kt').read_text()
assert '/torrents/asynccreatetorrent' not in provider
assert provider.count('"POST", "/torrents/createtorrent"') == 2
for path in ['torrents', 'webdl', 'usenet']:
    assert f'"/{path}/requestdl"' in provider
assert '"file_id" to fileId' in provider
assert '/stream/' not in provider
pill = (ui / 'downloads/CountPill.kt').read_text()
assert 'wrapContentWidth(unbounded = true)' in pill and 'requiredWidth(IntrinsicSize.Min)' not in pill and 'softWrap = false' in pill and 'maxLines = 1' in pill
screen = (ui / 'downloads/DownloadsScreen.kt').read_text()
assert 'HorizontalPager(' in screen and screen.count('rememberLazyListState()') == 3
assert screen.index('detailsSheetState.hide()') < screen.index('onPlayUrl(it, state.playFileName')
assert 'withFrameNanos' in screen
sheet = (ui / 'downloads/DownloadDetailsSheet.kt').read_text()
assert sheet.count('LazyColumn(') == 1 and 'verticalScroll(' not in sheet
assert 'baseFileName(file.name)' in sheet and 'onPlay(file.id)' in sheet
assert 'libs.compose.foundation' in (root / 'app/build.gradle.kts').read_text()
files = list((root / 'app/src/test').rglob('*.kt')) + list((root / 'core/src/test').rglob('*.kt'))
print(f'Static guards passed: resources, imports, semantic colors, playback privacy, torrent creation, pager and sheet.')
print(f'Test inventory: {len(files)} files, {sum(p.read_text().count("@Test") for p in files)} tests (not executed).')
