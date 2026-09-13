#!/usr/bin/env python3
import gzip
import json
import sys
from pathlib import Path

from google.protobuf import json_format

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2  # noqa: E402

VERSION_NAME = "1.6.12"
VERSION_CODE = 12
PACKAGE = "eu.kanade.tachiyomi.extension.en.mangafire"
SOURCE_ID = 6084907896154116083
APK_URL = "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/apk/tachiyomi-en.mangafire-v1.6.12.apk"
JAR_URL = "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/jar/tachiyomi-en.mangafire-v1.6.12.jar"
SIGNING_KEY = sys.argv[1] if len(sys.argv) > 1 else ""

ext = index_pb2.Extension(
    name="MangaFire",
    packageName=PACKAGE,
    resources=index_pb2.Resources(
        apkUrl=APK_URL,
        jarUrl=JAR_URL,
        iconUrl="https://raw.githubusercontent.com/WavyKisuke/extensions-source/main/src/en/mangafire/res/mipmap-xhdpi/ic_launcher.png",
    ),
    extensionLib="1.6",
    versionCode=VERSION_CODE,
    versionName=VERSION_NAME,
    contentWarning=index_pb2.CONTENT_WARNING_MIXED,
    sources=[
        index_pb2.Source(
            id=SOURCE_ID,
            name="MangaFire",
            language="en",
            homeUrl="https://mangafire.to",
        )
    ],
)

index = index_pb2.Index(
    name="WavyKisuke MangaFire",
    badgeLabel="MANGA",
    signingKey=SIGNING_KEY,
    contact=index_pb2.Contact(website="https://github.com/WavyKisuke/extensions-source"),
    extensionList=index_pb2.ExtensionList(extensions=[ext]),
)

repo_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(".")
repo_dir.mkdir(parents=True, exist_ok=True)

with (repo_dir / "index.json").open("w", encoding="utf-8") as f:
    f.write(json_format.MessageToJson(index, preserving_proto_field_name=True, always_print_fields_with_no_presence=False))
    f.write("\n")

with (repo_dir / "index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

with (repo_dir / "repo.json").open("w", encoding="utf-8") as f:
    json.dump(
        {
            "index_v2": "https://raw.githubusercontent.com/WavyKisuke/extensions-source/repo/index.pb",
            "meta": {
                "name": "WavyKisuke MangaFire",
                "shortName": "MangaFire",
                "website": "https://github.com/WavyKisuke/extensions-source",
                "signingKeyFingerprint": SIGNING_KEY,
            },
        },
        f,
        indent=2,
    )
    f.write("\n")

with (repo_dir / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(
        [
            {
                "name": "MangaFire",
                "pkg": PACKAGE,
                "apk": APK_URL,
                "lang": "en",
                "code": VERSION_CODE,
                "version": VERSION_NAME,
                "nsfw": 1,
                "sources": [
                    {
                        "name": "MangaFire",
                        "lang": "en",
                        "id": str(SOURCE_ID),
                        "versionId": 1,
                        "baseUrl": "https://mangafire.to",
                    }
                ],
            }
        ],
        f,
        separators=(",", ":"),
    )
    f.write("\n")
