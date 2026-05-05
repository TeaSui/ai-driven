# Working with Files

Files API (upload by ID), PDF input, images / vision. For code_execution + `container_upload`, see [built-in-tools.md#code-execution](built-in-tools.md#code-execution).

## Contents

- [Files API](#files-api)
- [PDF input](#pdf-input)
- [Images / Vision](#images--vision)
- [Sources](#sources)

## Files API

Upload once, reference by `file_id` — skips re-upload on every request. Beta `files-api-2025-04-14`. Free storage; the Messages call still bills input tokens when the file is included.

```python
uploaded = client.beta.files.upload(
    file=("document.pdf", open("/path/doc.pdf", "rb"), "application/pdf"),
)

response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    betas=["files-api-2025-04-14"],
    messages=[{"role": "user", "content": [
        {"type": "text", "text": "Summarize this."},
        {"type": "document", "source": {"type": "file", "file_id": uploaded.id}},
    ]}],
)
```

Content blocks that accept `file_id`:
- `document` — PDF or `text/plain`
- `image` — jpeg/png/gif/webp
- `container_upload` — datasets for code execution

Gotchas:
- Max 500 MB per file, 500 GB per org.
- You can **download** files created by code_execution / Skills, **not** files you uploaded.
- Not supported on Bedrock / Vertex.
- `.csv / .xlsx / .docx / .md / .txt` are NOT `document`-compatible — inline as text or use code execution.
- Files are per-workspace (not per-API-key).
- For `.docx` with images, convert to PDF first.
- `files.list()` paginates; deletion is irreversible.

## PDF input

Claude reads PDFs as text + vision (each page is also an image). All active models. Three sources: URL, base64, file_id.

```python
# URL
message = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": [
        {"type": "document", "source": {"type": "url",
            "url": "https://assets.anthropic.com/.../doc.pdf"}},
        {"type": "text", "text": "Key findings?"},
    ]}],
)

# Base64
import base64
with open("r.pdf", "rb") as f:
    pdf_data = base64.standard_b64encode(f.read()).decode()
message = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": [
        {"type": "document", "source": {"type": "base64",
            "media_type": "application/pdf", "data": pdf_data}},
        {"type": "text", "text": "Summarize."},
    ]}],
)
```

Limits: 32 MB per request, 600 pages (100 for 200k-token models), no password / encryption.

Gotchas:
- Dense PDFs can exhaust context before the page limit. Split or downsample.
- Prefer Files API for large PDFs to keep payload small.
- On Bedrock Converse API, visual PDF analysis requires citations enabled — otherwise text-only extraction.
- Vision limitations apply (blurriness, low-res text, scanned PDFs with no OCR text layer).
- Base64 PDFs count toward the 32 MB payload cap **after** encoding.

For grounded citations on PDFs see [model-capabilities.md#citations](model-capabilities.md#citations).

## Images / Vision

JPEG / PNG / GIF / WebP via `image` block (base64, URL, or file_id). Token cost ≈ `(width × height) / 750`.

Request limits:
- 100 images per request on 200k-token models, **600** on 1M-token models.
- Max 8000 × 8000 px (2000 × 2000 if > 20 images).
- 32 MB payload cap.

```python
import base64, httpx
data = base64.standard_b64encode(httpx.get(url).content).decode()

response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": [
        {"type": "image", "source": {"type": "base64",
            "media_type": "image/jpeg", "data": data}},
        {"type": "text", "text": "Describe this image"},
    ]}],
)
# URL variant: {"source": {"type": "url", "url": "https://..."}}
# file_id variant: {"source": {"type": "file", "file_id": "file_..."}}
```

Gotchas:
- Most models resize to ≤ 1568 px long edge (1568 tokens). **Opus 4.7** supports 2576 px / 4784 tokens and costs up to 3× more — downsample when fidelity isn't needed.
- Animated GIFs: only the first frame is seen.
- Coordinates Claude outputs are on the resized / padded image — rescale client-side.
- Place images **before** text in the content array for best accuracy.
- Heavy JPEG compression degrades OCR.
- Payload cap (32 MB) often binds before image-count limit — use Files API for many large images.

## Sources

- `../docs/files/files-api.md`
- `../docs/files/pdf-support.md`
- `../docs/files/images-and-vision.md`
