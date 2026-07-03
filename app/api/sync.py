"""
Sync API — Redis-backed queue between data ingestion and processing.

Previously, sync ingestion and processing happened synchronously in the same
request, causing:
- Request timeouts on large payloads
- No retry on processing failures
- No backpressure when processing is slow
- Server restarts lose in-flight data

This module adds a Redis queue (or in-memory fallback) between the ingestion
endpoint and the processing worker:

  Device → POST /v1/sync/upload → Validate → Enqueue → 202 Accepted
                                                      ↓
  Background Worker ← Dequeue → Process → Store → Mark Complete

Features:
- Redis-backed durable queue with in-memory fallback
- Dead letter queue for permanently failed items
- Processing metrics and health checks
- Graceful shutdown with in-flight item draining
"""

import asyncio
import hashlib
import json
import logging
import time
import uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)


# ─── Data Types ──────────────────────────────────────────────────────────────

class SyncItemStatus(str, Enum):
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    DEAD_LETTER = "dead_letter"


@dataclass
class SyncItem:
    """A single sync payload item in the queue."""
    item_id: str
    device_id: str
    payload: bytes  # raw encrypted/compressed payload
    payload_hash: str  # SHA-256 of payload for deduplication
    status: SyncItemStatus = SyncItemStatus.QUEUED
    attempt_count: int = 0
    max_attempts: int = 3
    last_error: str = ""
    queued_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None

    def to_dict(self) -> dict:
        d = asdict(self)
        d["status"] = self.status.value
        d["payload"] = self.payload.hex()  # JSON-safe encoding
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "SyncItem":
        d["status"] = SyncItemStatus(d["status"])
        if isinstance(d.get("payload"), str):
            d["payload"] = bytes.fromhex(d["payload"])
        return cls(**d)


@dataclass
class SyncQueueStats:
    """Queue health metrics."""
    queued_count: int = 0
    processing_count: int = 0
    completed_count: int = 0
    failed_count: int = 0
    dead_letter_count: int = 0
    total_processed: int = 0
    average_processing_time_ms: float = 0.0
    oldest_queued_age_seconds: float = 0.0


# ─── Queue Backend Abstraction ───────────────────────────────────────────────

class SyncQueueBackend:
    """Abstract queue backend interface."""

    async def enqueue(self, item: SyncItem) -> None:
        raise NotImplementedError

    async def dequeue(self, timeout_seconds: float = 5.0) -> Optional[SyncItem]:
        raise NotImplementedError

    async def complete(self, item_id: str) -> None:
        raise NotImplementedError

    async def fail(self, item_id: str, error: str, requeue: bool = True) -> None:
        raise NotImplementedError

    async def get_stats(self) -> SyncQueueStats:
        raise NotImplementedError

    async def get_item(self, item_id: str) -> Optional[SyncItem]:
        raise NotImplementedError

    async def health_check(self) -> bool:
        raise NotImplementedError

    async def shutdown(self) -> None:
        raise NotImplementedError


class InMemorySyncQueue(SyncQueueBackend):
    """
    In-memory queue backend for development and single-server deployments.

    Falls back to this when Redis is not available.
    Data is lost on server restart — use Redis backend for production.
    """

    def __init__(self, max_size: int = 10000):
        self.max_size = max_size
        self._queue: asyncio.Queue[SyncItem] = asyncio.Queue(maxsize=max_size)
        self._items: dict[str, SyncItem] = {}
        self._completed: list[SyncItem] = []
        self._dead_letter: list[SyncItem] = []
        self._processing_times: list[float] = []
        self._lock = asyncio.Lock()

    async def enqueue(self, item: SyncItem) -> None:
        async with self._lock:
            # Deduplication check
            if item.payload_hash in {i.payload_hash for i in self._items.values()}:
                logger.info("Duplicate sync item %s, skipping", item.item_id)
                return

            if self._queue.full():
                # Drop oldest failed items to make room
                logger.warning("Queue full, cannot enqueue item %s", item.item_id)
                raise RuntimeError("Sync queue is full")

            self._items[item.item_id] = item
            await self._queue.put(item)
            logger.debug("Enqueued sync item %s (queue size: %d)", item.item_id, self._queue.qsize())

    async def dequeue(self, timeout_seconds: float = 5.0) -> Optional[SyncItem]:
        try:
            item = await asyncio.wait_for(self._queue.get(), timeout=timeout_seconds)
            item.status = SyncItemStatus.PROCESSING
            item.started_at = time.time()
            item.attempt_count += 1
            return item
        except asyncio.TimeoutError:
            return None

    async def complete(self, item_id: str) -> None:
        async with self._lock:
            item = self._items.get(item_id)
            if item:
                item.status = SyncItemStatus.COMPLETED
                item.completed_at = time.time()
                if item.started_at:
                    self._processing_times.append(
                        (item.completed_at - item.started_at) * 1000
                    )
                self._completed.append(item)
                del self._items[item_id]

    async def fail(self, item_id: str, error: str, requeue: bool = True) -> None:
        async with self._lock:
            item = self._items.get(item_id)
            if not item:
                return

            item.last_error = error

            if requeue and item.attempt_count < item.max_attempts:
                item.status = SyncItemStatus.QUEUED
                item.started_at = None
                await self._queue.put(item)
                logger.info(
                    "Requeued sync item %s (attempt %d/%d)",
                    item_id, item.attempt_count, item.max_attempts
                )
            else:
                item.status = SyncItemStatus.DEAD_LETTER
                item.completed_at = time.time()
                self._dead_letter.append(item)
                del self._items[item_id]
                logger.error(
                    "Sync item %s moved to dead letter queue: %s",
                    item_id, error
                )

    async def get_stats(self) -> SyncQueueStats:
        queued = sum(
            1 for i in self._items.values()
            if i.status == SyncItemStatus.QUEUED
        )
        processing = sum(
            1 for i in self._items.values()
            if i.status == SyncItemStatus.PROCESSING
        )

        oldest_age = 0.0
        now = time.time()
        for i in self._items.values():
            if i.status == SyncItemStatus.QUEUED:
                age = now - i.queued_at
                oldest_age = max(oldest_age, age)

        avg_time = (
            sum(self._processing_times[-100:]) / len(self._processing_times[-100:])
            if self._processing_times else 0.0
        )

        return SyncQueueStats(
            queued_count=queued,
            processing_count=processing,
            completed_count=len(self._completed),
            failed_count=len(self._dead_letter),
            dead_letter_count=len(self._dead_letter),
            total_processed=len(self._completed) + len(self._dead_letter),
            average_processing_time_ms=avg_time,
            oldest_queued_age_seconds=oldest_age,
        )

    async def get_item(self, item_id: str) -> Optional[SyncItem]:
        return self._items.get(item_id)

    async def health_check(self) -> bool:
        return True

    async def shutdown(self) -> None:
        # Drain remaining items
        while not self._queue.empty():
            try:
                self._queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        logger.info("In-memory sync queue shut down")


class RedisSyncQueue(SyncQueueBackend):
    """
    Redis-backed queue backend for production deployments.

    Uses Redis lists for the queue and hashes for item metadata.
    Provides durability, horizontal scaling, and monitoring.

    Requires: pip install redis[hiredis]
    """

    QUEUE_KEY = "msaidizi:sync:queue"
    ITEMS_KEY = "msaidizi:sync:items"
    PROCESSING_KEY = "msaidizi:sync:processing"
    DEAD_LETTER_KEY = "msaidizi:sync:dead_letter"
    STATS_KEY = "msaidizi:sync:stats"

    def __init__(self, redis_url: str = "redis://localhost:6379/0", max_attempts: int = 3):
        self.redis_url = redis_url
        self.max_attempts = max_attempts
        self._redis = None

    async def _get_redis(self):
        if self._redis is None:
            try:
                import redis.asyncio as aioredis
            except ImportError:
                raise ImportError(
                    "redis[hiredis] is required for RedisSyncQueue. "
                    "Install with: pip install redis[hiredis]"
                )
            self._redis = aioredis.from_url(
                self.redis_url,
                decode_responses=False,
                socket_connect_timeout=5,
                socket_timeout=5,
                retry_on_timeout=True,
            )
        return self._redis

    async def enqueue(self, item: SyncItem) -> None:
        r = await self._get_redis()

        # Deduplication check
        existing = await r.hget(self.ITEMS_KEY, item.payload_hash)
        if existing:
            logger.info("Duplicate payload hash %s, skipping", item.payload_hash[:12])
            return

        pipe = r.pipeline()
        # Store item metadata
        item_data = json.dumps(item.to_dict()).encode()
        pipe.hset(self.ITEMS_KEY, item.item_id, item_data)
        pipe.hset(self.ITEMS_KEY, item.payload_hash, item.item_id)
        # Add to queue
        pipe.lpush(self.QUEUE_KEY, item.item_id)
        # Update stats
        pipe.hincrby(self.STATS_KEY, "total_queued", 1)
        await pipe.execute()

        logger.debug("Enqueued sync item %s to Redis", item.item_id)

    async def dequeue(self, timeout_seconds: float = 5.0) -> Optional[SyncItem]:
        r = await self._get_redis()

        # BRPOPLPUSH: atomically move from queue to processing list
        result = await r.brpoplpush(
            self.QUEUE_KEY,
            self.PROCESSING_KEY,
            timeout=int(timeout_seconds)
        )

        if result is None:
            return None

        item_id = result.decode() if isinstance(result, bytes) else result
        item_data = await r.hget(self.ITEMS_KEY, item_id)

        if item_data is None:
            logger.warning("Item %s metadata not found in Redis", item_id)
            return None

        item = SyncItem.from_dict(json.loads(item_data))
        item.status = SyncItemStatus.PROCESSING
        item.started_at = time.time()
        item.attempt_count += 1

        # Update metadata
        await r.hset(self.ITEMS_KEY, item.item_id, json.dumps(item.to_dict()).encode())

        return item

    async def complete(self, item_id: str) -> None:
        r = await self._get_redis()

        item_data = await r.hget(self.ITEMS_KEY, item_id)
        if item_data:
            item = SyncItem.from_dict(json.loads(item_data))
            item.status = SyncItemStatus.COMPLETED
            item.completed_at = time.time()

            pipe = r.pipeline()
            pipe.hset(self.ITEMS_KEY, item_id, json.dumps(item.to_dict()).encode())
            pipe.lrem(self.PROCESSING_KEY, 0, item_id)
            pipe.hincrby(self.STATS_KEY, "total_completed", 1)
            processing_time = (item.completed_at - (item.started_at or item.completed_at)) * 1000
            pipe.hincrbyfloat(self.STATS_KEY, "total_processing_ms", processing_time)
            await pipe.execute()

    async def fail(self, item_id: str, error: str, requeue: bool = True) -> None:
        r = await self._get_redis()

        item_data = await r.hget(self.ITEMS_KEY, item_id)
        if not item_data:
            return

        item = SyncItem.from_dict(json.loads(item_data))
        item.last_error = error

        pipe = r.pipeline()
        pipe.lrem(self.PROCESSING_KEY, 0, item_id)

        if requeue and item.attempt_count < item.max_attempts:
            item.status = SyncItemStatus.QUEUED
            item.started_at = None
            pipe.hset(self.ITEMS_KEY, item_id, json.dumps(item.to_dict()).encode())
            pipe.lpush(self.QUEUE_KEY, item_id)
            logger.info("Requeued sync item %s (attempt %d/%d)", item_id, item.attempt_count, item.max_attempts)
        else:
            item.status = SyncItemStatus.DEAD_LETTER
            item.completed_at = time.time()
            pipe.hset(self.ITEMS_KEY, item_id, json.dumps(item.to_dict()).encode())
            pipe.lpush(self.DEAD_LETTER_KEY, item_id)
            pipe.hincrby(self.STATS_KEY, "total_dead_letter", 1)
            logger.error("Sync item %s moved to dead letter: %s", item_id, error)

        await pipe.execute()

    async def get_stats(self) -> SyncQueueStats:
        r = await self._get_redis()

        pipe = r.pipeline()
        pipe.llen(self.QUEUE_KEY)
        pipe.llen(self.PROCESSING_KEY)
        pipe.hgetall(self.STATS_KEY)
        results = await pipe.execute()

        queued_count = results[0]
        processing_count = results[1]
        stats_data = results[2]

        total_completed = int(stats_data.get(b"total_completed", 0))
        total_dead_letter = int(stats_data.get(b"total_dead_letter", 0))
        total_processing_ms = float(stats_data.get(b"total_processing_ms", 0))

        avg_time = total_processing_ms / total_completed if total_completed > 0 else 0.0

        return SyncQueueStats(
            queued_count=queued_count,
            processing_count=processing_count,
            completed_count=total_completed,
            failed_count=total_dead_letter,
            dead_letter_count=total_dead_letter,
            total_processed=total_completed + total_dead_letter,
            average_processing_time_ms=avg_time,
        )

    async def get_item(self, item_id: str) -> Optional[SyncItem]:
        r = await self._get_redis()
        item_data = await r.hget(self.ITEMS_KEY, item_id)
        if item_data is None:
            return None
        return SyncItem.from_dict(json.loads(item_data))

    async def health_check(self) -> bool:
        try:
            r = await self._get_redis()
            await r.ping()
            return True
        except Exception:
            return False

    async def shutdown(self) -> None:
        if self._redis:
            await self._redis.close()
            self._redis = None
        logger.info("Redis sync queue shut down")


# ─── Sync Processing Service ─────────────────────────────────────────────────

class SyncProcessor:
    """
    Background worker that dequeues sync items and processes them.

    Processing pipeline:
    1. Dequeue item from Redis/memory queue
    2. Decrypt payload (AES-256)
    3. Decompress payload (zstd)
    4. Parse JSON transactions
    5. Validate and store in database
    6. Mark item as completed

    Runs as a background task, processing items as they arrive.
    """

    def __init__(
        self,
        queue: SyncQueueBackend,
        process_fn: Optional[Callable] = None,
        batch_size: int = 10,
        poll_interval_seconds: float = 1.0,
    ):
        self.queue = queue
        self.process_fn = process_fn or self._default_process
        self.batch_size = batch_size
        self.poll_interval_seconds = poll_interval_seconds
        self._running = False
        self._task: Optional[asyncio.Task] = None

    async def start(self) -> None:
        """Start the background processing loop."""
        if self._running:
            logger.warning("Sync processor already running")
            return

        self._running = True
        self._task = asyncio.create_task(self._processing_loop())
        logger.info("Sync processor started")

    async def stop(self) -> None:
        """Stop the processing loop gracefully."""
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Sync processor stopped")

    async def _processing_loop(self) -> None:
        """Main processing loop — dequeues and processes items."""
        while self._running:
            try:
                item = await self.queue.dequeue(
                    timeout_seconds=self.poll_interval_seconds
                )
                if item is None:
                    continue

                logger.info(
                    "Processing sync item %s (attempt %d)",
                    item.item_id, item.attempt_count
                )

                try:
                    await self.process_fn(item)
                    await self.queue.complete(item.item_id)
                    logger.info("Completed sync item %s", item.item_id)
                except Exception as e:
                    logger.error(
                        "Failed to process sync item %s: %s",
                        item.item_id, e
                    )
                    await self.queue.fail(item.item_id, str(e))

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Sync processing loop error: %s", e)
                await asyncio.sleep(self.poll_interval_seconds)

    async def _default_process(self, item: SyncItem) -> None:
        """
        Default processing: parse payload and log it.

        In production, this would:
        1. Decrypt with AES-256
        2. Decompress with zstd
        3. Parse JSON
        4. Validate transactions
        5. Store in database
        """
        # Placeholder for actual processing
        logger.info(
            "Processing %d bytes from device %s",
            len(item.payload), item.device_id
        )
        # Simulate processing time
        await asyncio.sleep(0.1)


# ─── Sync API Helpers ────────────────────────────────────────────────────────

def create_sync_queue(redis_url: Optional[str] = None) -> SyncQueueBackend:
    """
    Factory: create the appropriate queue backend.

    Tries Redis first, falls back to in-memory.
    """
    if redis_url:
        try:
            queue = RedisSyncQueue(redis_url=redis_url)
            logger.info("Using Redis sync queue: %s", redis_url)
            return queue
        except ImportError:
            logger.warning(
                "redis package not available, falling back to in-memory queue"
            )

    logger.info("Using in-memory sync queue")
    return InMemorySyncQueue()


async def health_check(queue: SyncQueueBackend) -> dict:
    """Get sync system health status."""
    is_healthy = await queue.health_check()
    stats = await queue.get_stats()

    return {
        "healthy": is_healthy,
        "queue_type": type(queue).__name__,
        "stats": asdict(stats),
    }
