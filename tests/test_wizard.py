from droid import wizard


def test_valid_repo():
    gh_user = 'ontodev'
    gh_project = 'DROID'
    valid = wizard.validate_repo(gh_user, gh_project)
    assert valid


def test_invalid_repo():
    gh_user = 'not_a_user'
    gh_project = 'not_a_repository'
    valid = wizard.validate_repo(gh_user, gh_project)
    assert not valid
