"""Tests for the chat WebSocket streaming endpoint."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_driven.api.chat.router import router, _ALLOWED_EVENT_TYPES, _translate_event


@pytest.fixture
def app():
    app = FastAPI()
    app.include_router(router, prefix="/api/chat")
    return app


@pytest.fixture
def client(app):
    return TestClient(app)


class TestSendMessage:
    """Tests for POST /api/chat/send."""

    def test_send_message_returns_task_id(self, client):
        resp = client.post(
            "/api/chat/send",
            json={"message": "implement feature X"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "queued"
        assert "taskId" in body or "task_id" in body

    def test_send_message_validates_input(self, client):
        resp = client.post("/api/chat/send", json={})
        assert resp.status_code == 422


class TestAllowedEventTypes:
    """Verify the set of allowed event types."""

    def test_allowed_types_are_complete(self):
        expected = {
            "text_delta", "tool_call_start", "tool_call_end", "phase_change",
            "complete", "error", "prompt_sent", "response_chunk", "iteration",
            "streaming_chunk", "rag_indexing_start", "rag_indexing_complete",
        }
        assert _ALLOWED_EVENT_TYPES == expected


class TestTranslateEvent:
    """Tests for _translate_event() step-type translation."""

    def test_streaming_chunk_maps_to_think(self):
        event = {"type": "streaming_chunk", "data": {"content": "hello"}}
        result = _translate_event(event)
        assert result["stepType"] == "think"
        assert result["type"] == "streaming_chunk"
        assert result["data"]["content"] == "hello"

    def test_text_delta_maps_to_think(self):
        event = {"type": "text_delta", "data": {"content": "world"}}
        result = _translate_event(event)
        assert result["stepType"] == "think"

    def test_response_chunk_maps_to_think(self):
        event = {"type": "response_chunk", "data": {"content": "chunk"}}
        result = _translate_event(event)
        assert result["stepType"] == "think"

    def test_tool_call_start_maps_to_tool(self):
        event = {"type": "tool_call_start", "data": {"tool": "shell.run"}}
        result = _translate_event(event)
        assert result["stepType"] == "tool"

    def test_tool_call_end_maps_to_tool(self):
        event = {"type": "tool_call_end", "data": {"tool": "shell.run", "result_data": "ok"}}
        result = _translate_event(event)
        assert result["stepType"] == "tool"

    def test_tool_call_end_write_file_maps_to_edit(self):
        event = {"type": "tool_call_end", "data": {"tool": "file.write", "result_data": "ok"}}
        result = _translate_event(event)
        assert result["stepType"] == "edit"

    def test_tool_call_end_create_file_maps_to_edit(self):
        event = {"type": "tool_call_end", "data": {"tool": "create_file"}}
        result = _translate_event(event)
        assert result["stepType"] == "edit"

    def test_tool_call_end_edit_file_maps_to_edit(self):
        event = {"type": "tool_call_end", "data": {"tool": "edit_file"}}
        result = _translate_event(event)
        assert result["stepType"] == "edit"

    def test_tool_call_end_fs_writefile_maps_to_edit(self):
        event = {"type": "tool_call_end", "data": {"tool": "fs.writeFile"}}
        result = _translate_event(event)
        assert result["stepType"] == "edit"

    def test_complete_event_passes_through_without_step_type(self):
        event = {"type": "complete", "data": {"output_length": 100}}
        result = _translate_event(event)
        assert "stepType" not in result
        assert result["type"] == "complete"

    def test_error_event_passes_through_without_step_type(self):
        event = {"type": "error", "data": {"message": "fail"}}
        result = _translate_event(event)
        assert "stepType" not in result

    def test_phase_change_passes_through_without_step_type(self):
        event = {"type": "phase_change", "data": {"phase": "coding"}}
        result = _translate_event(event)
        assert "stepType" not in result

    def test_original_fields_preserved(self):
        event = {"type": "streaming_chunk", "data": {"content": "hi"}, "timestamp": "t1", "extra": 42}
        result = _translate_event(event)
        assert result["timestamp"] == "t1"
        assert result["extra"] == 42
        assert result["type"] == "streaming_chunk"
        assert result["stepType"] == "think"

    def test_tool_call_end_missing_tool_name_maps_to_tool(self):
        event = {"type": "tool_call_end", "data": {}}
        result = _translate_event(event)
        assert result["stepType"] == "tool"


def _make_mock_redis(messages: list[dict | None]):
    """Create a mock Redis client whose pubsub ``get_message`` yields items.

    ``pubsub()`` is a synchronous method on the real redis client so we
    use ``MagicMock`` for the Redis instance and ``AsyncMock`` for the
    pubsub object's async methods.
    """
    mock_pubsub = AsyncMock()
    _iter = iter(messages)

    async def _get_message(ignore_subscribe_messages=True, timeout=0.1):
        return next(_iter, None)

    mock_pubsub.get_message = _get_message
    mock_pubsub.subscribe = AsyncMock()
    mock_pubsub.unsubscribe = AsyncMock()
    mock_pubsub.close = AsyncMock()

    mock_redis = MagicMock()
    mock_redis.pubsub.return_value = mock_pubsub

    return mock_redis, mock_pubsub


class TestStreamWebSocket:
    """Tests for WebSocket /api/chat/stream/{task_id}."""

    def test_websocket_receives_connected_and_complete(self, client):
        messages = [
            {"type": "message", "data": json.dumps({
                "type": "complete",
                "data": {"output_length": 100},
                "timestamp": "2026-04-20T12:00:00Z",
            })},
        ]
        mock_redis, mock_pubsub = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-1") as ws:
                msg = ws.receive_json()
                assert msg["type"] == "connected"
                assert msg["task_id"] == "task-1"

                msg = ws.receive_json()
                assert msg["type"] == "complete"
                assert msg["data"]["output_length"] == 100

    def test_websocket_filters_unknown_event_types(self, client):
        messages = [
            {"type": "message", "data": json.dumps({
                "type": "unknown_event", "data": {}, "timestamp": "t",
            })},
            {"type": "message", "data": json.dumps({
                "type": "complete", "data": {}, "timestamp": "t",
            })},
        ]
        mock_redis, _ = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-2") as ws:
                connected = ws.receive_json()
                assert connected["type"] == "connected"

                msg = ws.receive_json()
                assert msg["type"] == "complete"

    def test_websocket_forwards_all_allowed_types_in_order(self, client):
        event_sequence = ["text_delta", "tool_call_start", "tool_call_end", "phase_change", "complete"]
        messages = [
            {"type": "message", "data": json.dumps({"type": t, "data": {}, "timestamp": "t"})}
            for t in event_sequence
        ]
        mock_redis, _ = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-3") as ws:
                _ = ws.receive_json()  # connected

                received_types = []
                for _ in event_sequence:
                    msg = ws.receive_json()
                    received_types.append(msg["type"])

                assert received_types == event_sequence

    def test_websocket_handles_malformed_json_from_redis(self, client):
        messages = [
            {"type": "message", "data": "not-json{{{"},
            {"type": "message", "data": json.dumps({
                "type": "complete", "data": {}, "timestamp": "t",
            })},
        ]
        mock_redis, _ = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-4") as ws:
                _ = ws.receive_json()  # connected
                msg = ws.receive_json()
                assert msg["type"] == "complete"

    def test_websocket_closes_on_error_event(self, client):
        messages = [
            {"type": "message", "data": json.dumps({
                "type": "error", "data": {"message": "fail"}, "timestamp": "t",
            })},
        ]
        mock_redis, _ = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-5") as ws:
                _ = ws.receive_json()  # connected
                msg = ws.receive_json()
                assert msg["type"] == "error"

    def test_pubsub_cleanup_on_exit(self, client):
        messages = [
            {"type": "message", "data": json.dumps({
                "type": "complete", "data": {}, "timestamp": "t",
            })},
        ]
        mock_redis, mock_pubsub = _make_mock_redis(messages)

        with patch("ai_driven.api.chat.router.aioredis") as mock_mod:
            mock_mod.from_url.return_value = mock_redis

            with client.websocket_connect("/api/chat/stream/task-6") as ws:
                _ = ws.receive_json()
                _ = ws.receive_json()

        mock_pubsub.unsubscribe.assert_awaited()
        mock_pubsub.close.assert_awaited()
