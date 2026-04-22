"""Code chunking strategies for RAG indexing."""

from __future__ import annotations

import ast
import logging
import re
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class CodeChunk:
    file_path: str
    content: str
    chunk_index: int
    start_line: int
    end_line: int
    chunk_type: str  # "function", "class", "module", "fixed"


def chunk_python_ast(file_path: str, source: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    """Chunk Python source using AST — one chunk per top-level function/class."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return chunk_fixed_size(file_path, source, max_chunk_size)

    lines = source.splitlines(keepends=True)
    chunks: list[CodeChunk] = []
    idx = 0

    for node in ast.iter_child_nodes(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            start = node.lineno - 1
            end = node.end_lineno or (start + 1)
            content = "".join(lines[start:end])
            if len(content) > max_chunk_size * 4:
                # Split very large classes into sub-chunks
                sub_chunks = chunk_fixed_size(file_path, content, max_chunk_size)
                for sc in sub_chunks:
                    sc.chunk_index = idx
                    sc.start_line += start
                    sc.end_line += start
                    chunks.append(sc)
                    idx += 1
            else:
                chunk_type = "class" if isinstance(node, ast.ClassDef) else "function"
                chunks.append(CodeChunk(
                    file_path=file_path, content=content,
                    chunk_index=idx, start_line=start + 1, end_line=end,
                    chunk_type=chunk_type,
                ))
                idx += 1

    # Add module-level code (imports, constants) as first chunk
    module_lines = []
    child_ranges: set[int] = set()
    for node in ast.iter_child_nodes(tree):
        if hasattr(node, "lineno") and hasattr(node, "end_lineno"):
            for ln in range(node.lineno, (node.end_lineno or node.lineno) + 1):
                child_ranges.add(ln)
    for i, line in enumerate(lines, 1):
        if i not in child_ranges:
            module_lines.append(line)
    module_content = "".join(module_lines).strip()
    if module_content:
        chunks.insert(0, CodeChunk(
            file_path=file_path, content=module_content,
            chunk_index=0, start_line=1, end_line=len(lines),
            chunk_type="module",
        ))
        for c in chunks[1:]:
            c.chunk_index += 1

    return chunks


def chunk_fixed_size(file_path: str, content: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    """Chunk content by fixed character count with line-boundary alignment."""
    lines = content.splitlines(keepends=True)
    chunks: list[CodeChunk] = []
    current_lines: list[str] = []
    current_size = 0
    start_line = 1
    idx = 0

    for i, line in enumerate(lines, 1):
        if current_size + len(line) > max_chunk_size and current_lines:
            chunks.append(CodeChunk(
                file_path=file_path,
                content="".join(current_lines),
                chunk_index=idx,
                start_line=start_line,
                end_line=i - 1,
                chunk_type="fixed",
            ))
            idx += 1
            current_lines = []
            current_size = 0
            start_line = i
        current_lines.append(line)
        current_size += len(line)

    if current_lines:
        chunks.append(CodeChunk(
            file_path=file_path,
            content="".join(current_lines),
            chunk_index=idx,
            start_line=start_line,
            end_line=len(lines),
            chunk_type="fixed",
        ))

    return chunks


def _chunk_by_regex(
    file_path: str,
    source: str,
    pattern: re.Pattern[str],
    max_chunk_size: int = 1000,
    default_type: str = "function",
) -> list[CodeChunk]:
    """Split source at zero-indentation boundaries matching *pattern*.

    Each match starts a new chunk that extends until the next match or EOF.
    Falls back to fixed-size if no matches are found.
    """
    lines = source.splitlines(keepends=True)
    boundaries: list[int] = []
    for i, line in enumerate(lines):
        if pattern.match(line):
            boundaries.append(i)

    if not boundaries:
        return chunk_fixed_size(file_path, source, max_chunk_size)

    chunks: list[CodeChunk] = []
    # Module-level preamble before first boundary
    if boundaries[0] > 0:
        preamble = "".join(lines[: boundaries[0]]).strip()
        if preamble:
            chunks.append(CodeChunk(
                file_path=file_path, content=preamble,
                chunk_index=0, start_line=1, end_line=boundaries[0],
                chunk_type="module",
            ))

    for idx_b, start in enumerate(boundaries):
        end = boundaries[idx_b + 1] if idx_b + 1 < len(boundaries) else len(lines)
        content_block = "".join(lines[start:end])
        if len(content_block) > max_chunk_size * 4:
            for sc in chunk_fixed_size(file_path, content_block, max_chunk_size):
                sc.chunk_index = len(chunks)
                sc.start_line += start
                sc.end_line += start
                chunks.append(sc)
        else:
            ctype = default_type
            stripped = lines[start].strip()
            if stripped.startswith("class ") or "class " in stripped[:30]:
                ctype = "class"
            elif stripped.startswith(("interface ", "type ")):
                ctype = "interface"
            chunks.append(CodeChunk(
                file_path=file_path, content=content_block,
                chunk_index=len(chunks), start_line=start + 1, end_line=end,
                chunk_type=ctype,
            ))

    # Re-index
    for i, c in enumerate(chunks):
        c.chunk_index = i

    return chunks


# Regex patterns for language-specific top-level definitions (column-0 only)
_TS_PATTERN = re.compile(
    r"^(?:export\s+)?(?:default\s+)?(?:async\s+)?"
    r"(?:function\s+\w|class\s+\w|interface\s+\w|type\s+\w|const\s+\w+\s*=\s*(?:\(|async\s*\())",
)
_JAVA_PATTERN = re.compile(
    r"^(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:abstract\s+)?"
    r"(?:class|interface|enum|record|void|int|long|String|boolean|byte|short|float|double|[\w<>\[\]]+)\s+\w+",
)
_GO_PATTERN = re.compile(
    r"^(?:func\s+(?:\(\w+\s+\*?\w+\)\s+)?\w|type\s+\w+\s+(?:struct|interface))",
)
_KOTLIN_PATTERN = re.compile(
    r"^(?:(?:public|private|internal|protected|open|data|sealed|abstract|override)\s+)*"
    r"(?:fun\s+\w|class\s+\w|interface\s+\w|object\s+\w|enum\s+class\s+\w)",
)


def chunk_typescript(file_path: str, source: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    return _chunk_by_regex(file_path, source, _TS_PATTERN, max_chunk_size)


def chunk_java(file_path: str, source: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    return _chunk_by_regex(file_path, source, _JAVA_PATTERN, max_chunk_size)


def chunk_go(file_path: str, source: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    return _chunk_by_regex(file_path, source, _GO_PATTERN, max_chunk_size)


def chunk_kotlin(file_path: str, source: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    return _chunk_by_regex(file_path, source, _KOTLIN_PATTERN, max_chunk_size)


_EXTENSION_CHUNKER = {
    ".py": chunk_python_ast,
    ".ts": chunk_typescript,
    ".tsx": chunk_typescript,
    ".js": chunk_typescript,
    ".jsx": chunk_typescript,
    ".java": chunk_java,
    ".kt": chunk_kotlin,
    ".go": chunk_go,
}


def chunk_file(file_path: str, content: str, max_chunk_size: int = 1000) -> list[CodeChunk]:
    """Route to the appropriate chunking strategy based on file extension."""
    for ext, chunker in _EXTENSION_CHUNKER.items():
        if file_path.endswith(ext):
            return chunker(file_path, content, max_chunk_size)
    return chunk_fixed_size(file_path, content, max_chunk_size)
