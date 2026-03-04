#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Uso:
  scripts/fix-jrxml-compat.sh [--check] <archivo-o-directorio> [...]

Descripción:
  Aplica (o valida con --check) ajustes de compatibilidad JRXML:
  1) org.apache.commons.codec.binary.Base64 -> java.util.Base64
  2) Base64.decodeBase64(...) -> Base64.getDecoder().decode(...)
  3) contains("level='2'") -> contains("level=2") (y cualquier dígito)
  4) Expresión null-safe para IMGFIRMA64 con IMGLOGO null/vacío
EOF
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

CHECK_ONLY=false
if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=true
  shift
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

collect_files() {
  local target
  for target in "$@"; do
    if [[ -f "$target" ]]; then
      [[ "$target" == *.jrxml ]] && printf '%s\n' "$target"
    elif [[ -d "$target" ]]; then
      find "$target" -type f -name "*.jrxml"
    else
      printf 'Ruta no encontrada: %s\n' "$target" >&2
      exit 2
    fi
  done
}

apply_fixes_to_file() {
  local file="$1"
  local tmp
  tmp="$(mktemp)"
  cp "$file" "$tmp"

  # 1) Import Base64 moderno.
  perl -0777 -i -pe 's{<import value="org\.apache\.commons\.codec\.binary\.Base64"\s*/>}{<import value="java.util.Base64"/>}g' "$tmp"
  if ! rg -q '<import value="java\.util\.Base64"\s*/>' "$tmp"; then
    perl -0777 -i -pe 's{(<import value="javax\.imageio\.ImageIO"\s*/>)}{$1\n\t<import value="java.util.Base64"/>}s' "$tmp"
  fi

  # 2) decodeBase64 -> getDecoder().decode
  perl -0777 -i -pe 's{Base64\.decodeBase64\s*\(}{Base64.getDecoder().decode(}g' "$tmp"

  # 3) contains("level='\''2'\''") -> contains("level=2")
  perl -0777 -i -pe 's{contains\("level='\''([0-9]+)'\''"\)}{contains("level=$1")}g' "$tmp"

  # 4) IMGLOGO null-safe para variable IMGFIRMA64 (idempotente).
  if ! rg -q '\$P\{IMGLOGO\} == null \|\| \$P\{IMGLOGO\}\.trim\(\)\.isEmpty\(\)' "$tmp"; then
    perl -0777 -i -pe 's{ImageIO\.read\(new ByteArrayInputStream\(Base64\.getDecoder\(\)\.decode\(\$P\{IMGLOGO\}\.getBytes\("UTF-8"\)\)\)\)}{(\$P{IMGLOGO} == null || \$P{IMGLOGO}.trim().isEmpty()) ? null : ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(\$P{IMGLOGO}.getBytes("UTF-8"))))}g' "$tmp"
  fi

  if cmp -s "$file" "$tmp"; then
    rm -f "$tmp"
    return 1
  fi

  if [[ "$CHECK_ONLY" == true ]]; then
    printf '[CAMBIO] %s\n' "$file"
    rm -f "$tmp"
    return 0
  fi

  mv "$tmp" "$file"
  printf '[OK] %s\n' "$file"
  return 0
}

changed=0
total=0

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  total=$((total + 1))
  if apply_fixes_to_file "$file"; then
    changed=$((changed + 1))
  fi
done < <(collect_files "$@" | sort -u)

if [[ "$CHECK_ONLY" == true ]]; then
  printf 'Check completado. Archivos evaluados: %d, con cambios pendientes: %d\n' "$total" "$changed"
else
  printf 'Proceso completado. Archivos evaluados: %d, modificados: %d\n' "$total" "$changed"
fi
