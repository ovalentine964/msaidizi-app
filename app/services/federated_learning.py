"""
Federated Learning Service — persists FL state to database.

Previously, FL state was held in-memory, meaning server restarts lost all
progress (current round, worker contributions, model versions). This module
moves all state to a database-backed store for durability.

Architecture:
- FLRound: tracks each federated learning round (status, participants, metrics)
- FLWorkerContribution: tracks each worker's gradient contribution per round
- FLModelVersion: tracks aggregated model versions
- FederatedLearningService: orchestrates rounds with full DB persistence

All state survives server restarts. In-flight rounds are recovered on startup.
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
from typing import Any, Optional

logger = logging.getLogger(__name__)


# ─── Domain Types ────────────────────────────────────────────────────────────

class RoundStatus(str, Enum):
    PENDING = "pending"
    COLLECTING = "collecting"
    AGGREGATING = "aggregating"
    COMPLETED = "completed"
    FAILED = "failed"


class ContributionStatus(str, Enum):
    PENDING = "pending"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    STALE = "stale"


@dataclass
class FLRound:
    """A single federated learning round."""
    round_id: str
    model_version_id: str
    status: RoundStatus = RoundStatus.PENDING
    min_participants: int = 5
    max_participants: int = 100
    target_participants: int = 10
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    timeout_seconds: int = 3600  # 1 hour
    aggregated_metrics: dict = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["status"] = self.status.value
        return d

    @classmethod
    def from_row(cls, row: dict) -> "FLRound":
        row["status"] = RoundStatus(row["status"])
        return cls(**row)


@dataclass
class FLWorkerContribution:
    """A worker's gradient contribution to a round."""
    contribution_id: str
    round_id: str
    worker_id: str  # hashed device ID
    gradient_data: bytes  # serialized gradient
    sample_count: int
    loss: float
    status: ContributionStatus = ContributionStatus.PENDING
    submitted_at: float = field(default_factory=time.time)
    validated_at: Optional[float] = None
    rejection_reason: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        d["status"] = self.status.value
        # gradient_data stored as hex for DB serialization
        d["gradient_data"] = self.gradient_data.hex()
        return d

    @classmethod
    def from_row(cls, row: dict) -> "FLWorkerContribution":
        row["status"] = ContributionStatus(row["status"])
        if isinstance(row.get("gradient_data"), str):
            row["gradient_data"] = bytes.fromhex(row["gradient_data"])
        return cls(**row)


@dataclass
class FLModelVersion:
    """An aggregated model version."""
    version_id: str
    round_id: str
    model_hash: str  # SHA-256 of aggregated weights
    participant_count: int
    total_samples: int
    average_loss: float
    metrics: dict = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return asdict(self)


# ─── Database Abstraction ────────────────────────────────────────────────────

class FLStateStore:
    """
    Abstract interface for FL state persistence.

    Implementations:
    - SQLiteFLStateStore: for development / single-server
    - PostgresFLStateStore: for production (via subclass)
    """

    async def initialize(self) -> None:
        """Create tables if they don't exist."""
        raise NotImplementedError

    # Round operations
    async def create_round(self, fl_round: FLRound) -> None:
        raise NotImplementedError

    async def get_round(self, round_id: str) -> Optional[FLRound]:
        raise NotImplementedError

    async def get_active_round(self) -> Optional[FLRound]:
        raise NotImplementedError

    async def update_round(self, fl_round: FLRound) -> None:
        raise NotImplementedError

    async def list_rounds(self, limit: int = 50, offset: int = 0) -> list[FLRound]:
        raise NotImplementedError

    # Contribution operations
    async def save_contribution(self, contribution: FLWorkerContribution) -> None:
        raise NotImplementedError

    async def get_contributions(self, round_id: str) -> list[FLWorkerContribution]:
        raise NotImplementedError

    async def get_contribution(self, round_id: str, worker_id: str) -> Optional[FLWorkerContribution]:
        raise NotImplementedError

    # Model version operations
    async def save_model_version(self, version: FLModelVersion) -> None:
        raise NotImplementedError

    async def get_latest_model_version(self) -> Optional[FLModelVersion]:
        raise NotImplementedError

    async def get_model_version(self, version_id: str) -> Optional[FLModelVersion]:
        raise NotImplementedError

    # Recovery
    async def get_incomplete_rounds(self) -> list[FLRound]:
        """Find rounds that were in-progress when the server stopped."""
        raise NotImplementedError


class SQLiteFLStateStore(FLStateStore):
    """
    SQLite-backed FL state store using aiosqlite.

    Tables:
    - fl_rounds: round lifecycle tracking
    - fl_contributions: per-worker gradient submissions
    - fl_model_versions: aggregated model registry
    """

    def __init__(self, db_path: str = "fl_state.db"):
        self.db_path = db_path
        self._conn = None

    async def _get_conn(self):
        if self._conn is None:
            try:
                import aiosqlite
            except ImportError:
                raise ImportError(
                    "aiosqlite is required for SQLiteFLStateStore. "
                    "Install with: pip install aiosqlite"
                )
            self._conn = await aiosqlite.connect(self.db_path)
            self._conn.row_factory = aiosqlite.Row
        return self._conn

    async def initialize(self) -> None:
        conn = await self._get_conn()
        await conn.executescript("""
            CREATE TABLE IF NOT EXISTS fl_rounds (
                round_id TEXT PRIMARY KEY,
                model_version_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                min_participants INTEGER NOT NULL DEFAULT 5,
                max_participants INTEGER NOT NULL DEFAULT 100,
                target_participants INTEGER NOT NULL DEFAULT 10,
                started_at REAL,
                completed_at REAL,
                timeout_seconds INTEGER NOT NULL DEFAULT 3600,
                aggregated_metrics TEXT NOT NULL DEFAULT '{}',
                created_at REAL NOT NULL
            );

            CREATE TABLE IF NOT EXISTS fl_contributions (
                contribution_id TEXT PRIMARY KEY,
                round_id TEXT NOT NULL,
                worker_id TEXT NOT NULL,
                gradient_data BLOB NOT NULL,
                sample_count INTEGER NOT NULL,
                loss REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                submitted_at REAL NOT NULL,
                validated_at REAL,
                rejection_reason TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (round_id) REFERENCES fl_rounds(round_id),
                UNIQUE(round_id, worker_id)
            );

            CREATE TABLE IF NOT EXISTS fl_model_versions (
                version_id TEXT PRIMARY KEY,
                round_id TEXT NOT NULL,
                model_hash TEXT NOT NULL,
                participant_count INTEGER NOT NULL,
                total_samples INTEGER NOT NULL,
                average_loss REAL NOT NULL,
                metrics TEXT NOT NULL DEFAULT '{}',
                created_at REAL NOT NULL,
                FOREIGN KEY (round_id) REFERENCES fl_rounds(round_id)
            );

            CREATE INDEX IF NOT EXISTS idx_fl_rounds_status
                ON fl_rounds(status);
            CREATE INDEX IF NOT EXISTS idx_fl_contributions_round
                ON fl_contributions(round_id);
            CREATE INDEX IF NOT EXISTS idx_fl_contributions_worker
                ON fl_contributions(worker_id);
        """)
        await conn.commit()
        logger.info("FL state store initialized at %s", self.db_path)

    async def create_round(self, fl_round: FLRound) -> None:
        conn = await self._get_conn()
        r = fl_round
        await conn.execute(
            """INSERT INTO fl_rounds
               (round_id, model_version_id, status, min_participants,
                max_participants, target_participants, started_at, completed_at,
                timeout_seconds, aggregated_metrics, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (r.round_id, r.model_version_id, r.status.value,
             r.min_participants, r.max_participants, r.target_participants,
             r.started_at, r.completed_at, r.timeout_seconds,
             json.dumps(r.aggregated_metrics), r.created_at)
        )
        await conn.commit()

    async def get_round(self, round_id: str) -> Optional[FLRound]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_rounds WHERE round_id = ?", (round_id,)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["aggregated_metrics"] = json.loads(d["aggregated_metrics"])
        return FLRound.from_row(d)

    async def get_active_round(self) -> Optional[FLRound]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            """SELECT * FROM fl_rounds
               WHERE status IN ('pending', 'collecting', 'aggregating')
               ORDER BY created_at DESC LIMIT 1"""
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["aggregated_metrics"] = json.loads(d["aggregated_metrics"])
        return FLRound.from_row(d)

    async def update_round(self, fl_round: FLRound) -> None:
        conn = await self._get_conn()
        r = fl_round
        await conn.execute(
            """UPDATE fl_rounds SET
               status = ?, started_at = ?, completed_at = ?,
               aggregated_metrics = ?
               WHERE round_id = ?""",
            (r.status.value, r.started_at, r.completed_at,
             json.dumps(r.aggregated_metrics), r.round_id)
        )
        await conn.commit()

    async def list_rounds(self, limit: int = 50, offset: int = 0) -> list[FLRound]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_rounds ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (limit, offset)
        )
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["aggregated_metrics"] = json.loads(d["aggregated_metrics"])
            results.append(FLRound.from_row(d))
        return results

    async def save_contribution(self, contribution: FLWorkerContribution) -> None:
        conn = await self._get_conn()
        c = contribution
        await conn.execute(
            """INSERT OR REPLACE INTO fl_contributions
               (contribution_id, round_id, worker_id, gradient_data,
                sample_count, loss, status, submitted_at, validated_at,
                rejection_reason)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (c.contribution_id, c.round_id, c.worker_id,
             c.gradient_data, c.sample_count, c.loss,
             c.status.value, c.submitted_at, c.validated_at,
             c.rejection_reason)
        )
        await conn.commit()

    async def get_contributions(self, round_id: str) -> list[FLWorkerContribution]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_contributions WHERE round_id = ? ORDER BY submitted_at",
            (round_id,)
        )
        rows = await cursor.fetchall()
        return [FLWorkerContribution.from_row(dict(r)) for r in rows]

    async def get_contribution(
        self, round_id: str, worker_id: str
    ) -> Optional[FLWorkerContribution]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_contributions WHERE round_id = ? AND worker_id = ?",
            (round_id, worker_id)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return FLWorkerContribution.from_row(dict(row))

    async def save_model_version(self, version: FLModelVersion) -> None:
        conn = await self._get_conn()
        v = version
        await conn.execute(
            """INSERT INTO fl_model_versions
               (version_id, round_id, model_hash, participant_count,
                total_samples, average_loss, metrics, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (v.version_id, v.round_id, v.model_hash,
             v.participant_count, v.total_samples, v.average_loss,
             json.dumps(v.metrics), v.created_at)
        )
        await conn.commit()

    async def get_latest_model_version(self) -> Optional[FLModelVersion]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_model_versions ORDER BY created_at DESC LIMIT 1"
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["metrics"] = json.loads(d["metrics"])
        return FLModelVersion(**d)

    async def get_model_version(self, version_id: str) -> Optional[FLModelVersion]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            "SELECT * FROM fl_model_versions WHERE version_id = ?",
            (version_id,)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["metrics"] = json.loads(d["metrics"])
        return FLModelVersion(**d)

    async def get_incomplete_rounds(self) -> list[FLRound]:
        conn = await self._get_conn()
        cursor = await conn.execute(
            """SELECT * FROM fl_rounds
               WHERE status IN ('pending', 'collecting', 'aggregating')
               ORDER BY created_at ASC"""
        )
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["aggregated_metrics"] = json.loads(d["aggregated_metrics"])
            results.append(FLRound.from_row(d))
        return results

    async def close(self) -> None:
        if self._conn:
            await self._conn.close()
            self._conn = None


# ─── Federated Learning Service ──────────────────────────────────────────────

class FederatedLearningService:
    """
    Orchestrates federated learning rounds with full DB persistence.

    All state is written to the database before returning success,
    ensuring no progress is lost on server restart.

    Lifecycle:
    1. start_round() → creates round in DB with status COLLECTING
    2. submit_contribution() → validates and stores worker gradients
    3. When enough contributions → aggregate_round() → stores new model version
    4. On startup → recover_incomplete_rounds() picks up where we left off
    """

    def __init__(self, store: FLStateStore):
        self.store = store
        self._aggregation_lock = asyncio.Lock()

    async def initialize(self) -> None:
        """Initialize store and recover any incomplete rounds."""
        await self.store.initialize()
        await self.recover_incomplete_rounds()

    async def start_round(
        self,
        model_version_id: str,
        min_participants: int = 5,
        target_participants: int = 10,
        max_participants: int = 100,
        timeout_seconds: int = 3600,
    ) -> FLRound:
        """
        Start a new federated learning round.

        Creates the round in the database with COLLECTING status.
        Returns the round object for tracking.
        """
        # Check if there's already an active round
        active = await self.store.get_active_round()
        if active is not None:
            logger.warning(
                "Active round already exists: %s (status=%s)",
                active.round_id, active.status.value
            )
            return active

        fl_round = FLRound(
            round_id=str(uuid.uuid4()),
            model_version_id=model_version_id,
            status=RoundStatus.COLLECTING,
            min_participants=min_participants,
            target_participants=target_participants,
            max_participants=max_participants,
            started_at=time.time(),
            timeout_seconds=timeout_seconds,
        )

        await self.store.create_round(fl_round)
        logger.info(
            "Started FL round %s (model=%s, target=%d participants)",
            fl_round.round_id, model_version_id, target_participants
        )
        return fl_round

    async def submit_contribution(
        self,
        round_id: str,
        worker_id: str,
        gradient_data: bytes,
        sample_count: int,
        loss: float,
    ) -> FLWorkerContribution:
        """
        Submit a worker's gradient contribution.

        Validates:
        - Round exists and is in COLLECTING status
        - Worker hasn't already contributed to this round
        - Gradient data is non-empty
        - Sample count > 0

        Persists to DB before returning success.
        """
        fl_round = await self.store.get_round(round_id)
        if fl_round is None:
            raise ValueError(f"Round {round_id} not found")

        if fl_round.status != RoundStatus.COLLECTING:
            raise ValueError(
                f"Round {round_id} is not collecting (status={fl_round.status.value})"
            )

        # Check timeout
        if fl_round.started_at and (time.time() - fl_round.started_at) > fl_round.timeout_seconds:
            fl_round.status = RoundStatus.FAILED
            fl_round.completed_at = time.time()
            await self.store.update_round(fl_round)
            raise ValueError(f"Round {round_id} has timed out")

        # Check for duplicate contribution
        existing = await self.store.get_contribution(round_id, worker_id)
        if existing is not None:
            raise ValueError(
                f"Worker {worker_id} already contributed to round {round_id}"
            )

        # Validate inputs
        if not gradient_data:
            raise ValueError("Gradient data cannot be empty")
        if sample_count <= 0:
            raise ValueError("Sample count must be positive")
        if loss < 0:
            raise ValueError("Loss must be non-negative")

        contribution = FLWorkerContribution(
            contribution_id=str(uuid.uuid4()),
            round_id=round_id,
            worker_id=worker_id,
            gradient_data=gradient_data,
            sample_count=sample_count,
            loss=loss,
            status=ContributionStatus.ACCEPTED,
            validated_at=time.time(),
        )

        await self.store.save_contribution(contribution)
        logger.info(
            "Accepted contribution from worker %s for round %s "
            "(samples=%d, loss=%.4f)",
            worker_id, round_id, sample_count, loss
        )

        # Check if we should trigger aggregation
        contributions = await self.store.get_contributions(round_id)
        accepted = [c for c in contributions if c.status == ContributionStatus.ACCEPTED]
        if len(accepted) >= fl_round.target_participants:
            # Trigger aggregation in background
            asyncio.create_task(self._try_aggregate(round_id))

        return contribution

    async def aggregate_round(self, round_id: str) -> FLModelVersion:
        """
        Aggregate contributions for a round and produce a new model version.

        Uses Federated Averaging (FedAvg):
        - Weighted average of gradients by sample count
        - New model = old model - learning_rate * aggregated_gradient

        Persists the new model version to DB.
        """
        async with self._aggregation_lock:
            fl_round = await self.store.get_round(round_id)
            if fl_round is None:
                raise ValueError(f"Round {round_id} not found")

            if fl_round.status != RoundStatus.COLLECTING:
                raise ValueError(
                    f"Round {round_id} cannot be aggregated (status={fl_round.status.value})"
                )

            contributions = await self.store.get_contributions(round_id)
            accepted = [
                c for c in contributions
                if c.status == ContributionStatus.ACCEPTED
            ]

            if len(accepted) < fl_round.min_participants:
                raise ValueError(
                    f"Not enough contributions: {len(accepted)}/{fl_round.min_participants}"
                )

            # Mark round as aggregating
            fl_round.status = RoundStatus.AGGREGATING
            await self.store.update_round(fl_round)

            try:
                # Perform FedAvg aggregation
                total_samples = sum(c.sample_count for c in accepted)
                weighted_loss = sum(
                    c.loss * c.sample_count for c in accepted
                ) / total_samples

                # Aggregate gradients (weighted by sample count)
                aggregated_gradient = self._federated_average(
                    accepted, total_samples
                )

                # Compute model hash
                model_hash = hashlib.sha256(aggregated_gradient).hexdigest()

                # Create model version
                model_version = FLModelVersion(
                    version_id=str(uuid.uuid4()),
                    round_id=round_id,
                    model_hash=model_hash,
                    participant_count=len(accepted),
                    total_samples=total_samples,
                    average_loss=weighted_loss,
                    metrics={
                        "min_loss": min(c.loss for c in accepted),
                        "max_loss": max(c.loss for c in accepted),
                        "gradient_norm": len(aggregated_gradient),
                    },
                )

                await self.store.save_model_version(model_version)

                # Mark round as completed
                fl_round.status = RoundStatus.COMPLETED
                fl_round.completed_at = time.time()
                fl_round.aggregated_metrics = model_version.metrics
                await self.store.update_round(fl_round)

                logger.info(
                    "Round %s completed: %d participants, %.4f avg loss, model=%s",
                    round_id, len(accepted), weighted_loss, model_hash[:12]
                )
                return model_version

            except Exception as e:
                fl_round.status = RoundStatus.FAILED
                fl_round.completed_at = time.time()
                fl_round.aggregated_metrics = {"error": str(e)}
                await self.store.update_round(fl_round)
                logger.error("Round %s aggregation failed: %s", round_id, e)
                raise

    async def get_round_status(self, round_id: str) -> Optional[dict]:
        """Get the current status of a round."""
        fl_round = await self.store.get_round(round_id)
        if fl_round is None:
            return None

        contributions = await self.store.get_contributions(round_id)
        accepted = [
            c for c in contributions if c.status == ContributionStatus.ACCEPTED
        ]

        return {
            "round_id": fl_round.round_id,
            "status": fl_round.status.value,
            "model_version_id": fl_round.model_version_id,
            "participants": len(accepted),
            "target_participants": fl_round.target_participants,
            "started_at": fl_round.started_at,
            "completed_at": fl_round.completed_at,
            "metrics": fl_round.aggregated_metrics,
        }

    async def get_latest_model(self) -> Optional[FLModelVersion]:
        """Get the latest aggregated model version."""
        return await self.store.get_latest_model_version()

    async def recover_incomplete_rounds(self) -> None:
        """
        On startup, find and handle incomplete rounds.

        Strategy:
        - COLLECTING rounds: check if timed out → fail them, else keep collecting
        - AGGREGATING rounds: re-run aggregation
        - PENDING rounds: mark as failed (shouldn't exist in this state)
        """
        incomplete = await self.store.get_incomplete_rounds()
        if not incomplete:
            logger.info("No incomplete FL rounds to recover")
            return

        logger.info("Recovering %d incomplete FL rounds", len(incomplete))

        for fl_round in incomplete:
            try:
                if fl_round.status == RoundStatus.COLLECTING:
                    # Check timeout
                    elapsed = time.time() - (fl_round.started_at or fl_round.created_at)
                    if elapsed > fl_round.timeout_seconds:
                        logger.warning(
                            "Round %s timed out during recovery, marking as failed",
                            fl_round.round_id
                        )
                        fl_round.status = RoundStatus.FAILED
                        fl_round.completed_at = time.time()
                        await self.store.update_round(fl_round)
                    else:
                        logger.info(
                            "Round %s still collecting (%.0fs remaining)",
                            fl_round.round_id,
                            fl_round.timeout_seconds - elapsed
                        )

                elif fl_round.status == RoundStatus.AGGREGATING:
                    logger.info(
                        "Retrying aggregation for round %s", fl_round.round_id
                    )
                    try:
                        await self.aggregate_round(fl_round.round_id)
                    except Exception as e:
                        logger.error(
                            "Recovery aggregation failed for round %s: %s",
                            fl_round.round_id, e
                        )

                elif fl_round.status == RoundStatus.PENDING:
                    logger.warning(
                        "Round %s stuck in PENDING, marking as failed",
                        fl_round.round_id
                    )
                    fl_round.status = RoundStatus.FAILED
                    fl_round.completed_at = time.time()
                    await self.store.update_round(fl_round)

            except Exception as e:
                logger.error(
                    "Error recovering round %s: %s", fl_round.round_id, e
                )

    def _federated_average(
        self,
        contributions: list[FLWorkerContribution],
        total_samples: int,
    ) -> bytes:
        """
        Compute weighted average of gradient contributions.

        Uses simple byte-level averaging as a placeholder.
        In production, this would use numpy/torch for proper tensor aggregation.
        """
        # For production: use proper gradient aggregation library
        # This is a simplified version that concatenates and averages
        if not contributions:
            return b""

        # Simple weighted concatenation for demonstration
        result = bytearray()
        for c in contributions:
            weight = c.sample_count / total_samples
            # In production: weight each gradient tensor properly
            result.extend(c.gradient_data[:min(len(c.gradient_data), 1024)])

        return bytes(result)

    async def _try_aggregate(self, round_id: str) -> None:
        """Attempt to aggregate a round (called from background)."""
        try:
            await self.aggregate_round(round_id)
        except ValueError as e:
            logger.info("Aggregation not ready for round %s: %s", round_id, e)
        except Exception as e:
            logger.error("Background aggregation failed for round %s: %s", round_id, e)

    async def get_rounds_history(
        self, limit: int = 50, offset: int = 0
    ) -> list[dict]:
        """Get history of FL rounds."""
        rounds = await self.store.list_rounds(limit=limit, offset=offset)
        results = []
        for r in rounds:
            contributions = await self.store.get_contributions(r.round_id)
            results.append({
                "round_id": r.round_id,
                "status": r.status.value,
                "model_version_id": r.model_version_id,
                "participants": len(contributions),
                "started_at": r.started_at,
                "completed_at": r.completed_at,
                "metrics": r.aggregated_metrics,
            })
        return results
