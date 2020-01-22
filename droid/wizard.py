import requests
import os
import sys


def run():
    """Get user input to create a config file. Validate fields and write
    to droid.yml file.
    """
    if os.path.exists('droid.yml'):
        print('A configuration file already exists!')
        try_continue()

    p_name = input('Project name: ')
    gh_user = input('GitHub organization or username: ')
    gh_project = input('GitHub project name: ')

    # Check that the GitHub repo actually exists
    gh_repo = 'https://github.com/{0}/{1}'.format(gh_user, gh_project)
    req = requests.get(gh_repo)
    if req.status_code != 200:
        print('WARN: GitHub repo {0}/{1} does not exist!'.format(
            gh_user, gh_project))

    yml = '''droid:
  configuration version: 1

project:
  name: {0}
  GitHub organization: {1}
  GitHub project: {2}
'''.format(p_name, gh_user, gh_project)

    with open('droid.yml', 'w+') as f:
        f.write(yml)

    print('Results written to droid.yml')


def try_continue():
    """Get user input if process should continue. Repeat until user enters y/n.
    """
    while True:
        resp = input('Do you wish to overwrite? [y/n] ')
        if resp.lower() == 'n':
            sys.exit(0)
        elif resp.lower() != 'y':
            try_continue()
        else:
            return


if __name__ == '__main__':
    run()
