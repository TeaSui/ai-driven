"""Tests for Jira client hardening: ADF extraction, retry, pagination, new methods."""

from __future__ import annotations

import pytest

from ai_driven.jira.client import JiraClient


class TestExtractText:
    """Tests for the ADF-to-text extraction logic."""

    def test_none_returns_empty(self) -> None:
        assert JiraClient._extract_text(None) == ""

    def test_string_passthrough(self) -> None:
        assert JiraClient._extract_text("plain text") == "plain text"

    def test_paragraph(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "paragraph",
                    "content": [{"type": "text", "text": "Hello world"}],
                }
            ],
        }
        assert JiraClient._extract_text(adf) == "Hello world"

    def test_heading(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "heading",
                    "attrs": {"level": 2},
                    "content": [{"type": "text", "text": "Title"}],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "Title" in result

    def test_bullet_list(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "bulletList",
                    "content": [
                        {
                            "type": "listItem",
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "Item A"}],
                                }
                            ],
                        },
                        {
                            "type": "listItem",
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "Item B"}],
                                }
                            ],
                        },
                    ],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "- Item A" in result
        assert "- Item B" in result

    def test_ordered_list(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "orderedList",
                    "content": [
                        {
                            "type": "listItem",
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "First"}],
                                }
                            ],
                        },
                        {
                            "type": "listItem",
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "Second"}],
                                }
                            ],
                        },
                    ],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "1. First" in result
        assert "2. Second" in result

    def test_code_block(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "codeBlock",
                    "attrs": {"language": "python"},
                    "content": [{"type": "text", "text": "print('hi')"}],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "```" in result
        assert "print('hi')" in result

    def test_mention(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "paragraph",
                    "content": [
                        {"type": "mention", "attrs": {"id": "123", "text": "@John Doe"}},
                    ],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "@John Doe" in result

    def test_inline_card(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "paragraph",
                    "content": [
                        {
                            "type": "inlineCard",
                            "attrs": {"url": "https://example.com/page"},
                        },
                    ],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "https://example.com/page" in result

    def test_block_card(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "blockCard",
                    "attrs": {"url": "https://example.com/doc"},
                },
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "https://example.com/doc" in result

    def test_table(self) -> None:
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "table",
                    "content": [
                        {
                            "type": "tableRow",
                            "content": [
                                {
                                    "type": "tableHeader",
                                    "content": [
                                        {
                                            "type": "paragraph",
                                            "content": [{"type": "text", "text": "Name"}],
                                        }
                                    ],
                                },
                                {
                                    "type": "tableHeader",
                                    "content": [
                                        {
                                            "type": "paragraph",
                                            "content": [{"type": "text", "text": "Value"}],
                                        }
                                    ],
                                },
                            ],
                        },
                        {
                            "type": "tableRow",
                            "content": [
                                {
                                    "type": "tableCell",
                                    "content": [
                                        {
                                            "type": "paragraph",
                                            "content": [{"type": "text", "text": "foo"}],
                                        }
                                    ],
                                },
                                {
                                    "type": "tableCell",
                                    "content": [
                                        {
                                            "type": "paragraph",
                                            "content": [{"type": "text", "text": "bar"}],
                                        }
                                    ],
                                },
                            ],
                        },
                    ],
                }
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "Name | Value" in result
        assert "foo | bar" in result

    def test_mixed_content(self) -> None:
        """Multiple node types in one document."""
        adf = {
            "type": "doc",
            "content": [
                {
                    "type": "paragraph",
                    "content": [{"type": "text", "text": "Intro"}],
                },
                {
                    "type": "bulletList",
                    "content": [
                        {
                            "type": "listItem",
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "bullet"}],
                                }
                            ],
                        },
                    ],
                },
                {
                    "type": "codeBlock",
                    "content": [{"type": "text", "text": "code()"}],
                },
            ],
        }
        result = JiraClient._extract_text(adf)
        assert "Intro" in result
        assert "- bullet" in result
        assert "```" in result
        assert "code()" in result
