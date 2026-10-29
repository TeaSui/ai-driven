#!/usr/bin/env python3
"""Local setup diagnostics: no model, network, plugin or metrics-hook calls."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--settings', type=Path)
    args = parser.parse_args()
    root = args.root.absolute()
    errors, warnings = [], []
    settings_path = args.settings or root / 'settings.json'
    try:
        settings = json.loads(settings_path.read_text())
        for model, options in settings.get('modelSettings', {}).items():
            level = options.get('effortLevel')
            if level is not None and level not in {'low', 'medium', 'high', 'xhigh'}:
                errors.append('Unsupported saved effort: ' + model)
        if 'thinking' in settings:
            warnings.append('Top-level thinking is not a documented Claude Code control.')
    except (OSError, ValueError, TypeError) as error:
        errors.append('Settings could not be parsed: ' + type(error).__name__)

    agents = sorted((root / 'agents').glob('*.md'))
    skills = sorted((root / 'skills').glob('*/SKILL.md'))
    headers = agents + skills
    ruby = shutil.which('ruby')
    if ruby:
        script = r'''
require 'yaml'
require 'json'
errors = []
JSON.parse(STDIN.read).each do |path|
  begin
    match = File.read(path).match(/\A---\r?\n(.*?)\r?\n---/m)
    raise 'missing frontmatter' unless match
    data = YAML.safe_load(match[1], permitted_classes: [], permitted_symbols: [], aliases: false)
    raise 'expected mapping' unless data.is_a?(Hash)
    raise 'missing name/description' unless data['name'].is_a?(String) && data['description'].is_a?(String)
    if path.include?('/agents/')
      raise 'skills must be a YAML list' if data.key?('skills') && !data['skills'].is_a?(Array)
      if data.key?('effort') && !['low','medium','high','xhigh','max'].include?(data['effort'])
        raise 'unknown effort'
      end
    end
  rescue => error
    errors << File.basename(File.dirname(path)) + '/' + File.basename(path) + ': ' + error.message.lines.first.strip
  end
end
puts JSON.generate(errors)
'''
        result = subprocess.run([ruby, '-e', script], input=json.dumps([str(p) for p in headers]),
                                capture_output=True, text=True, timeout=20)
        if result.returncode:
            errors.append('YAML diagnostics failed: exit ' + str(result.returncode))
        else:
            errors.extend(json.loads(result.stdout))
    else:
        warnings.append('Ruby/Psych unavailable; complete YAML parsing was skipped.')

    # Literal user-config links only; dynamic/template paths are checked when instantiated.
    checked = 0
    for directory in ('agents', 'rules', 'commands'):
        for path in (root / directory).glob('*.md'):
            text = path.read_text()
            for match in re.finditer(r'~/.claude/([A-Za-z0-9_./-]+)', text):
                relative = match[1].rstrip('.')
                if relative.endswith('/') or relative in {'settings.json', 'CLAUDE.md'}:
                    continue
                checked += 1
                if not (root / relative).exists():
                    errors.append(str(path.relative_to(root)) + ': missing local reference ' + relative)

    hook = root / 'hooks/session-start'
    syntax = subprocess.run(['/bin/bash', '-n', str(hook)], capture_output=True, timeout=5)
    if syntax.returncode:
        errors.append('SessionStart shell syntax failed.')
    else:
        try:
            result = subprocess.run(['/bin/bash', str(hook)], input='{}', capture_output=True,
                                    text=True, timeout=5, cwd=root)
            payload = json.loads(result.stdout)
            context = payload['hookSpecificOutput']
            if result.returncode or context['hookEventName'] != 'SessionStart' or not isinstance(context['additionalContext'], str):
                errors.append('SessionStart output contract failed.')
            if len(result.stdout.encode('utf-8')) > 49152:
                errors.append('SessionStart exceeded the local output budget.')
            if 'systemMessage' in payload:
                warnings.append(payload['systemMessage'])
        except (ValueError, KeyError, OSError, subprocess.TimeoutExpired):
            errors.append('SessionStart could not emit valid bounded JSON within five seconds.')

    instruction_bytes = sum(p.stat().st_size for p in (root / 'rules').glob('*.md'))
    instruction_bytes += (root / 'CLAUDE.md').stat().st_size
    print(json.dumps({'status': 'PASS' if not errors else 'FAIL', 'agents': len(agents), 'skills': len(skills),
                      'yaml_parser': 'Psych' if ruby else 'unavailable', 'local_references_checked': checked,
                      'global_instruction_file_bytes': instruction_bytes,
                      'errors': errors, 'warnings': warnings,
                      'limits': 'Local structure and direct bootstrap only; no model/API or independent behavior review.'}, indent=2))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
