import re

_STREET_RE = re.compile(
    r"\bonto\s+(.+)$|\bon\s+(.+)$|\btoward\s+(.+)$", re.IGNORECASE
)

def extract_street_hint(instruction: str) -> str:
    """
    Best-effort street name hint from the human-readable instruction.
    Example: "Turn left onto Swanston St" -> "Swanston St"
    """
    lines = [line.strip() for line in instruction.splitlines() if line.strip()]
    primary_instruction = lines[0] if lines else ""

    m = _STREET_RE.search(primary_instruction)
    if not m:
        return primary_instruction
    # pick first non-empty group
    for g in m.groups():
        if g:
            return g.strip()
    return primary_instruction
