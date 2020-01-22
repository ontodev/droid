from droid import create_app
from droid import wizard

import os


def setup_factory():
    if not os.path.exists('droid.yml'):
        # Create a phony droid.yml file
        wizard.create_config('droid_test', 'ontodev', 'droid')


def test_config():
    setup_factory()
    assert not create_app().testing
    assert create_app({'TESTING': True}).testing


def test_hello(client):
    setup_factory()
    response = client.get('/hello')
    assert response.data == b'Hello, World!'
