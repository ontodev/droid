import os, yaml

def create_app(test_config=None):
    # Create droid.yml with wizard if not found
    if not os.path.exists('droid.yml'):
        from . import wizard
        wizard.run()

    # Load droid.yml
    config = None
    if os.path.exists('droid.yml'):
        with open('droid.yml', 'r') as f:
            config = yaml.safe_load(f)
    if config is None:
        raise ValueError('The droid.yml configuration file is required, but could not be found.')
    # TODO: validate config

    # Load the server
    from . import server
    return server.create_app(config, test_config)
