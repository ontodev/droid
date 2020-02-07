import os, subprocess
from bs4 import BeautifulSoup, SoupStrainer
from markdown import markdown
from urllib.parse import urlparse

def read_workflow(lines):
    itr = iter(lines)
    for line in itr:
        if line.startswith('### Workflow'):
            break
    workflow = []
    for line in itr:
        if not line.startswith('#'):
            break
        if line.startswith('# '):
            workflow.append(line[2:])
        else:
            workflow.append(line[1:])
    return ''.join(workflow)

def read_makefile(branch):
    makefile = {'targets': [], 'views': [], 'actions': []}
    result = subprocess.run(['make', '-pn'], cwd='workspace/' + branch, capture_output=True, text=True)
    for line in result.stdout.splitlines():
        if line.startswith('.PHONY: '):
            makefile['phony'] = line.split()[1:]
    with open('workspace/' + branch + '/Makefile', 'r') as f:
        makefile['markdown'] = read_workflow(f.readlines())
        html = BeautifulSoup(markdown(makefile['markdown']), 'html.parser')
        for link in html.find_all('a'):
            if link.has_attr('href'):
                url = urlparse(link['href'])
                if url.netloc.strip() == '':
                    makefile['targets'].append(link['href'])
                    if link['href'] in makefile['phony']:
                        link['class'] = 'btn btn-primary btn-sm'
                        link['href'] = '?action=' + link['href']
                        makefile['actions'].append(link['href'])
                    else:
                        link['href'] = branch + '/views/' + link['href']
                        makefile['views'].append(link['href'])
        makefile['html'] = html
    return makefile

if __name__ == '__main__':
    import pprint
    pp = pprint.PrettyPrinter(indent=2, width=160)
    pp.pprint(read_makefile('master'))
