import sqlite3,re,pathlib
root=pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/java/com/pocketsteward/app/content/index'
s=(root/'ContentSearchDatabase.kt').read_text().split('val MIGRATION_2_3')[1].split('@Volatile')[0]
db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
db.execute('CREATE TABLE indexed_documents(stableRef TEXT PRIMARY KEY NOT NULL,sourceRoot TEXT NOT NULL,displayName TEXT,parentRef TEXT,extension TEXT,category TEXT,sizeBytes INTEGER,modifiedAt INTEGER,contentKind TEXT,extractionStatus TEXT,quickFingerprint TEXT,extractorVersion INTEGER NOT NULL DEFAULT 1)')
db.execute("INSERT INTO indexed_documents VALUES('/Downloads/Uncertain/a.txt','/Downloads','a.txt','/Downloads/Uncertain','txt','DOCUMENT',12,100,'TEXT','INDEXED',NULL,1)")
for sql in re.findall(r'db.execSQL\("([^"]+)"\)',s):db.execute(sql)
assert db.execute('SELECT extractionProfile, coverageComplete FROM indexed_documents').fetchone() == ('FULL', 0)
assert db.execute('SELECT * FROM indexed_document_scopes').fetchall()==[('/Downloads/Uncertain/a.txt','/Downloads')]
db.execute("INSERT INTO indexed_document_scopes VALUES('/Downloads/Uncertain/a.txt','/Downloads/Uncertain')")
db.execute('CREATE TABLE indexed_segments(id INTEGER PRIMARY KEY,stableRef TEXT,pageNumber INTEGER,ocr INTEGER,body TEXT)')
db.execute('CREATE VIRTUAL TABLE indexed_segments_fts USING fts4(body)')
db.execute("INSERT INTO indexed_segments VALUES(1,'/Downloads/Uncertain/a.txt',NULL,0,'Lilith manuscript notes')")
db.execute("INSERT INTO indexed_segments_fts(rowid,body) VALUES(1,'Lilith manuscript notes')")
dao=(root/'ContentIndexDao.kt').read_text();sql=re.search(r'SELECT s.id AS segmentId,.*?LIMIT :limit',dao,re.S)[0]
# The actual Room query is exercised with overlapping roots and a real FTS4 index.
sql=sql.replace('IN (:sourceRoots)','IN (:rootA,:rootB)')
params={'matchQuery':'"Lilith"','rootA':'/Downloads','rootB':'/Downloads/Uncertain','limit':100,'extractorVersion':2}
assert db.execute(sql,params).fetchall()==[] # Legacy caches cannot fill the candidate limit.
db.execute("UPDATE indexed_documents SET quickFingerprint='evidence-sample-v1:prefix:fixture',extractorVersion=2")
rows=db.execute(sql,params).fetchall();assert len(rows)==1,rows
ask=re.findall(r'SELECT s.id AS segmentId,.*?LIMIT :limit',dao,re.S)[1]
ask_rows=db.execute(ask,params).fetchall();assert len(ask_rows)==1,ask_rows
assert ask_rows[0][-1]=='evidence-sample-v1:prefix:fixture'
assert set(rows[0][3].split('\n'))=={'/Downloads','/Downloads/Uncertain'},rows
# Removing one root retains text, then orphan deletion cascades the final membership.
db.execute("DELETE FROM indexed_document_scopes WHERE sourceRoot='/Downloads'")
assert len(db.execute(sql,params).fetchall())==1
assert db.execute('SELECT count(*) FROM indexed_document_scopes').fetchone()[0]==1
db.execute('DELETE FROM indexed_documents')
assert db.execute('SELECT count(*) FROM indexed_document_scopes').fetchone()[0]==0
print('Derived migration, overlapping-root FTS query, scoped removal, and FK cascade passed in SQLite.')
