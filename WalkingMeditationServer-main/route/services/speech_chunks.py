"""Split narration without losing text, XML escaping, pauses, or enclosing SSML."""
import re
from copy import deepcopy
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

MAX_BLOCK_CHARACTERS = 60_000
SPOKEN_LIMIT = 2800
TOTAL_LIMIT = 5600


def split_speech(text: str, text_type: str) -> list[str]:
    if text_type == "text":
        if len(text) <= 3000:
            return [text]
        return _pack(text, None)
    root = ET.fromstring(text)
    if len(text) <= 6000 and len("".join(root.itertext())) <= 3000:
        return [text]
    return _pack(text, root)


def _tags(node):
    shallow = ET.Element(node.tag, node.attrib)
    serialized = ET.tostring(shallow, encoding="unicode", short_empty_elements=False)
    end = serialized.index(">") + 1
    return serialized[:end], serialized[end:]


def _pack(text, root):
    xml = root is not None
    wrapper = _tags(root) if xml else ("", "")
    active = []
    parts, chunks = [], []
    spoken = ""

    def render(extra="", stack=None):
        stack = active if stack is None else stack
        return wrapper[0] + "".join(parts) + extra + "".join(c for _, c in reversed(stack)) + wrapper[1]

    def fits(extra, additional_spoken=0, stack=None):
        return (len(spoken) + additional_spoken <= SPOKEN_LIMIT
                and len(render(extra, stack)) <= TOTAL_LIMIT)

    def flush():
        nonlocal parts, spoken
        if not spoken.strip():
            raise ValueError("SSML markup cannot fit in one synthesis chunk")
        chunks.append(render())
        parts = [opening for opening, _ in active]
        spoken = ""

    def append_text(value):
        nonlocal spoken
        # Keep paragraphs and sentences together where possible; then try words.
        units = re.split(r"(?<=\n\n)|(?<=[.!?])(?=\s)", value)
        for unit in units:
            append_unit(unit, 0)

    def append_unit(unit, level):
        nonlocal spoken
        if not unit:
            return
        encoded = escape(unit) if xml else unit
        if fits(encoded, len(unit)):
            parts.append(encoded)
            spoken += unit
            return
        if spoken.strip():
            flush()
            if fits(encoded, len(unit)):
                parts.append(encoded)
                spoken += unit
                return
        if level == 0:
            for word in re.findall(r"\S+\s*|\s+", unit):
                append_unit(word, 1)
        elif level == 1 and len(unit) > 1:
            # Last resort for long URLs/words. Python slices preserve Unicode code points.
            for char in unit:
                append_unit(char, 2)
        else:
            raise ValueError("SSML enclosing markup exceeds synthesis limits")

    def visit(node):
        if node.text:
            append_text(node.text)
        for child in node:
            opening, closing = _tags(child)
            # These elements describe an indivisible pronunciation/substitution.
            atomic_element = child.tag.rsplit("}", 1)[-1] in {"sub", "phoneme", "say-as"}
            if atomic_element or (not len(child) and not child.text):
                copy = deepcopy(child)
                copy.tail = None
                atomic = ET.tostring(copy, encoding="unicode")
                atomic_spoken = "".join(child.itertext())
                if not fits(atomic, len(atomic_spoken)):
                    flush()
                if not fits(atomic, len(atomic_spoken)):
                    raise ValueError("SSML element exceeds synthesis limits")
                parts.append(atomic)
                # Keep pronunciation nodes intact rather than repeating their attributes across chunks.
                add_atomic_speech(atomic_spoken)
            else:
                stack = active + [(opening, closing)]
                if not fits(opening, stack=stack):
                    flush()
                if not fits(opening, stack=stack):
                    raise ValueError("SSML element exceeds synthesis limits")
                parts.append(opening)
                active.append((opening, closing))
                visit(child)
                parts.append(closing)
                active.pop()
            if child.tail:
                append_text(child.tail)

    def add_atomic_speech(value):
        nonlocal spoken
        spoken += value

    if xml:
        visit(root)
    else:
        append_text(text)
    final = render()
    if spoken.strip():
        chunks.append(final)
    elif parts and chunks:
        # A rare trailing pause-only chunk must remain attached to spoken text.
        if xml:
            previous = ET.fromstring(chunks[-1])
            tail = ET.fromstring(final)
            last = previous[-1] if len(previous) else previous
            if len(previous):
                last.tail = (last.tail or "") + (tail.text or "")
            else:
                previous.text = (previous.text or "") + (tail.text or "")
            previous.extend(list(tail))
            merged = ET.tostring(previous, encoding="unicode")
        else:
            merged = chunks[-1] + final
        merged_spoken = "".join(ET.fromstring(merged).itertext()) if xml else merged
        if len(merged) > 6000 or len(merged_spoken) > 3000:
            raise ValueError("Trailing pauses exceed synthesis limits")
        chunks[-1] = merged
    if not chunks:
        raise ValueError("Narration contains no spoken text")
    return chunks
