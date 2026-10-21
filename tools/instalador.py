"""SalaTV Instalador: encuentra las teles de casa con la depuración ADB activa, instala SalaTV y
aplica la lista de limpieza de cada modelo (las de CleanYourTV que lleva SalaTV dentro).

Antes de tocar nada guarda una copia de los paquetes de la tele en copias/.
Solo usa la biblioteca estándar de Python y el adb de las platform-tools.

    python tools/instalador.py
    python tools/instalador.py --apk ruta/al/app-release.apk
"""
import argparse
import concurrent.futures
import datetime
import ipaddress
import os
import shutil
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LISTS = ROOT / "app" / "src" / "main" / "assets" / "listas"
OWN_LIST = ROOT / "app" / "src" / "main" / "assets" / "debloat.txt"
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
PROTECTED = {"com.amazon.tv.launcher", "com.amazon.tv.settings", "com.amazon.tv.settings.v2",
             "com.amazon.tv.inputpreference.service", "com.amazon.device.software.ota",
             "com.amazon.tv.oobe", "com.amazon.venezia", "com.salatv.app", "android"}


def find_adb():
    a = shutil.which("adb")
    if a:
        return a
    guess = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if guess.exists():
        return str(guess)
    sys.exit("No encuentro adb. Instala las platform-tools de Android.")


ADB = find_adb()


def adb(*args, serial=None, timeout=60):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return (r.stdout + r.stderr).strip()


def local_networks():
    nets = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        nets.add(ipaddress.ip_network(s.getsockname()[0] + "/24", strict=False))
        s.close()
    except OSError:
        pass
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        ip = info[4][0]
        if not ip.startswith("127."):
            nets.add(ipaddress.ip_network(ip + "/24", strict=False))
    return nets


def port_open(ip):
    try:
        with socket.create_connection((str(ip), 5555), timeout=0.4):
            return str(ip)
    except OSError:
        return None


def scan():
    hosts = [h for n in local_networks() for h in n.hosts()]
    print(f"Buscando teles en {len(hosts)} direcciones…")
    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        return sorted(filter(None, ex.map(port_open, hosts)), key=ipaddress.ip_address)


def describe(ip):
    serial = f"{ip}:5555"
    out = adb("connect", serial, timeout=10)
    state = adb("get-state", serial=serial, timeout=10)
    if state != "device":
        why = "acepta el aviso «¿Permitir la depuración?» en la tele" if "unauthorized" in (out + state) else "no responde"
        return {"ip": ip, "serial": serial, "ok": False, "why": why}
    prop = lambda k: adb("shell", "getprop", k, serial=serial, timeout=10)
    return {
        "ip": ip, "serial": serial, "ok": True,
        "maker": prop("ro.product.manufacturer"), "model": prop("ro.product.model"),
        "os": prop("ro.build.version.name") or "Android " + prop("ro.build.version.release"),
        "salatv": "package:" in adb("shell", "pm", "path", "com.salatv.app", serial=serial, timeout=10),
    }


def lists_for(tv):
    index = [l.split("\t") for l in (LISTS / "index.tsv").read_text(encoding="utf-8").splitlines() if l.strip()]
    model = tv["model"].lower()
    exact = [r for r in index if len(model) >= 4 and (model in r[0] or model in r[1].lower())]
    if exact:
        return exact
    maker = tv["maker"].lower()
    return [r for r in index if r[0].startswith(maker) or ("fire os" in tv["os"].lower() and "Fire OS" in r[2])]


def read_list(path):
    pkgs = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        p = line.split("#")[0].strip()
        if p and " " not in p and p not in PROTECTED and not p.startswith("com.android."):
            pkgs.append(p)
    return pkgs


def backup(tv):
    d = ROOT / "copias"
    d.mkdir(exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M")
    f = d / f"{tv['ip']}-{stamp}.txt"
    f.write_text("# todos\n" + adb("shell", "pm", "list", "packages", serial=tv["serial"]) +
                 "\n# desactivados\n" + adb("shell", "pm", "list", "packages", "-d", serial=tv["serial"]), encoding="utf-8")
    print(f"Copia guardada en {f}")


def clean(tv):
    options = lists_for(tv)
    print("\nListas de limpieza:")
    print("  0) La de la tele del salón (la que aplicaste con CleanYourTV)")
    for i, r in enumerate(options, 1):
        print(f"  {i}) {r[1]} · {r[2]}")
    pick = input("¿Cuál? (Enter para cancelar): ").strip()
    if not pick:
        return
    path = OWN_LIST if pick == "0" else LISTS / f"{options[int(pick) - 1][0]}.txt"
    pkgs = read_list(path)
    backup(tv)
    print(f"Desactivando {len(pkgs)} apps…")
    done = 0
    for p in pkgs:
        out = adb("shell", "pm", "disable-user", "--user", "0", p, serial=tv["serial"], timeout=20)
        if "new state: disabled" in out:
            done += 1
    print(f"Listo: {done} desactivadas; el resto no estaba instalado o Fire OS no dejó tocarlas.")


def install(tv, apk):
    if not Path(apk).exists():
        print(f"No está el APK: {apk}. Compílalo con .\\gradlew assembleRelease")
        return
    print("Instalando SalaTV…")
    print(adb("install", "-r", str(apk), serial=tv["serial"], timeout=180))
    print("Ahora en la tele: abre SalaTV y pulsa Configurar > Configurar todo.")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=str(DEFAULT_APK))
    args = ap.parse_args()
    print("SalaTV Instalador\n")
    ips = scan()
    if not ips:
        sys.exit("No hay ninguna tele con la depuración ADB activa en la red.")
    tvs = [describe(ip) for ip in ips]
    for i, tv in enumerate(tvs, 1):
        if tv["ok"]:
            state = "SalaTV instalada" if tv["salatv"] else "sin SalaTV"
            print(f"  {i}) {tv['maker']} {tv['model']} · {tv['ip']} · {tv['os']} · {state}")
        else:
            print(f"  {i}) {tv['ip']} · {tv['why']}")
    pick = input("\n¿Qué tele? (número): ").strip()
    if not pick.isdigit() or not 1 <= int(pick) <= len(tvs) or not tvs[int(pick) - 1]["ok"]:
        sys.exit("Nada que hacer.")
    tv = tvs[int(pick) - 1]
    print("\n  1) Instalar SalaTV\n  2) Aplicar limpieza\n  3) Las dos")
    what = input("¿Qué hago? ").strip()
    if what in ("1", "3"):
        install(tv, args.apk)
    if what in ("2", "3"):
        clean(tv)


if __name__ == "__main__":
    main()
