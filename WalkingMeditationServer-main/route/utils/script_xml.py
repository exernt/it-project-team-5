from __future__ import annotations

from xml.etree import ElementTree


def normalize_script_xml(script: str) -> str:
    normalized = (script or "").strip()
    if normalized.startswith("```"):
        first_newline = normalized.find("\n")
        if first_newline >= 0:
            normalized = normalized[first_newline + 1 :]
        if normalized.rstrip().endswith("```"):
            normalized = normalized.rstrip()[:-3].rstrip()

    try:
        root = ElementTree.fromstring(normalized)
    except ElementTree.ParseError as exc:
        raise ValueError("Generated meditation is not valid XML") from exc

    if root.tag != "meditation":
        raise ValueError("Generated meditation must use a <meditation> root")
    if not root.findall(".//narration"):
        raise ValueError("Generated meditation contains no narration blocks")
    return normalized
