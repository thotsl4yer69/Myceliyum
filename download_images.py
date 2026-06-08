"""Download species images from Wikimedia Commons with rate-limit handling."""
import os, time, urllib.request, urllib.error, sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
IMG_DIR = os.path.join(SCRIPT_DIR, "app", "src", "main", "assets", "species_images")
MANIFEST = os.path.join(IMG_DIR, "download_manifest.txt")
DELAY = 5  # seconds between downloads
MAX_RETRIES = 3

os.makedirs(IMG_DIR, exist_ok=True)

opener = urllib.request.build_opener()
opener.addheaders = [
    ("User-Agent", "MycilliyumsApp/1.0 (jack@mazlabz.ai; one-time asset bundle; Android field guide)")
]
urllib.request.install_opener(opener)

with open(MANIFEST, "r") as f:
    lines = [l.strip() for l in f if l.strip()]

total = len(lines)
ok = fail = skip = 0
print(f"\n=== Downloading {total} species images ({DELAY}s between each) ===\n")

for i, line in enumerate(lines):
    fname, url = line.split("|", 1)
    fname, url = fname.strip(), url.strip()
    dest = os.path.join(IMG_DIR, fname)

    if os.path.exists(dest) and os.path.getsize(dest) > 5000:
        sz = os.path.getsize(dest) // 1024
        print(f"  SKIP {fname} ({sz}KB already exists)")
        ok += 1; skip += 1
        continue

    success = False
    for attempt in range(1, MAX_RETRIES + 1):
        retry_note = f" (retry {attempt})" if attempt > 1 else ""
        print(f"  [{i+1}/{total}] {fname}{retry_note} ... ", end="", flush=True)
        try:
            urllib.request.urlretrieve(url, dest)
            sz = os.path.getsize(dest) // 1024
            print(f"OK ({sz}KB)")
            ok += 1; success = True
            break
        except urllib.error.HTTPError as e:
            if e.code == 429:
                backoff = DELAY * (2 ** attempt)
                print(f"rate-limited, waiting {backoff}s...")
                time.sleep(backoff)
            else:
                print(f"HTTP {e.code}")
                break
        except Exception as e:
            print(f"ERROR: {e}")
            break

    if not success:
        fail += 1
        if os.path.exists(dest):
            os.remove(dest)

    if i < total - 1 and success:
        time.sleep(DELAY)

print(f"\n=== Done: {ok} OK ({skip} skipped), {fail} failed ===")
if fail == 0:
    print("All images ready. Build the app.")
else:
    print(f"Re-run to retry {fail} failures.")

input("\nPress Enter to close...")
