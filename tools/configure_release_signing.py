#!/usr/bin/env python3
"""Check an existing PKCS12 key and save private Gradle settings locally."""

import argparse
import getpass
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def property_value(value):
    # Properties.load(InputStream) accepts UTF-16 escapes, including surrogate pairs.
    encoded = value.encode("utf-16-be")
    return "".join(f"\\u{int.from_bytes(encoded[i:i + 2], 'big'):04x}"
                   for i in range(0, len(encoded), 2))


def configure(project, keystore, keytool, alias, password):
    if not password:
        raise ValueError("Пароль не может быть пустым.")
    result = subprocess.run(
        [str(keytool), "-list", "-keystore", str(keystore), "-storetype", "PKCS12",
         "-alias", alias, "-storepass:env", "MAMOKEEP_KEYSTORE_PASSWORD"],
        env={**os.environ, "MAMOKEEP_KEYSTORE_PASSWORD": password},
        capture_output=True,
    )
    if result.returncode != 0:
        raise ValueError("Ключ не прошёл проверку. Проверьте путь, пароль и alias; настройки не изменены.")

    local = project / "local.properties"
    existing = local.read_text(encoding="latin-1") if local.exists() else ""
    existing = re.sub(
        r"(?m)^release\.(?:storeFile|storePassword|keyAlias)=.*(?:\n|$)",
        "", existing,
    )
    values = {
        "storeFile": str(keystore),
        "storePassword": password,
        "keyAlias": alias,
    }
    content = existing.rstrip("\n") + "\n" + "".join(
        f"release.{key}={property_value(value)}\n" for key, value in values.items()
    )
    fd, name = tempfile.mkstemp(prefix=".release-signing-", dir=project)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "w", encoding="latin-1") as stream:
            stream.write(content)
        temporary.replace(local)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--keytool", required=True, type=Path)
    parser.add_argument("--alias", required=True)
    args = parser.parse_args()
    if not sys.stdin.isatty():
        parser.error("Запустите команду в своём Терминале: нужен скрытый ввод пароля.")
    project = Path(__file__).resolve().parents[1]
    try:
        configure(project, args.keystore.expanduser().resolve(), args.keytool,
                  args.alias, getpass.getpass("Пароль созданного ключа: "))
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")
    print("Готово: ключ проверен, настройки сохранены в local.properties с доступом только владельцу.")
    print("Пароль остаётся на этом Mac. Не отправляйте local.properties и файл ключа в чат или репозиторий.")


if __name__ == "__main__":
    main()
