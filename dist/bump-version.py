# coding: utf-8
# tested iron-python 2.7.7

# bump version-number of W10Wheel
# version 0.1

import sys, os, re, codecs, datetime, shutil
from os.path import abspath, join, dirname
from decimal import Decimal

ROOT_PATH = abspath(join(dirname(__file__), os.pardir))
DIST_PATH = dirname(abspath(__file__))
SRC_PATH = join(ROOT_PATH, 'src\main\scala\hooktest')

CONTEXT_PATH = join(SRC_PATH, 'Context.scala')
SBT_PATH = join(ROOT_PATH, 'build.sbt')
EXEWRAP_X86_PATH = join(DIST_PATH, 'exewrap-x86.bat')
EXEWRAP_X64_PATH = join(DIST_PATH, 'exewrap-x64.bat')
README_PATH = join(DIST_PATH, 'Readme.ja.txt')

args = sys.argv[1:]

if len(args) != 1:
    print('bump-version version-number(x.x | x.x.x | x.x.x.x)')
    print('bump-version command(--restore-old)')
    sys.exit(1)

command = args[0]

def restore_old(path):
    old_path = path + '.old'
    shutil.copy2(old_path, path)
    os.remove(old_path)

if command == '--restore-old':
    restore_old(CONTEXT_PATH)
    restore_old(SBT_PATH)
    restore_old(EXEWRAP_X86_PATH)
    restore_old(EXEWRAP_X64_PATH)
    restore_old(README_PATH)
    print('done: restore .old files')
    sys.exit(0)

if command.startswith('--'):
    print('error: unknown command: ' + command)
    sys.exit(1)
    
ver_num = args[0]
ver_len = len(ver_num)

VER_RE_PAT = r'\d\.\d(\.\d){0,2}'

if not re.match(r'^%s$' % VER_RE_PAT, ver_num):
    print('error: invalid version-number format: ' + ver_num)
    sys.exit(1)

print("ver_num: " + ver_num)

def read_file(path):
    f = codecs.open(path, 'r', 'utf-8')
    data = f.read()
    f.close()
    return data

def write_file(path, data):
    f = codecs.open(path, 'w', 'utf-8')
    f.write(data)
    f.close()

CONTEXT_RE_PAT = r'val PROGRAM_VERSION = "(%s)"' % VER_RE_PAT

def get_context_version():
    data = read_file(CONTEXT_PATH)
    match = re.search(CONTEXT_RE_PAT, data)
    return match.group(1) if match else None

ctx_ver = get_context_version()
if not ctx_ver:
    print('error: failed get version of Context.scala')
    sys.exit(1)

print('ctx_ver: ' + ctx_ver)
if Decimal(ver_num) < Decimal(ctx_ver):
    print('error: invalid version: ver_num < ctx_ver')
    sys.exit(1)

def copy_backup(path):
    shutil.copy2(path, path + '.old')

def update_context_version():
    data = read_file(CONTEXT_PATH)
    repl = 'val PROGRAM_VERSION = "%s"' % ver_num
    res = re.sub(CONTEXT_RE_PAT, repl, data, count=1)
    copy_backup(CONTEXT_PATH)
    write_file(CONTEXT_PATH, res)
    print('done: update Context.scala')

def update_sbt_version():
    data = read_file(SBT_PATH)
    repl = 'version := "%s",' % ver_num
    res = re.sub(r'version := "%s",' % VER_RE_PAT, repl, data, count=1)
    copy_backup(SBT_PATH)
    write_file(SBT_PATH, res)
    print('done: update build.sbt')

def update_exwrap_verson():
    def get_file_version():
        if ver_len == 3:
            return ver_num + '.0.0'
        elif ver_len == 5:
            return ver_num + '.0'
        else:
            raise RuntimeError('invalid ver_num: ' + ver_num)

    def update(path):
        data = read_file(path)
        fv_repl =  ' -v %s ' % get_file_version()
        res1 = re.sub(r' -v %s ' % VER_RE_PAT, fv_repl, data, count=1)
        pv_repl = ' -V "%s" ' % ver_num
        return re.sub(r' -V "%s" ' % VER_RE_PAT, pv_repl, res1, count=1)

    x86_res = update(EXEWRAP_X86_PATH)
    copy_backup(EXEWRAP_X86_PATH)
    write_file(EXEWRAP_X86_PATH, x86_res)
    print('done: update exewrap-x86.bat')

    x64_res = update(EXEWRAP_X64_PATH)
    copy_backup(EXEWRAP_X64_PATH)    
    write_file(EXEWRAP_X64_PATH, x64_res)
    print('done: update exewrap-x64.bat')

def update_readme_version():
    def get_history_version():
        if ver_len == 3:
            return 'v%s.0' % ver_num
        elif ver_len == 5:
            return 'v' + ver_num
        else:
            raise RuntimeError('invalid ver_num: ' + ver_num)

    def get_iso_date():
        return datetime.datetime.now().date().isoformat()
            
    data = read_file(README_PATH)
    v_repl = 'バージョン:\r\n        %s' % ver_num
    res1 = re.sub(r'バージョン:\r\n {8}%s' % VER_RE_PAT, v_repl, data, count=1)
    h_repl = '履歴:\r\n        %s: %s:\r\n' % (get_iso_date(), get_history_version())
    res2 = re.sub(r'履歴:\r\n', h_repl, res1, count=1)
    copy_backup(README_PATH)
    write_file(README_PATH, res2)
    print('done: update Readme.ja.txt')


update_context_version()
update_sbt_version()
update_exwrap_verson()
update_readme_version()
