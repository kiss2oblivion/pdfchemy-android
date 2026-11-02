"""Reject an undersized hosted runner before the emulator's boot timeout starts."""
from pathlib import Path
import shutil

# The API 30 image requires 7.2 GiB even with a 4 GiB requested partition.
# Reserve additional space for the SDK, emulator and instrumentation evidence.
MINIMUM_FREE_BYTES = 10 * 1024 ** 3


def require_headroom(free_bytes):
    if free_bytes < MINIMUM_FREE_BYTES:
        raise RuntimeError(
            f"Android emulator needs at least {MINIMUM_FREE_BYTES} free bytes; "
            f"runner has {free_bytes}. No tests have started."
        )


if __name__ == '__main__':
    free = shutil.disk_usage(Path.home()).free
    require_headroom(free)
    print(f"Emulator disk preflight passed: {free} free bytes")
