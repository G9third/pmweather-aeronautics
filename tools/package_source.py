"""Create the public source ZIP and refresh its SHA-256 manifest. Never builds."""
from pathlib import Path
import datetime
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
fixed_files = {
    '.gitignore', 'LICENSE', 'README.md', 'build.gradle', 'settings.gradle', 'gradle.properties',
    'gradlew', 'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
    'libs/DEPENDENCIES.json', 'libs/README.md',
    'docs/BUILD-SUMMARY.md', 'docs/EXTERNAL-API.md', 'docs/RELEASE-1.0.md',
    'docs/WHEEL-CONTACT-API.md',
    'tools/fetch_compile_dependencies.py', 'tools/package_source.py',
    'src/test/java/com/axes/pmweather_aeronautics/ExternalApiChecks.java',
}
paths = {root / name for name in fixed_files}
paths.update(path for path in (root / 'src/main').rglob('*') if path.is_file())
missing = sorted(str(path.relative_to(root)) for path in paths if not path.is_file())
if missing:
    raise SystemExit('Missing public source files: ' + ', '.join(missing))
paths = sorted(paths)
manifest = {
    'version': '1.0',
    'project': 'PMWeather Aeronautics',
    'generated_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'verification': 'This manifest records source-file hashes and package integrity, not compilation or runtime behavior. See docs/BUILD-SUMMARY.md for build status.',
    'distribution': 'Public source and build files; compile-only dependency binaries are omitted.',
    'sha256': {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
}
(root/'SOURCE-MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
output = root.parent / (root.name+'.zip')
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for p in paths + [root/'SOURCE-MANIFEST.json']:
        archive.write(p, root.name+'/'+p.relative_to(root).as_posix())
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    stored = json.loads(archive.read(root.name+'/SOURCE-MANIFEST.json'))
    for name, digest in stored['sha256'].items():
        assert hashlib.sha256(archive.read(root.name+'/'+name)).hexdigest() == digest, name
print(output)
print(f'{output.stat().st_size:,} bytes; archive integrity and all manifest hashes verified')
