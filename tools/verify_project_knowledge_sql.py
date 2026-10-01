"""Exercise the exact Room project-layout query with scoped, mixed-case directories."""
import json
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).resolve().parents[1]
dao = (root / 'app/src/main/java/com/pocketsteward/app/data/db/FileRecordDao.kt').read_text()
block = dao.split('/** Project-layout observations')[1].split('suspend fun indexedProjectLayouts')[0]
sql = ''.join(json.loads(m.group()) for m in re.finditer(r'"(?:[^"\\]|\\.)*"', block))
roles = ['manuscript', 'notes', 'drafts', 'images', 'versions', 'archive']
sql = sql.replace('IN (:roleNames)', 'IN (' + ','.join(':'+str('role'+str(i)) for i in range(len(roles))) + ')')
params = {'scopeRootRef': '/storage/emulated/0', 'limit': 201, **{'role'+str(i): name for i, name in enumerate(roles)}}
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE file_records(stableRef TEXT PRIMARY KEY,displayName TEXT,parentRef TEXT,isDirectory INTEGER,isHidden INTEGER)')
db.execute('CREATE TABLE file_scopes(fileRef TEXT,scopeRoot TEXT,PRIMARY KEY(fileRef,scopeRoot))')

def directory(path, scope=params['scopeRootRef']):
    db.execute('INSERT INTO file_records VALUES(?,?,?,?,?)', (path,path.rsplit('/',1)[-1],path.rsplit('/',1)[0],1,0))
    db.execute('INSERT INTO file_scopes VALUES(?,?)',(path,scope))

directory('/storage/emulated/0/Projects/Lilith')
directory('/storage/emulated/0/Projects/Lilith/notes')
directory('/storage/emulated/0/Projects/Lilith/IMAGES')
directory('/storage/emulated/0/Other/Lilith', '/separate-scope')
directory('/storage/emulated/0/Other/Lilith/Notes', '/separate-scope')
directory('/storage/emulated/0/NoLayout')
db.execute('INSERT INTO file_records VALUES(?,?,?,?,?)',('/storage/emulated/0/NoLayout/Notes','Notes','/storage/emulated/0/NoLayout',0,0))
rows=db.execute(sql,params).fetchall()
assert len(rows)==1,rows
assert rows[0][0]=='/storage/emulated/0/Projects/Lilith'
assert set(rows[0][3].split('\n'))=={'notes','IMAGES'},rows
# Two case variants remain visible to the policy, which refuses to pick one.
directory('/storage/emulated/0/Projects/Lilith/Notes')
assert set(db.execute(sql,params).fetchone()[3].split('\n'))=={'Notes','notes','IMAGES'}
for i in range(1000):
    directory(f'/storage/emulated/0/Projects/Project {i}')
    directory(f'/storage/emulated/0/Projects/Project {i}/Notes')
assert len(db.execute(sql,params).fetchall())==201
print('Actual project-layout SQL passed scope filtering, grouping, role spelling, case ambiguity and bounded result tests.')
