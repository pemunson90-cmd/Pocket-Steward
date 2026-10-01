"""Exercise exact Room slice replacement queries, including overlapping literal paths."""
import json
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).resolve().parents[1]
dao = (root / 'app/src/main/java/com/pocketsteward/app/data/db/FileRecordDao.kt').read_text()

def query(name):
    end = dao.index('suspend fun ' + name + '(')
    start = dao.rfind('@Query(', 0, end)
    assert start >= 0, name
    return ''.join(json.loads(m.group()) for m in re.finditer(r'"(?:[^"\\]|\\.)*"', dao[start:end]))

remove = query('removeScopeTagsUnder')
copy = query('copyScopeTagsUnder')
clear = query('removeAllScopeTags')
orphans = query('deleteOrphanedFiles')
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
db.execute('CREATE TABLE file_records(stableRef TEXT PRIMARY KEY, enrichment TEXT)')
db.execute('CREATE TABLE file_scopes(fileRef TEXT REFERENCES file_records(stableRef) ON DELETE CASCADE,scopeRoot TEXT,PRIMARY KEY(fileRef,scopeRoot))')
folder = '/storage/0/Down_load%'
child = folder + '/Uncertain'
refresh = 'library-refresh:' + folder
library = '/storage/0'
outside = '/storage/0/Documents/keep.txt'
stale = child + '/gone.txt'
current = child + '/a.txt'
neighbor = '/storage/0/DownXloadY/keep.txt'

def seed(ref, scopes, evidence='cached'):
    db.execute('INSERT OR IGNORE INTO file_records VALUES(?,?)', (ref, evidence))
    db.executemany('INSERT OR IGNORE INTO file_scopes VALUES(?,?)', [(ref, scope) for scope in scopes])

seed(outside, [library, '/storage/0/Documents'])
seed(neighbor, [library])
seed(stale, [library, folder, child])
seed(current, [library, child, refresh])
seed(child + '/new.txt', [refresh], 'new')
seed(folder, [library, refresh])
seed(child, [library, refresh])
db.commit()
with db:
    for scope in [library, folder, child]:
        part = scope if scope == folder or scope.startswith(folder + '/') else folder
        db.execute(remove, {'scopeRootRef': scope, 'folderRef': part, 'folderSlash': part + '/'})
        db.execute(copy, {'libraryScope': refresh, 'newScope': scope, 'folderRef': part, 'folderSlash': part + '/'})
    db.execute(clear, {'scopeRootRef': refresh})
    db.execute(orphans)
assert db.execute('SELECT stableRef FROM file_records WHERE stableRef=?', (stale,)).fetchone() is None
assert set(row[0] for row in db.execute('SELECT scopeRoot FROM file_scopes WHERE fileRef=?', (current,))) == {library, folder, child}
assert db.execute('SELECT enrichment FROM file_records WHERE stableRef=?', (outside,)).fetchone() == ('cached',)
assert db.execute('SELECT enrichment FROM file_records WHERE stableRef=?', (current,)).fetchone() == ('cached',)
assert db.execute('SELECT 1 FROM file_scopes WHERE fileRef=? AND scopeRoot=?', (neighbor, library)).fetchone() == (1,)
assert db.execute('SELECT 1 FROM file_scopes WHERE scopeRoot=?', (refresh,)).fetchone() is None
before = db.execute('SELECT * FROM file_scopes ORDER BY fileRef,scopeRoot').fetchall()
try:
    with db:
        db.execute(remove, {'scopeRootRef': library, 'folderRef': folder, 'folderSlash': folder + '/'})
        raise RuntimeError('synthetic interrupted transaction')
except RuntimeError:
    pass
assert db.execute('SELECT * FROM file_scopes ORDER BY fileRef,scopeRoot').fetchall() == before
print('Actual directory-slice SQL passed overlapping memberships, deletion, unchanged enrichment, literal paths and rollback.')
