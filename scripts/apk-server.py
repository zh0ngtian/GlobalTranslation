#!/usr/bin/env python3
"""Temporary LAN APK delivery, using Python 3.9+ and macOS launchd."""

import argparse
import hashlib
import html
import http.client
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.local-apk-server'
FILES = STATE / 'files'
PORT = 8765
TTL = 48 * 3600
LIMIT = 100 * 1024 * 1024
LABEL = 'local.globaltranslation.apk-server'
DOMAIN = f'gui/{os.getuid()}'
LOCAL = f'http://127.0.0.1:{PORT}'
LOCK = threading.RLock()


def stamp(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).astimezone().isoformat(timespec='seconds')


def records():
    """Called under LOCK; expiry also applies while the service was stopped."""
    result = []
    for path in FILES.glob('*.json'):
        item = json.loads(path.read_text())
        if item['expires_at_unix'] <= time.time():
            path.with_suffix('.apk').unlink(missing_ok=True)
            path.unlink(missing_ok=True)
        else:
            result.append(item)
    return sorted(result, key=lambda item: item['expires_at_unix'], reverse=True)


class Handler(BaseHTTPRequestHandler):
    server_version = 'LocalAPK/1.0'

    def setup(self):
        super().setup()
        self.connection.settimeout(120)

    def reply(self, code, body, content_type='application/json; charset=utf-8'):
        data = body.encode() if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/health':
            return self.reply(200, {'service': LABEL, 'url': self.server.base_url, 'ttl_hours': 48})
        with LOCK:
            items = records()
            if path == '/':
                rows = ''.join(
                    f'<li><a href="{html.escape(item["path"], quote=True)}">{html.escape(item["name"])}</a>'
                    f'<p>{item["bytes"] / 1024 / 1024:.1f} MiB · 到期：{item["expires_at"]}</p>'
                    f'<small>SHA-256：{item["sha256"]}</small></li>' for item in items
                )
                page = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
                        '<meta name="viewport" content="width=device-width,initial-scale=1">'
                        '<title>GlobalTranslation APK 下载</title>'
                        '<style>body{max-width:800px;margin:40px auto;padding:0 20px;font:16px system-ui;'
                        'line-height:1.6}li{margin-bottom:24px}small{overflow-wrap:anywhere}</style>'
                        '<h1>GlobalTranslation APK 下载</h1><p>测试版安装包，上传后保留 48 小时。'
                        '手机需与此 Mac 在同一局域网；Mac 保持开机和唤醒。</p>'
                        '<form id="upload"><input id="apk" type="file" accept=".apk" required '
                        'aria-label="选择 APK"><button>上传 APK</button></form><p id="message"></p>'
                        f'<ul>{rows or "<li>暂无安装包</li>"}</ul>'
                        '<script>document.getElementById("upload").onsubmit=async(e)=>{'
                        'e.preventDefault();const file=document.getElementById("apk").files[0];'
                        'const message=document.getElementById("message");'
                        'const button=e.target.querySelector("button");button.disabled=true;'
                        'message.textContent="正在上传…";try{'
                        'const response=await fetch("/upload/"+encodeURIComponent(file.name),'
                        '{method:"PUT",body:file});const result=await response.json();'
                        'if(!response.ok)throw new Error(result.error);location.reload();'
                        '}catch(error){message.textContent=error.message;button.disabled=false;}};'
                        '</script></html>')
                return self.reply(200, page, 'text/html; charset=utf-8')
            item = next((item for item in items if item['path'] == path), None)
            if item is None:
                return self.reply(404, {'error': '文件不存在或已过期'})
            source = (FILES / (item['id'] + '.apk')).open('rb')
        with source:
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Length', str(item['bytes']))
            self.send_header('Content-Disposition', f"attachment; filename*=UTF-8''{quote(item['name'])}")
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            if self.command != 'HEAD':
                shutil.copyfileobj(source, self.wfile, 1024 * 1024)

    def do_PUT(self):
        path = urlsplit(self.path).path
        name = unquote(path.removeprefix('/upload/'))
        if not path.startswith('/upload/') or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,149}\.apk', name):
            return self.reply(400, {'error': '使用 /upload/文件名.apk；文件名仅限英文字母、数字、点、横线和下划线'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            size = 0
        if not 0 < size <= LIMIT or self.headers.get('Transfer-Encoding'):
            return self.reply(413, {'error': '需要 Content-Length，大小须在 1 字节至 100 MiB 之间'})
        file_id = secrets.token_hex(16)
        pending = FILES / (file_id + '.part')
        digest = hashlib.sha256()
        try:
            with pending.open('xb') as target:
                remaining = size
                while remaining:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ValueError('上传中断')
                    target.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
            with zipfile.ZipFile(pending) as archive:
                if 'AndroidManifest.xml' not in archive.namelist():
                    raise ValueError('文件不是 APK')
            expires = time.time() + TTL
            download_path = f'/files/{file_id}/{name}'
            item = {'id': file_id, 'name': name, 'bytes': size, 'sha256': digest.hexdigest(),
                    'path': download_path, 'url': self.server.base_url + download_path,
                    'expires_at': stamp(expires), 'expires_at_unix': expires}
            with LOCK:
                records()
                pending.rename(FILES / (file_id + '.apk'))
                (FILES / (file_id + '.json')).write_text(json.dumps(item, ensure_ascii=False, indent=2))
            self.reply(201, item)
        except (ValueError, zipfile.BadZipFile, TimeoutError) as error:
            self.reply(400, {'error': str(error)})
        finally:
            pending.unlink(missing_ok=True)


def health():
    conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=2)
    try:
        conn.request('GET', '/health')
        response = conn.getresponse()
        result = json.loads(response.read())
        return result if response.status == 200 and result.get('service') == LABEL else None
    except (OSError, ValueError, http.client.HTTPException):
        return None
    finally:
        conn.close()


def serve():
    config = json.loads((STATE / 'config.json').read_text())
    server = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    server.base_url = config['base_url']
    for path in FILES.glob('*.part'):
        path.unlink()

    def cleanup():
        while True:
            with LOCK:
                records()
            time.sleep(60)

    threading.Thread(target=cleanup, daemon=True).start()
    server.serve_forever()


def start(base_url):
    if health():
        print(json.dumps(health(), ensure_ascii=False, indent=2))
        return
    parsed = urlsplit(base_url)
    if parsed.scheme != 'http' or not parsed.hostname or parsed.port != PORT or parsed.path not in ('', '/'):
        raise ValueError(f'base-url 应为 http://局域网主机:{PORT}')
    STATE.mkdir(mode=0o700, exist_ok=True)
    FILES.mkdir(mode=0o700, exist_ok=True)
    (STATE / 'config.json').write_text(json.dumps({'base_url': base_url.rstrip('/')}))
    plist = STATE / (LABEL + '.plist')
    plist.write_bytes(plistlib.dumps({
        'Label': LABEL,
        'ProgramArguments': [sys.executable, '-u', str(Path(__file__).resolve()), 'serve'],
        'WorkingDirectory': str(ROOT), 'RunAtLoad': True,
        'StandardOutPath': str(STATE / 'server.log'),
        'StandardErrorPath': str(STATE / 'server.log'),
    }))
    subprocess.run(['launchctl', 'bootstrap', DOMAIN, str(plist)], check=True)
    for _ in range(30):
        if health():
            print(json.dumps(health(), ensure_ascii=False, indent=2))
            return
        time.sleep(0.2)
    raise RuntimeError(f'启动失败，检查 {STATE / "server.log"}')


def upload(path):
    if not health():
        raise RuntimeError('服务未启动，请先运行 start')
    conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=120)
    with path.open('rb') as source:
        conn.request('PUT', '/upload/' + quote(path.name), source, headers={
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Length': str(path.stat().st_size),
        })
        response = conn.getresponse()
        body = response.read().decode()
    conn.close()
    if response.status != 201:
        raise RuntimeError(f'上传失败 ({response.status}): {body}')
    print(json.dumps(json.loads(body), ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('start').add_argument('--base-url', default=f'http://Yao-Mac-mini.local:{PORT}')
    commands.add_parser('stop')
    commands.add_parser('status')
    commands.add_parser('serve')
    commands.add_parser('upload').add_argument('apk', type=Path)
    args = parser.parse_args()
    if args.command == 'start':
        start(args.base_url)
    elif args.command == 'stop':
        subprocess.run(['launchctl', 'bootout', f'{DOMAIN}/{LABEL}'], check=True)
        print('服务已停止；文件保留至到期，下次启动清理。')
    elif args.command == 'status':
        result = health()
        print(json.dumps(result or {'status': 'stopped'}, ensure_ascii=False, indent=2))
        return 0 if result else 1
    elif args.command == 'upload':
        upload(args.apk)
    else:
        serve()
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
