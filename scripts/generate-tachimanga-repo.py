#!/usr/bin/env python3
import gzip
import json
import sys
from pathlib import Path

from google.protobuf import json_format

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2  # noqa: E402

SIGNING_KEY = sys.argv[1] if len(sys.argv) > 1 else ""
repo_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(".")
repo_dir.mkdir(parents=True, exist_ok=True)

MANGA_FIRE = {
    "name": "MangaFire",
    "package": "eu.kanade.tachiyomi.extension.en.mangafire",
    "source_id": 6084907896154116083,
    "version_name": "1.6.15",
    "version_code": 15,
    "apk_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/apk/tachiyomi-en.mangafire-v1.6.15.apk",
    "jar_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/jar/tachiyomi-en.mangafire-v1.6.15.jar",
    "icon_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/main/src/en/mangafire/res/mipmap-xhdpi/ic_launcher.png",
    "base_url": "https://mangafire.to",
    "warning": index_pb2.CONTENT_WARNING_MIXED,
}

BATCAVE = {
    "name": "BatCave",
    "package": "eu.kanade.tachiyomi.extension.en.batcave2",
    "source_id": 7422099479605463706,
    "version_name": "1.6.19",
    "version_code": 19,
    "apk_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/apk/tachiyomi-en.batcave2-v1.6.19.apk",
    "jar_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/jar/tachiyomi-en.batcave2-v1.6.19.jar",
    "icon_url": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/main/src/en/batcave2/res/mipmap-xhdpi/ic_launcher.png",
    "base_url": "https://batcave.biz",
    "warning": index_pb2.CONTENT_WARNING_SAFE,
}

EXTENSIONS = [MANGA_FIRE, BATCAVE]

protobuf_extensions = []
legacy_extensions = []
for item in EXTENSIONS:
    protobuf_extensions.append(
        index_pb2.Extension(
            name=item["name"], packageName=item["package"],
            resources=index_pb2.Resources(apkUrl=item["apk_url"], jarUrl=item["jar_url"], iconUrl=item["icon_url"]),
            extensionLib="1.6", versionCode=item["version_code"], versionName=item["version_name"],
            contentWarning=item["warning"],
            sources=[index_pb2.Source(id=item["source_id"], name=item["name"], language="en", homeUrl=item["base_url"])],
        )
    )
    legacy_extensions.append({
        "name": item["name"], "pkg": item["package"], "lang": "en", "code": item["version_code"],
        "version": item["version_name"], "apk": item["apk_url"], "jar": item["jar_url"], "nsfw": False,
        "sources": [{"id": str(item["source_id"]), "name": item["name"], "lang": "en", "baseUrl": item["base_url"]}],
    })

index = index_pb2.Index(
    name="WavyKisuke Extensions", badgeLabel="MANGA", signingKey=SIGNING_KEY,
    contact=index_pb2.Contact(website="https://github.com/WavyKisuke/extensions-source"),
    extensionList=index_pb2.ExtensionList(extensions=protobuf_extensions),
)

with (repo_dir / "index.json").open("w", encoding="utf-8") as f:
    f.write(json_format.MessageToJson(index, preserving_proto_field_name=True, always_print_fields_with_no_presence=False))
    f.write("\n")
with (repo_dir / "index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))
with (repo_dir / "repo.json").open("w", encoding="utf-8") as f:
    json.dump({
        "index_v2": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/index.pb",
        "meta": {
            "name": "WavyKisuke Extensions", "shortName": "WavyKisuke",
            "website": "https://github.com/WavyKisuke/extensions-source", "signingKeyFingerprint": SIGNING_KEY,
        },
    }, f, indent=2)
    f.write("\n")
with (repo_dir / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_extensions, f, indent=2)
    f.write("\n")
