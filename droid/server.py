import math, os, subprocess, time, datetime

from flask import Flask, request, redirect, render_template, send_file, safe_join, abort, url_for
from flaskext.markdown import Markdown

from . import make

def create_app(config, test_config=None):
    # create and configure the app
    app = Flask(__name__, instance_relative_config=True)
    app.config.from_mapping(
        SECRET_KEY='dev',
    )
    Markdown(app)

    # Custom Jinja filters
    @app.template_filter()
    def basename(text):
      return os.path.basename(text)

    # Load app config
    if test_config is None:
        # load the instance config, if it exists, when not testing
        app.config.from_pyfile('config.py', silent=True)
    else:
        # load the test config if passed in
        app.config.from_mapping(test_config)

    # ensure the instance folder exists
    try:
        os.makedirs(app.instance_path)
    except OSError:
        pass

    # Look in the workspace directory for branches
    os.makedirs('workspace', exist_ok=True)
    branches = {}
    branch_names = []
    for x in os.listdir('workspace'):
      if os.path.isdir('workspace/' + x):
        branch_names.append(x)
        branches[x] = {'name': x}

    # Create a temp directory for each branch
    os.makedirs('temp', exist_ok=True)
    for branch in branch_names:
      os.makedirs('temp/' + branch, exist_ok=True)
      os.system('touch temp/' + branch + '/console.txt')

    # Index page
    @app.route('/')
    def index():
      return render_template(
        'index.html',
        config=config,
        branch_names=branch_names
      )

    # Branch page, including actions that run or cancel branch processes
    @app.route('/branches/<branch>')
    def branch(branch):
      targets = make.read_makefile(branch)
      console = 'temp/' + branch + '/console.txt'

      do = request.args.get('action')
      if do:
        if do == 'cancel':
          if 'process' in branches[branch]:
            branches[branch]['process'].kill()
            branches[branch]['cancelled'] = True
            time.sleep(1)
          return redirect('/branches/' + branch)

        for target in targets:
          if target['type'] == 'action' and do == target['target']:
            print('DO: ' + do)
            if 'process' in branches[branch]:
              process = branches[branch]['process']
              if process.poll() is None:
                process.kill()

            command = 'make ' + do
            process = subprocess.Popen(command + ' > ../../' + console + ' 2>&1', shell=True, cwd='workspace/' + branch)
            branches[branch].update({
                'action': do,
                'command': command,
                'process': process,
                'start_time': datetime.datetime.now(),
                'cancelled': False
            })
            time.sleep(1)
            return redirect('/branches/' + branch)

      # Get process status
      with open(console, 'r') as f:
        branches[branch]['console'] = f.read()
      if 'process' in branches[branch]:
        result = branches[branch]['process'].poll()
        if result is None:
          diff = datetime.datetime.now() - branches[branch]['start_time']
          branches[branch]['run_time'] = math.ceil(diff.total_seconds())
      return render_template(
        'branch.html',
        config=config,
        branch=branches[branch],
        targets=targets
      )

    # View files
    @app.route('/branches/<branch>/views/<path:path>')
    def view(branch, path):
      targets = make.read_makefile(branch)
      for target in targets:
        if target['type'] == 'view':
          for p in target['paths']:
            if path == p:
              return send_file(safe_join(os.getcwd(), 'workspace', branch, path))
      abort(404)

    return app
