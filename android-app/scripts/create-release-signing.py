#!/usr/bin/env python3
"""Create a private, stable APK signing key without printing its password."""
from pathlib import Path
import os
import secrets
import subprocess

project = Path(__file__).resolve().parents[1]
private = project / '.signing'
private.mkdir(mode=0o700, exist_ok=True)
properties = private / 'release.properties'
keystore = private / 'release.jks'
if properties.exists() or keystore.exists():
    raise SystemExit('Signing material already exists. Keep it for every future release; no files were overwritten.')
password = secrets.token_urlsafe(40)
environment = dict(os.environ, BOOKKEEPING_SIGNING_PASSWORD=password)
java_home = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
result = subprocess.run([str(java_home/'bin/keytool'), '-genkeypair', '-keystore', str(keystore), '-alias', 'bookkeeping-release', '-storetype', 'PKCS12', '-storepass:env', 'BOOKKEEPING_SIGNING_PASSWORD', '-keypass:env', 'BOOKKEEPING_SIGNING_PASSWORD', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Screenshot Bookkeeping, OU=Invitation Testing'], env=environment, capture_output=True, text=True)
if result.returncode:
    raise SystemExit('Signing key generation failed: ' + result.stderr)
keystore.chmod(0o600)
properties.write_text('storeFile=.signing/release.jks\nkeyAlias=bookkeeping-release\nstorePassword='+password+'\nkeyPassword='+password+'\n')
properties.chmod(0o600)
print('Created stable release signing material in android-app/.signing. Back up this private directory; never distribute or commit it.')
