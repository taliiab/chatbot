#!/bin/sh
set -eu

# Rasa só aceita ${VAR} simples. Expandir antes de qualquer comando rasa.
python3 - <<'PY'
import os
from pathlib import Path

for filename in ("endpoints.yml", "credentials.yml"):
    path = Path(f"/app/{filename}")
    path.write_text(os.path.expandvars(path.read_text(encoding="utf-8")), encoding="utf-8")
PY

MODEL_DIR="${RASA_MODEL_DIR:-/app/models}"

if [ -n "${RASA_MODEL_FILE:-}" ]; then
  MODEL_PATH="${RASA_MODEL_FILE}"
  case "${MODEL_PATH}" in
    /*) ;;
    *) MODEL_PATH="${MODEL_DIR}/${MODEL_PATH}" ;;
  esac
else
  MODEL_PATH="$(ls -1t "${MODEL_DIR}"/*.tar.gz 2>/dev/null | head -n 1 || true)"
fi

if [ -z "${MODEL_PATH}" ] || [ ! -f "${MODEL_PATH}" ]; then
  echo "[rasa-bot] Nenhum modelo encontrado em ${MODEL_DIR}."
  echo "[rasa-bot] Treine manualmente com: docker compose --profile train run --rm rasa-train"
  echo "[rasa-bot] Depois, inicie novamente o serviço rasa."
  exit 0
fi

exec rasa run \
  --model "${MODEL_PATH}" \
  --enable-api \
  --cors '*' \
  --port 5005 \
  --debug