import os, re, subprocess

view_pattern = re.compile(r'^# VIEW\s+(?P<name>.*?)(\s*:\s*(?P<description>.*))?$')

def read_lines(lines):
    targets = []
    itr = iter(lines)
    for line in itr:
        if line.startswith('# ACTION '):
            next_line = next(itr)
            name = next_line.split(':', 1)[0]
            target = {
                'type': 'action',
                'name': name,
                'target': name,
                'description': line[8:].strip()
            }
            targets.append(target)

        elif line.startswith('# VIEW '):
            m = view_pattern.match(line)
            if m is None:
                continue
            next_line = next(itr)
            target = m.groupdict()
            target['type'] = 'view'
            target['paths'] = next_line.split()[2:]
            targets.append(target)

    return targets

def read_makefile(branch):
    with open('workspace/' + branch + '/Makefile', 'r') as f:
        return read_lines(f.readlines())

def read_block(block):
    return read_lines(block.splitlines())

if __name__ == '__main__':
    import pprint
    pp = pprint.PrettyPrinter(indent=2, width=160)
    pp.pprint(read_makefile('master'))

