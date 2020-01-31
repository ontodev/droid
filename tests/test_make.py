from droid import make


def test_views():
    block = """# VIEW name: description
FOO := file1.html path2/file2.xlsx"""
    result = [{
        'type': 'view',
        'name': 'name',
        'description': 'description',
        'paths': ['file1.html', 'path2/file2.xlsx']
    }]
    assert make.read_block(block) == result
