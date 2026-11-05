#!/usr/bin/env python3
"""Check complete Android locale coverage and Java Formatter arguments (stdlib only)."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/res'
LOCALES = ('en', 'de', 'es', 'it', 'pt-rBR', 'ru', 'zh-rCN', 'ja', 'ar', 'hi', 'bn')
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])')

def arguments(value):
    result = {}
    next_index = 1
    for match in FORMAT.finditer(value):
        index, kind = match.groups()
        if kind in ('%', 'n'):
            continue
        index = int(index) if index else next_index
        next_index = index + 1
        kind = kind.lower()
        kind = 'integer' if kind in 'dox' else 'float' if kind in 'efga' else kind
        if index in result and result[index] != kind:
            raise ValueError(f'conflicting types for argument {index}')
        result[index] = kind
    return result

def resources(path):
    found = {}
    for element in ET.parse(path).getroot():
        if element.tag not in ('string', 'plurals') or element.get('translatable') == 'false':
            continue
        key = element.get('name')
        if key in found:
            raise ValueError(f'{path}: duplicate {key}')
        if element.tag == 'plurals':
            items = {item.get('quantity'): ''.join(item.itertext()) for item in element}
            if len(items) != len(element) or 'other' not in items:
                raise ValueError(f'{path}: invalid plural {key}')
        else:
            items = {'string': ''.join(element.itertext())}
        found[key] = (element.tag, items)
    return found

def check():
    base = resources(ROOT / 'values/strings.xml')
    errors = []
    for locale in LOCALES:
        translated = resources(ROOT / f'values-{locale}/strings.xml')
        for key in base.keys() ^ translated.keys():
            errors.append(f'{locale}: missing or extra key {key}')
        for key in base.keys() & translated.keys():
            tag, items = translated[key]
            base_tag, original = base[key]
            if tag != base_tag:
                errors.append(f'{locale}: resource type differs: {key}')
                continue
            for quantity, text in items.items():
                expected = arguments(original.get(quantity, original.get('other', '')))
                actual = arguments(text)
                # Arabic singular and dual can spell out the count without a numeral.
                omitted_count = tag == 'plurals' and locale == 'ar' and quantity in ('one', 'two')
                if actual != expected and not (omitted_count and not actual and all(t == 'integer' for t in expected.values())):
                    errors.append(f'{locale}: {key}/{quantity}: {actual} != {expected}')
                if not text.strip():
                    errors.append(f'{locale}: empty {key}/{quantity}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Translation checks passed: {len(base)} resources × {len(LOCALES) + 1} languages.')
    return 0

if __name__ == '__main__':
    sys.exit(check())
