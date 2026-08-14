#!/usr/bin/env python3
"""Validate Meshify media packs and rebuild the compact root catalog."""
import hashlib, json, mimetypes, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK_ID = re.compile(r"^[a-z0-9_-]{1,32}$")
ITEM_ID = re.compile(r"^(?:[0-9]+|0[xX][0-9a-fA-F]+)$")
LIMITS = {"emoji": {"webp": 256 * 1024}, "sticker": {"webp": 512 * 1024, "mp4": 2 * 1024 * 1024}}

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def fail(message): raise ValueError(message)

def validate_pack(kind, directory):
    if not PACK_ID.fullmatch(directory.name.lower()): fail(f"invalid pack id: {directory}")
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file(): fail(f"missing manifest: {directory}")
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("type") != kind or manifest.get("id") != directory.name.lower(): fail(f"type/id mismatch: {manifest_path}")
    if not isinstance(manifest.get("version"), int) or manifest["version"] < 1: fail(f"invalid version: {manifest_path}")
    seen = set()
    for item in manifest.get("items", []):
        item_id, rel = str(item.get("id", "")), item.get("path", "")
        if not ITEM_ID.fullmatch(item_id) or item_id.lower() in seen: fail(f"invalid or duplicate item id in {manifest_path}")
        seen.add(item_id.lower())
        asset = (directory / rel).resolve()
        if directory.resolve() not in asset.parents or not asset.is_file(): fail(f"unsafe/missing asset {rel}")
        ext = asset.suffix.lower().lstrip(".")
        if ext not in LIMITS[kind]: fail(f"unsupported {kind} format: {asset}")
        if asset.stat().st_size > LIMITS[kind][ext]: fail(f"asset too large: {asset}")
        if item.get("mime") != {"webp":"image/webp", "mp4":"video/mp4"}[ext]: fail(f"MIME mismatch: {asset}")
        if item.get("sha256", "").lower() != digest(asset): fail(f"SHA-256 mismatch: {asset}")
        if item.get("size") != asset.stat().st_size: fail(f"size mismatch: {asset}")
    root_name = "emojis" if kind == "emoji" else "stickers"
    return {"type":kind, "id":manifest["id"], "name":manifest.get("name", manifest["id"]), "version":manifest["version"], "manifest":"%s/%s/manifest.json" % (root_name, directory.name), "manifestSha256":digest(manifest_path), "size":sum(p.stat().st_size for p in directory.rglob('*') if p.is_file()), "cover":manifest.get("cover")}

def main():
    packs=[]
    for kind, dirname in (("emoji", "emojis"), ("sticker", "stickers")):
        base=ROOT/dirname
        if base.exists(): packs.extend(validate_pack(kind, child) for child in sorted(base.iterdir()) if child.is_dir())
    (ROOT/"catalog.json").write_text(json.dumps({"format":1,"packs":packs}, ensure_ascii=False, indent=2)+"\n")
    print("validated %d pack(s)" % len(packs))
if __name__ == "__main__":
    try: main()
    except Exception as error: print("pack validation failed: %s" % error, file=sys.stderr); sys.exit(1)
