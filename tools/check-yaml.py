#!/usr/bin/env python3
"""Parse every YAML in the repo. A broken workflow or inventory must fail here, not on a box."""
import glob
import sys

import yaml

files = sorted(
    glob.glob('infra/**/*.yml', recursive=True)
    + glob.glob('.github/**/*.yml', recursive=True)
)
bad = []
for f in files:
    try:
        yaml.safe_load(open(f, encoding='utf-8'))
    except Exception as e:  # noqa: BLE001 - the message is the point
        bad.append(f'{f}: {e}')

if bad:
    print('\n'.join(bad))
    sys.exit(1)
print(f'{len(files)} yaml files parse')
