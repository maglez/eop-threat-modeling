#!/usr/bin/env python3
"""Report production classes ranked by effective lines of code.

Effective lines exclude blank lines and comment lines (``//`` line comments
and ``/* ... */`` block comments, including Javadoc). A line holding code
plus a trailing comment counts once; string and text-block content counts
as code. Scanning is limited to production sources under
``src/main/java/org/maglez`` by default.

Every scanned class is printed to the screen and also written to a
Microsoft Excel (``.xlsx``) workbook — with no third-party dependencies,
the file is assembled from the standard library's ``zipfile`` and XML
modules alone. A ``Classes`` sheet holds one row per class (rank, class,
effective lines of code, total lines) and a ``Summary`` sheet holds the
scan totals and the largest class.

Usage:
    tools/largest-class.py                  Scan the default path, print all, write Excel.
    tools/largest-class.py PATH             Scan PATH instead of the default.
    tools/largest-class.py --top 10         Print only the top 10 (Excel still holds all).
    tools/largest-class.py --excel OUT.xlsx Write Excel to OUT.xlsx instead of the default.
    tools/largest-class.py --no-excel       Print only, write no Excel file.
    tools/largest-class.py --json           Machine-readable ranking on stdout.

Exit code is 0 when at least one class is found, 1 otherwise.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import escape

DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "src/main/java/org/maglez"
DEFAULT_EXCEL = Path(__file__).resolve().parent / "largest-class.xlsx"

_STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts>
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/><color rgb="FFFFFFFF"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="4">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>
"""

_WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Classes" sheetId="1" r:id="rId1"/>
    <sheet name="Summary" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>
"""

_WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"""

_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>
"""

_ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>
"""


def count_effective_lines(source: str) -> int:
    """Count lines containing code outside comments in Java source."""
    code_lines: set[int] = set()
    line = 1
    index = 0
    length = len(source)
    block_comment = False
    text_block = False

    while index < length:
        char = source[index]
        following = source[index + 1] if index + 1 < length else ""
        third = source[index + 2] if index + 2 < length else ""

        if char == "\n":
            line += 1
            index += 1
            continue

        if block_comment:
            if char == "*" and following == "/":
                block_comment = False
                index += 2
            else:
                index += 1
            continue

        if text_block:
            if char == '"' and following == '"' and third == '"':
                code_lines.add(line)
                text_block = False
                index += 3
                continue
            if char == "\\" and following:
                index += 2
                continue
            if not char.isspace():
                code_lines.add(line)
            index += 1
            continue

        if char == "/" and following == "/":
            while index < length and source[index] != "\n":
                index += 1
            continue

        if char == "/" and following == "*":
            block_comment = True
            index += 2
            continue

        if char == '"' and following == '"' and third == '"':
            code_lines.add(line)
            text_block = True
            index += 3
            continue

        if char == '"' or char == "'":
            quote = char
            code_lines.add(line)
            index += 1
            while index < length:
                current = source[index]
                if current == "\n":
                    break
                if current == "\\":
                    index += 2
                    continue
                index += 1
                if current == quote:
                    break
            continue

        if not char.isspace():
            code_lines.add(line)
        index += 1

    return len(code_lines)


def scan(root: Path) -> list[tuple[int, int, Path]]:
    """Rank every Java file under root by effective lines of code."""
    ranking: list[tuple[int, int, Path]] = []
    for path in sorted(root.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        ranking.append((count_effective_lines(source), len(source.splitlines()), path))
    ranking.sort(key=lambda entry: (-entry[0], str(entry[2])))
    return ranking


def _cell(ref: str, value: str | int, style: int) -> str:
    if isinstance(value, int):
        return f'<c r="{ref}" s="{style}"><v>{value}</v></c>'
    return f'<c r="{ref}" s="{style}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'


def _sheet(rows: list[list[str | int]], widths: list[int], widths_style: int = 3) -> str:
    columns = "".join(
        f'<col min="{number}" max="{number}" width="{width}" customWidth="1"/>'
        for number, width in enumerate(widths, start=1)
    )
    body = []
    for row_number, row in enumerate(rows, start=1):
        if row_number == 1:
            cells = "".join(
                _cell(f"{chr(64 + column)}{row_number}", value, 1)
                for column, value in enumerate(row, start=1)
            )
        else:
            cells = "".join(
                _cell(
                    f"{chr(64 + column)}{row_number}",
                    value,
                    widths_style if isinstance(value, int) else 2,
                )
                for column, value in enumerate(row, start=1)
            )
        body.append(f'<row r="{row_number}">{cells}</row>')
    last_row = len(rows)
    last_column = chr(64 + len(rows[0])) if rows else "A"
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetViews><sheetView workbookViewId="0">'
        '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
        '</sheetView></sheetViews>'
        f'<sheetFormatPr defaultRowHeight="15"/><cols>{columns}</cols>'
        f'<sheetData>{"".join(body)}</sheetData>'
        f'<autoFilter ref="A1:{last_column}{last_row}"/>'
        '</worksheet>'
    )


def write_excel(destination: Path, root: Path, ranking: list[tuple[int, int, Path]]) -> None:
    """Write the full ranking as a two-sheet Microsoft Excel workbook."""
    class_rows: list[list[str | int]] = [["Rank", "Class", "Effective LOC", "Total lines"]]
    for rank, (effective, total, path) in enumerate(ranking, start=1):
        class_rows.append(
            [rank, path.relative_to(root).as_posix(), effective, total]
        )

    largest_effective, _, largest_path = ranking[0]
    summary_rows: list[list[str | int]] = [
        ["Item", "Value"],
        ["Scanned path", str(root)],
        ["Classes scanned", len(ranking)],
        ["Total effective LOC", sum(effective for effective, _, _ in ranking)],
        ["Total lines (incl. comments/blanks)", sum(total for _, total, _ in ranking)],
        ["Largest class", largest_path.relative_to(root).as_posix()],
        ["Largest class effective LOC", largest_effective],
        ["Generated", datetime.now().astimezone().isoformat(timespec="seconds")],
    ]

    core = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/">'
        '<dc:title>Production classes by effective lines of code</dc:title>'
        '</cp:coreProperties>'
    )

    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml", _CONTENT_TYPES)
        workbook.writestr("_rels/.rels", _ROOT_RELS)
        workbook.writestr("xl/workbook.xml", _WORKBOOK)
        workbook.writestr("xl/_rels/workbook.xml.rels", _WORKBOOK_RELS)
        workbook.writestr("xl/styles.xml", _STYLES)
        workbook.writestr("xl/worksheets/sheet1.xml", _sheet(class_rows, [8, 72, 16, 14]))
        workbook.writestr("xl/worksheets/sheet2.xml", _sheet(summary_rows, [38, 72]))
        workbook.writestr("docProps/core.xml", core)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, nargs="?", help="Directory to scan")
    parser.add_argument("--root", type=Path, help="Directory to scan (alias for PATH)")
    parser.add_argument(
        "--top",
        type=int,
        default=None,
        help="Limit screen output to the top N classes (Excel always holds every class)",
    )
    parser.add_argument(
        "--excel",
        type=Path,
        default=DEFAULT_EXCEL,
        help=f"Excel (.xlsx) output path (default: {DEFAULT_EXCEL})",
    )
    parser.add_argument("--no-excel", action="store_true", help="Do not write an Excel file")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text")
    args = parser.parse_args(argv)

    root = args.root or args.path or DEFAULT_ROOT
    if not root.is_dir():
        print(f"error: not a directory: {root}", file=sys.stderr)
        return 1

    ranking = scan(root)
    if not ranking:
        print(f"error: no Java files under {root}", file=sys.stderr)
        return 1

    if not args.no_excel:
        write_excel(args.excel, root, ranking)

    shown = ranking if args.top is None else ranking[: max(args.top, 1)]
    if args.json:
        print(json.dumps(
            [
                {
                    "class": path.relative_to(root).as_posix(),
                    "effective_lines": effective,
                    "total_lines": total,
                }
                for effective, total, path in shown
            ],
            indent=2,
        ))
        if not args.no_excel:
            print(f"Excel: {args.excel}", file=sys.stderr)
    else:
        for effective, total, path in shown:
            print(f"{effective:>6}  {path.relative_to(root)}")
        total_effective = sum(effective for effective, _, _ in ranking)
        print(f"\n{len(ranking)} classes scanned, {total_effective} effective lines total")
        if not args.no_excel:
            print(f"Excel: {args.excel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
