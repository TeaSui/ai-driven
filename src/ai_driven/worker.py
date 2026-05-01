"""Worker entrypoint — polls SQS queues and dispatches tasks to agents.

Run with:
    .venv/bin/python -m ai_driven.worker

In local dev this talks to localstack (AI_DRIVEN_SQS_ENDPOINT_URL).
In prod it talks to real AWS SQS. The TaskConsumer class handles both
transparently.
"""

from __future__ import annotations

import asyncio
import logging

from ai_driven.queue.consumer import TaskConsumer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


async def main() -> None:
    consumer = TaskConsumer()
    logger.info("worker_starting")
    await consumer.start()


if __name__ == "__main__":
    asyncio.run(main())
