"""
pdf_worker.py — a tiny helper program that opens ONE untrusted PDF.

It is started by uploads.run_pdf_worker() as a separate process with strict
memory, CPU and time limits.  If a malicious PDF makes the parser misbehave,
only this throw-away process is affected — never the main server.

Input : the PDF bytes on standard input.
Args  : mode ("info", "text" or "layout") and the maximum allowed page count.
Output: one JSON object on standard output, e.g. {"pages": 3, "text": [...]}.
        "layout" also returns where each piece of text sits on the page
        ({"w": page width, "items": [[x, y, text], ...]}), which is needed
        for reports printed from a web page in two columns.

The parser is pypdf, a widely used, actively maintained pure-Python library.
It only reads text; it never runs JavaScript or opens embedded files.
"""

import io
import json
import sys

MAX_TEXT_CHARS_PER_PAGE = 20_000
MAX_ITEMS_PER_PAGE = 4_000      # text pieces with positions ("layout" mode)
MAX_ITEM_CHARS = 300


def _page_layout(page) -> dict:
    """Every piece of text on the page with its (x, y) position.
    y grows upwards (PDF convention).  Limited in count and length."""
    items: list = []

    def visit(text, cm, tm, _font, _size):
        text = (text or "").strip()
        if not text or len(items) >= MAX_ITEMS_PER_PAGE:
            return
        # Position = text matrix combined with the current transformation matrix.
        x = tm[4] * cm[0] + tm[5] * cm[2] + cm[4]
        y = tm[4] * cm[1] + tm[5] * cm[3] + cm[5]
        items.append([round(float(x), 1), round(float(y), 1), text[:MAX_ITEM_CHARS]])

    text = page.extract_text(visitor_text=visit) or ""
    return {"w": round(float(page.mediabox.width), 1), "items": items, "text": text[:MAX_TEXT_CHARS_PER_PAGE]}


def main() -> int:
    """Read the PDF from stdin and print the requested information as JSON."""
    mode = sys.argv[1] if len(sys.argv) > 1 else "info"
    max_pages = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    data = sys.stdin.buffer.read()
    try:
        from pypdf import PdfReader

        reader = PdfReader(io.BytesIO(data), strict=False)
        if reader.is_encrypted:
            # Many PDFs are "encrypted" with an empty password just to block
            # editing; try that, otherwise reject.
            try:
                if not reader.decrypt(""):
                    raise ValueError
            except Exception:
                print(json.dumps({"error": "Password-protected PDFs are not supported."}))
                return 0
        pages = len(reader.pages)
        if pages == 0:
            print(json.dumps({"error": "The PDF has no pages."}))
            return 0
        if pages > max_pages:
            print(json.dumps({"error": f"The PDF has too many pages ({pages}; limit {max_pages})."}))
            return 0
        result: dict = {"pages": pages}
        if mode == "text":
            texts = []
            for page in reader.pages:
                text = page.extract_text() or ""
                texts.append(text[:MAX_TEXT_CHARS_PER_PAGE])
            result["text"] = texts
        elif mode == "layout":
            layouts = [_page_layout(page) for page in reader.pages]
            result["text"] = [lay.pop("text") for lay in layouts]
            result["layout"] = layouts
        print(json.dumps(result))
        return 0
    except Exception:
        print(json.dumps({"error": "The PDF could not be read."}))
        return 0


if __name__ == "__main__":
    sys.exit(main())
