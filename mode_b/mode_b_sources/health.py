import threading
import time
from pathlib import Path
from typing import Any, Dict, Optional

from .storage import atomic_write_json, safe_read_json


class CircuitBreaker:
    """持久化熔断状态：CLOSED -> OPEN(冷却) -> HALF_OPEN(单请求试探)"""

    def __init__(
        self,
        state_path: Path,
        failure_threshold: int = 3,
        cooldown_seconds: int = 600,
        clock=time.time,
    ):
        self.state_path = state_path
        self.failure_threshold = failure_threshold
        self.cooldown_seconds = cooldown_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._half_open_inflight = set()
        self._state: Dict[str, Dict[str, Any]] = safe_read_json(self.state_path) or {}

    def update_policy(self, failure_threshold: int, cooldown_seconds: int) -> None:
        with self._lock:
            self.failure_threshold = failure_threshold
            self.cooldown_seconds = cooldown_seconds

    def acquire_permission(self, source_id: str) -> str:
        """
        返回:
          - "CLOSED": 正常放行
          - "HALF_OPEN": 冷却期满，允许本请求作为唯一探针通过
          - "OPEN": 处于冷却期，或已有另一个线程正在执行 HALF_OPEN 探针
        """
        now = self._clock()
        with self._lock:
            entry = self._state.get(source_id)
            if not entry:
                return "CLOSED"
            cooldown_until = float(entry.get("cooldownUntil", 0.0))
            if cooldown_until <= 0.0:
                return "CLOSED"
            if now < cooldown_until:
                return "OPEN"
            if source_id in self._half_open_inflight:
                return "OPEN"
            self._half_open_inflight.add(source_id)
            return "HALF_OPEN"

    def record_success(self, source_id: str) -> None:
        with self._lock:
            self._half_open_inflight.discard(source_id)
            if source_id in self._state:
                self._state[source_id] = {
                    "failures": 0,
                    "cooldownUntil": 0.0,
                    "lastSuccessAt": self._clock(),
                    "lastError": None,
                }
                atomic_write_json(self.state_path, self._state)

    def record_failure(self, source_id: str, reason: str) -> bool:
        """记录源级故障。若本次刚好触发进入 OPEN 冷却状态，返回 True。"""
        now = self._clock()
        with self._lock:
            was_half_open = source_id in self._half_open_inflight
            self._half_open_inflight.discard(source_id)

            prev = self._state.get(source_id, {})
            prev_failures = int(prev.get("failures", 0))
            prev_cooldown = float(prev.get("cooldownUntil", 0.0))

            failures = prev_failures + 1
            tripped_now = False
            cooldown_until = 0.0

            if was_half_open or failures >= self.failure_threshold:
                cooldown_until = now + self.cooldown_seconds
                tripped_now = (prev_cooldown <= 0.0) or was_half_open

            self._state[source_id] = {
                "failures": failures,
                "cooldownUntil": cooldown_until,
                "lastFailAt": now,
                "lastError": reason,
            }
            atomic_write_json(self.state_path, self._state)
            return tripped_now

    def clear_source(self, source_id: str) -> None:
        with self._lock:
            self._half_open_inflight.discard(source_id)
            if source_id in self._state:
                del self._state[source_id]
                atomic_write_json(self.state_path, self._state)

    def reset_all(self) -> None:
        with self._lock:
            self._half_open_inflight.clear()
            self._state = {}
            atomic_write_json(self.state_path, self._state)

    def snapshot(self) -> Dict[str, Dict[str, Any]]:
        with self._lock:
            return {k: dict(v) for k, v in self._state.items()}
