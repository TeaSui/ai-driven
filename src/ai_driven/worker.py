"""Worker entrypoint — polls SQS queues and dispatches tasks to agents.

Run with:
    .venv/bin/python -m ai_driven.worker

In local dev this talks to localstack (AI_DRIVEN_SQS_ENDPOINT_URL).
In prod it talks to real AWS SQS. The TaskConsumer class handles both
transparently.

T24 adds a reaper loop alongside the consumer: every 5 min, sweep for
stuck orchestrating/awaiting_qa/running tasks and transition them to
'failed' with an audit row. Runs in-process to keep deployment
topology simple (1 binary = consumer + reaper); post-dogfood it may
promote to a separate process per T28 runbook.
"""

from __future__ import annotations

import asyncio
import logging

from ai_driven.background.reapers import (
    reap_stuck_orchestrators,
    reap_stuck_qa,
    reap_stuck_running,
)
from ai_driven.observability.metrics import emit_counter
from ai_driven.queue.consumer import TaskConsumer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


# Reaper loop interval. Matches the stuck threshold divided by ~6 so a
# task that crosses the 30-min line waits at most 5 min to be reaped.
REAPER_LOOP_INTERVAL_SEC = 300


async def _reaper_loop() -> None:
    """Run all 3 reapers every 5 min. Emits reaper.heartbeat on each
    loop iteration so an oncall alert can fire if the loop silently
    stops (brief §M4).

    Each reaper runs in its own try/except — a failure in one (bad
    row, transient DB outage) must not skip the others. The outer
    while-True / sleep wrapper ensures the loop never exits; a bare
    exception is logged and we continue on the next tick.
    """
    logger.info("reaper_loop_starting interval=%d", REAPER_LOOP_INTERVAL_SEC)
    while True:
        try:
            try:
                n = await reap_stuck_orchestrators()
                if n:
                    logger.info("reaper.orchestrator.reaped count=%d", n)
            except Exception:
                logger.exception("reap_stuck_orchestrators loop iteration failed")

            try:
                n = await reap_stuck_qa()
                if n:
                    logger.info("reaper.qa.reaped count=%d", n)
            except Exception:
                logger.exception("reap_stuck_qa loop iteration failed")

            try:
                n = await reap_stuck_running()
                if n:
                    logger.info("reaper.running.reaped count=%d", n)
            except Exception:
                logger.exception("reap_stuck_running loop iteration failed")

            # Heartbeat counter — absence alert (>15 min without a tick)
            # pages oncall per brief.
            emit_counter("reaper.heartbeat")
        except Exception:
            # Belt-and-suspenders: even if the per-reaper try/except
            # is bypassed somehow, keep the outer loop alive.
            logger.exception("reaper_loop top-level iteration error")

        await asyncio.sleep(REAPER_LOOP_INTERVAL_SEC)


async def main() -> None:
    consumer = TaskConsumer()
    logger.info("worker_starting")

    # Start reaper loop alongside the SQS consumer. Both run in the
    # same event loop — the reaper loop spends ~99% of its time in
    # asyncio.sleep so it doesn't compete with consumer throughput.
    reaper_task = asyncio.create_task(_reaper_loop())
    try:
        await consumer.start()
    finally:
        reaper_task.cancel()
        try:
            await reaper_task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
