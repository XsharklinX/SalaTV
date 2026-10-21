"""Copies CleanYourTV's per-model debloat lists into app/src/main/assets/listas, with an index.

The index (index.tsv) has one line per model: folder, TV name, OS, taken from examples/README.md.
Run from the repo root: python tools/copy_lists.py [path to cleanyourtv]
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / "cleanyourtv"
OUT = ROOT / "app" / "src" / "main" / "assets" / "listas"


def clean(cell):
    return re.sub(r"[`*]", "", cell).strip()


def main():
    examples = SRC / "examples"
    rows = {}
    for line in (examples / "README.md").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\|\s*`([^`/]+)/?`\s*\|([^|]*)\|([^|]*)\|", line)
        if m:
            rows[m.group(1)] = (clean(m.group(2)), clean(m.group(3)))
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    index = []
    for d in sorted(p for p in examples.iterdir() if p.is_dir()):
        src = d / "debloat.txt"
        if not src.exists():
            continue
        shutil.copy(src, OUT / f"{d.name}.txt")
        title, os_name = rows.get(d.name, (d.name, ""))
        index.append(f"{d.name}\t{title}\t{os_name}")
    (OUT / "index.tsv").write_text("\n".join(index) + "\n", encoding="utf-8")
    shutil.copy(SRC / "LICENSE", OUT / "LICENSE-cleanyourtv.txt")
    print(f"{len(index)} listas copiadas")


if __name__ == "__main__":
    main()
