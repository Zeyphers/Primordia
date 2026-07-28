"""
Exports the field guide's text to an editable JSON file.

Parsed out of GuideChapters.java rather than kept as a second copy, so the export is always what
the game actually shows. Hand the edited file back and `import_guide.py` writes it home.

    python design/export_guide.py [destination]
"""

import io
import json
import os
import re
import sys

SOURCE = os.path.join("src", "main", "java", "dev", "jsz", "primordia", "lab",
                      "GuideChapters.java")
DEFAULT_DEST = os.path.join(os.path.expanduser("~"), "Downloads",
                            "primordia_field_guide.json")

README = [
    "Primordia field guide text. Edit freely and hand this file back to have it applied.",
    "",
    "unlock - when an entry becomes legible. One of:",
    "    ALWAYS          readable from the start",
    "    FIRST_SPECIMEN  anything filed at all",
    "    THREE_SPECIES   three species on file",
    "    STUDIED         one species at PARTIAL confidence or better",
    "    MASTERED        one species fully characterised (12 filed)",
    "    FORK_SEEN       two filed species close enough to be kin",
    "",
    "paragraphs - an empty string is a blank line. Text wraps on its own, so do not",
    "    hard-wrap it. Keep away from exact mechanics: this is a journal, not a manual.",
    "",
    "sealed_hints - shown in place of a sealed entry. Name the work, never the answer.",
    "",
    "sections - the tabs. 'chapters' lists entry titles in order. The last two tabs are",
    "    generated from the reader's own records and hold no written entries.",
]


def java_strings(fragment):
    """Every Java string literal in order, joining runs spliced with '+'."""
    out, buf = [], ""
    pattern = re.compile(r'"((?:[^"\\]|\\.)*)"([ \t\r\n]*\+)?')
    for match in pattern.finditer(fragment):
        buf += match.group(1)
        if not match.group(2):
            out.append(buf.encode("utf-8").decode("unicode_escape"))
            buf = ""
    if buf:
        out.append(buf)
    return out


def main():
    src = io.open(SOURCE, encoding="utf-8").read()

    body = src[src.index("private static final List<Chapter> ALL"):
               src.index("public static final List<Section> SECTIONS")]

    chapters = []
    chapter_re = re.compile(
        r'new Chapter\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*List\.of\((.*?)\)\s*,\s*Unlock\.(\w+)\s*\)',
        re.S)
    for match in chapter_re.finditer(body):
        chapters.append({
            "title": match.group(1),
            "unlock": match.group(3),
            "paragraphs": java_strings(match.group(2)),
        })

    sec_block = src[src.index("public static final List<Section> SECTIONS"):
                    src.index("REFERENCE_TAB")]
    # Split on the constructor rather than trying to match balanced parentheses: every ALL.get(n)
    # carries its own ')', which defeats any single regex that tries to find the closing bracket.
    sections = []
    for chunk in sec_block.split("new Section(")[1:]:
        names = re.findall(r'"([^"]+)"', chunk)
        if len(names) < 2:
            continue
        indices = [int(i) for i in re.findall(r"ALL\.get\((\d+)\)", chunk.split("new Section(")[0])]
        sections.append({
            "tab": names[0],
            "icon": names[1],
            "chapters": [chapters[i]["title"] for i in indices if i < len(chapters)],
        })

    hints_block = src[src.index("public String hint()"):]
    hints = dict(re.findall(r'case (\w+) -> "([^"]*)";', hints_block))

    dest = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DEST
    payload = {
        "_readme": README,
        "sealed_hints": hints,
        "sections": sections,
        "chapters": chapters,
    }
    with io.open(dest, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print("exported %d entries across %d tabs" % (len(chapters), len(sections)))
    print("  -> %s (%d bytes)" % (dest, os.path.getsize(dest)))
    for chapter in chapters:
        words = sum(len(p.split()) for p in chapter["paragraphs"])
        print("    %-15s %-38s %4d words" % (chapter["unlock"], chapter["title"], words))

    # Every written entry must appear on some tab, or it ships unreadable.
    placed = {t for s in sections for t in s["chapters"]}
    orphans = [c["title"] for c in chapters if c["title"] not in placed]
    if orphans:
        print("\n  WARNING - entries on no tab: %s" % ", ".join(orphans))


if __name__ == "__main__":
    main()
