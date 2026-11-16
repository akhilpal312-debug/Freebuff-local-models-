"""Vercel Python entrypoint.

The ASGI application lives in ``app.main``; Vercel serves the exported ``app``.
"""

import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from app.main import app  # noqa: E402,F401
